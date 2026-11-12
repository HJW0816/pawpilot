package com.estareng.pawpilot.location

import com.estareng.pawpilot.navigation.validCoordinates
import kotlin.math.*

data class LocationFix(val latitude: Double, val longitude: Double, val timestamp: Long, val confidence: Float) {
    fun fresh(now: Long): Boolean = validCoordinates(latitude, longitude) && timestamp > 0 &&
        timestamp <= now && now - timestamp <= 60_000 && confidence.isFinite() && confidence > 0f
    fun distanceMeters(lat: Double, lon: Double): Double {
        val dLat = Math.toRadians(lat - latitude); val dLon = Math.toRadians(lon - longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(lat)) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
