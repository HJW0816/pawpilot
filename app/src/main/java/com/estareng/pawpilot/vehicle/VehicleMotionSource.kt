package com.estareng.pawpilot.vehicle

import android.content.Context
import android.os.SystemClock

class VehicleMotionSource(private val context: Context) {
    private val clock = ObservedDrivingClock()
    private var monitor: CarMotionMonitor? = null
    var moving: Boolean? = null
        private set
    fun elapsedMs() = clock.elapsedMs
    fun restore(value: Long) { stop(); clock.restore(value) }
    fun start(changed: () -> Unit) {
        stop()
        try {
            Class.forName("android.car.Car")
            monitor = CarMotionMonitor(context) { speed, timestamp ->
                moving = if (speed == null) { clock.invalidate(); null }
                    else clock.sample(speed, timestamp, SystemClock.elapsedRealtime())
                changed()
            }.also { it.start() }
        } catch (_: Exception) { changed() }
        catch (_: LinkageError) { changed() }
    }
    fun stop() { monitor?.stop(); monitor = null; clock.invalidate(); moving = null }
}
