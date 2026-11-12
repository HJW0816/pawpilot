package com.estareng.pawpilot.vehicle

/** 시뮬레이션 실행 시간만 누적한다. clockMs는 단조 증가 시계여야 한다. */
internal class DrivingTimer(private val clockMs: () -> Long) {
    private var accumulatedMs = 0L
    private var startedAtMs: Long? = null

    fun start() {
        if (startedAtMs == null) startedAtMs = clockMs()
    }

    fun stop() {
        accumulatedMs = elapsedMs()
        startedAtMs = null
    }

    fun elapsedMs(): Long = accumulatedMs +
        (startedAtMs?.let { (clockMs() - it).coerceAtLeast(0L) } ?: 0L)

    fun minutes(): Int = (elapsedMs() / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun restore(elapsed: Long) {
        accumulatedMs = elapsed.coerceAtLeast(0)
        startedAtMs = null
    }
}
