package com.estareng.pawpilot

import android.content.Context
import com.estareng.pawpilot.vehicle.FakeVehicleSource
import com.estareng.pawpilot.vehicle.VehicleMotionSource

/** Process-owned, explicitly started journey. Vehicle mode counts fresh speed observations only. */
class JourneySession private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pawpilot", Context.MODE_PRIVATE)
    private val source = FakeVehicleSource()
    private val vehicle = VehicleMotionSource(context.applicationContext)
    val vehicleMode: Boolean get() = prefs.getBoolean("vehicle_mode", false)
    val motionKnown: Boolean get() = !vehicleMode || !active || vehicle.moving != null
    val parked: Boolean get() = if (vehicleMode && active) vehicle.moving == false else !active
    fun selectVehicleMode(enabled: Boolean): Boolean {
        if (active) return false
        sync(); prefs.edit().putBoolean("vehicle_mode", enabled).apply()
        source.restore(trip.elapsedMs); vehicle.restore(trip.elapsedMs); changed(); return true
    }
    val trip = TripState(prefs.getLong("trip_elapsed", 0), prefs.getLong("trip_rest_at", 0), prefs.getInt("trip_rests", 0))
    private val listeners = mutableSetOf<() -> Unit>()
    private var checkpoint = -1L
    var active = false
        private set
    val interval: Int get() = prefs.getInt("interval", 60).takeIf { it in listOf(30, 60, 90, 120) } ?: 60

    init { source.restore(trip.elapsedMs); vehicle.restore(trip.elapsedMs) }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    fun start() {
        if (active) return
        active = true
        if (vehicleMode) vehicle.start { sync(); changed() } else source.start { sync(); changed() }
        changed()
    }
    fun pause() {
        source.stop(); vehicle.stop(); sync(); active = false; save(); changed()
    }
    fun sync() {
        trip.updateElapsed(if (vehicleMode) vehicle.elapsedMs() else source.elapsedMs())
        if (trip.elapsedMs / 60_000 != checkpoint) save()
    }
    fun save() {
        checkpoint = trip.elapsedMs / 60_000
        prefs.edit().putLong("trip_elapsed", trip.elapsedMs).putLong("trip_rest_at", trip.restAtMs)
            .putInt("trip_rests", trip.restCount).apply()
    }
    fun reset() { pause(); source.restore(0); vehicle.restore(0); trip.reset(); prefs.edit().remove("last_rest_alert").apply(); save(); changed() }

    companion object {
        private var instance: JourneySession? = null
        fun get(context: Context): JourneySession = instance ?: JourneySession(context).also { instance = it }
    }
}
