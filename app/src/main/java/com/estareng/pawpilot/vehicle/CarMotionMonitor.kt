package com.estareng.pawpilot.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Continuous speed events; unknown and disconnected are never treated as parked. */
internal class CarMotionMonitor(private val context: Context, private val changed: (Float?, Long) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var generation = 0
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private val expired = Runnable { changed(null, SystemClock.elapsedRealtime()) }
    fun start() {
        stop()
        val token = generation
        changed(null, SystemClock.elapsedRealtime())
        try {
            car = Car.createCar(context.applicationContext, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                if (generation != token) return@createCar
                if (!ready) { handler.removeCallbacks(expired); changed(null, SystemClock.elapsedRealtime()); return@createCar }
                try {
                    manager = service.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    val listener = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(value: CarPropertyValue<*>) {
                            if (generation != token || value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
                            val speed = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Float else null
                            changed(speed, value.timestamp / 1_000_000)
                            handler.removeCallbacks(expired); handler.postDelayed(expired, 3_000)
                        }
                        override fun onErrorEvent(propertyId: Int, areaId: Int) {
                            if (generation == token) { handler.removeCallbacks(expired); changed(null, SystemClock.elapsedRealtime()) }
                        }
                    }
                    callback = listener
                    if (manager?.registerCallback(listener, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f) != true)
                        changed(null, SystemClock.elapsedRealtime())
                } catch (_: Exception) { changed(null, SystemClock.elapsedRealtime()) }
            }
        } catch (_: Exception) { changed(null, SystemClock.elapsedRealtime()) }
    }
    fun stop() {
        generation++; handler.removeCallbacks(expired)
        callback?.let { runCatching { manager?.unregisterCallback(it) } }; callback = null; manager = null
        runCatching { car?.disconnect() }; car = null
    }
}
