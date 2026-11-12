package com.estareng.pawpilot.vehicle

data class VehicleUx(
    val known: Boolean = false,
    val setupAllowed: Boolean = false,
    val keyboardAllowed: Boolean = false,
    val contentLimited: Boolean = true,
)

