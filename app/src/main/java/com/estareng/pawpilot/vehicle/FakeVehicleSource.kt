package com.estareng.pawpilot.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.random.Random

/** 에뮬레이터·시연용: 1초마다 주행 신호를 흉내 낸다. */
class FakeVehicleSource : VehicleSource {
    private val handler = Handler(Looper.getMainLooper())
    private val timer = DrivingTimer(SystemClock::elapsedRealtime)
    private var callback: ((VehicleSnapshot) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (callback == null) return
            val hardBrake = Random.nextInt(10) == 0
            callback?.invoke(
                VehicleSnapshot(
                    isDriving = true,
                    brakePedalPercent = if (hardBrake) 70 else Random.nextInt(0, 20),
                    steeringAngleDeg = Random.nextInt(-90, 90).toFloat(),
                    drivingMinutes = timer.minutes(),
                    distanceKm = timer.elapsedMs() / 60_000f * 1.2f,
                    cabinTargetTempC = 22.0f,
                    rearWindowOpenPercent = 0,
                    rearSeatOccupied = true,
                )
            )
            if (callback != null) handler.postDelayed(this, 1000)
        }
    }

    override fun start(onUpdate: (VehicleSnapshot) -> Unit) {
        callback = onUpdate
        timer.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        timer.stop()
        callback = null
    }

    fun elapsedMs(): Long = timer.elapsedMs()

    fun restore(elapsedMs: Long) {
        stop()
        timer.restore(elapsedMs)
    }
}
