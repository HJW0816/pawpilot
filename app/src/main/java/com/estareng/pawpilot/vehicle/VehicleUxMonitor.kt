package com.estareng.pawpilot.vehicle

import android.content.Context
import android.util.Log

/** Keep optional android.car types out of the Activity's class-loading path. */
class VehicleUxMonitor(private val context: Context, private val changed: (VehicleUx) -> Unit) {
    private var platform: AndroidCarUxMonitor? = null

    fun start() {
        stop()
        val available = try { Class.forName("android.car.Car"); true }
        catch (_: ClassNotFoundException) { false }
        catch (_: LinkageError) { false }
        if (!available) {
            Log.w("PawPilotUx", "android.car unavailable; setup remains restricted")
            changed(VehicleUx())
            return
        }
        platform = AndroidCarUxMonitor(context, changed).also { it.start() }
    }

    fun stop() { platform?.stop(); platform = null }
}
