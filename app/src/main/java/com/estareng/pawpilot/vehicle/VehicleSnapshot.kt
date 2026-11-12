package com.estareng.pawpilot.vehicle

/** 펫 코파일럿이 쓰는 차량 신호 한 묶음. 값이 없으면 null (차종별 capability 차이). */
data class VehicleSnapshot(
    val isDriving: Boolean,                 // 기어 D/R = 주행, P/N = 정차
    val brakePedalPercent: Int? = null,     // Brake 0~100
    val steeringAngleDeg: Float? = null,    // Steeringwheel ±540
    val drivingMinutes: Int? = null,        // 이번 주행 누적 시간
    val distanceKm: Float? = null,          // 이번 주행 거리
    val cabinTargetTempC: Float? = null,    // HVAC 목표온도
    val rearWindowOpenPercent: Int? = null, // Window
    val rearSeatOccupied: Boolean? = null,  // Seat occupancy
)
