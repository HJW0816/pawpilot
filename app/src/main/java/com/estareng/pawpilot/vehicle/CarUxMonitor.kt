package com.estareng.pawpilot.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

internal class AndroidCarUxMonitor(private val context: Context, private val changed: (VehicleUx) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0
    fun start() {
        stop()
        val token = generation
        changed(VehicleUx())
        try {
            car = Car.createCar(context.applicationContext, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                if (generation != token) return@createCar
                if (!ready) { changed(VehicleUx()); return@createCar }
                try {
                    manager = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    manager?.registerListener { restrictions -> if (generation == token) update(restrictions) }
                    manager?.currentCarUxRestrictions?.let(::update)
                } catch (error: Exception) { Log.w("PawPilotUx", "UX restrictions unavailable", error); changed(VehicleUx()) }
            }
        } catch (error: Exception) { Log.w("PawPilotUx", "Car connection unavailable", error); changed(VehicleUx()) }
    }
    private fun update(value: CarUxRestrictions) {
        val flags = if (value.isRequiresDistractionOptimization) value.activeRestrictions else 0
        Log.i("PawPilotUx", "Restrictions flags=$flags")
        changed(VehicleUx(true, flags and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP == 0,
            flags and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD == 0,
            flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0))
    }
    fun stop() {
        generation++
        runCatching { manager?.unregisterListener() }; manager = null
        runCatching { car?.disconnect() }; car = null
    }
}
