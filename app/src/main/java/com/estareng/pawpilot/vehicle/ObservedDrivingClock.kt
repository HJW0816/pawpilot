package com.estareng.pawpilot.vehicle

/** Count only intervals bounded by two fresh moving observations. No extrapolation after signal loss. */
internal class ObservedDrivingClock {
    var elapsedMs = 0L
        private set
    private var previousMs: Long? = null
    private var moving = false
    fun restore(value: Long) { elapsedMs = value.coerceAtLeast(0); invalidate() }
    fun invalidate() { previousMs = null; moving = false }
    fun sample(speed: Float, sampleMs: Long, nowMs: Long): Boolean? {
        if (!speed.isFinite() || sampleMs > nowMs || nowMs - sampleMs > 3_000) {
            invalidate(); return null
        }
        val previous = previousMs
        if (previous != null && sampleMs <= previous) return null
        val nextMoving = kotlin.math.abs(speed) > 0.1f
        if (previous != null && sampleMs - previous <= 3_000 && moving && nextMoving)
            elapsedMs += sampleMs - previous
        previousMs = sampleMs; moving = nextMoving
        return nextMoving
    }
}
