package com.estareng.pawpilot

import com.estareng.pawpilot.vehicle.VehicleSnapshot
import kotlin.math.abs

/** 펫 동승 상태 단계. 주행 중 화면은 이 한 줄만 보여 준다(Glanceability 1~2초). */
enum class ComfortLevel { CALM, WATCH, REST_SOON }

data class ComfortState(
    val level: ComfortLevel,
    val score: Int,              // 0(편안) ~ 100(멀미 위험)
    val minutesToRest: Int,      // 다음 권장 휴식까지 남은 분
)

/**
 * 멀미·불안 위험 추정.
 * - 급제동(브레이크 ≥ 50%)과 급조향(직전 대비 30° 이상 변화)을 이벤트로 누적, 시간이 지나면 감쇠
 * - 휴식 권장 주기: 기본 60분 (반려동물 프로필로 조정 예정)
 * 기준값은 수의학 자료 검토 후 튜닝 필요.
 */
class PetComfortEngine(private val restIntervalMin: Int = 60) {
    init {
        require(restIntervalMin > 0) { "restIntervalMin must be positive" }
    }
    private var risk = 0.0
    private var lastSteering: Float? = null

    fun update(s: VehicleSnapshot): ComfortState {
        risk *= 0.95 // 감쇠
        if ((s.brakePedalPercent ?: 0) >= 50) risk += 15
        val steer = s.steeringAngleDeg
        val prev = lastSteering
        if (steer != null && prev != null && abs(steer - prev) >= 30f) risk += 8
        lastSteering = steer
        val score = risk.coerceIn(0.0, 100.0).toInt()

        val driven = (s.drivingMinutes ?: 0).coerceAtLeast(0)
        // 휴식 완료 신호가 없으므로 기한이 지나도 자동으로 주기를 초기화하지 않는다.
        val toRest = (restIntervalMin - driven).coerceAtLeast(0)

        val level = when {
            score >= 60 || toRest <= 5 -> ComfortLevel.REST_SOON
            score >= 30 -> ComfortLevel.WATCH
            else -> ComfortLevel.CALM
        }
        return ComfortState(level, score, toRest)
    }
}
