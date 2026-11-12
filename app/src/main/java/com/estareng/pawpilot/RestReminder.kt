package com.estareng.pawpilot

/** A route selection never changes the rest deadline. Only a recorded rest does. */
class RestReminder(private var lastNotified: String? = null) {
    fun dueKey(trip: TripState, interval: Int): String? =
        if (trip.remainingMinutes(interval) == 0) "${trip.restCount}:${trip.restAtMs}:$interval" else null

    fun consume(trip: TripState, interval: Int): String? {
        val key = dueKey(trip, interval) ?: return null
        if (key == lastNotified) return null
        lastNotified = key
        return key
    }
}
