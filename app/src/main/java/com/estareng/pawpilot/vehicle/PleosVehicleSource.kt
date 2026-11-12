package com.estareng.pawpilot.vehicle

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle

/**
 * Pleos Vehicle SDK 연동.
 * 수명주기: Vehicle(context) → initialize() → 도메인 API(checkXxxCapabilities / getCurrentXxx / registerXxx) → release()
 * - 결과는 IPC 비동기 콜백(onComplete/onFailed). 콜백 완료 전 release() 금지.
 * - 차종별 지원 여부는 반드시 checkXxxCapabilities로 확인 (모델명 분기 금지).
 * - VehicleSpeedListener는 v2.3.2에서 삭제됨 → 주행/정차는 기어 또는 CarPropertyManager(PERF_VEHICLE_SPEED).
 *
 * TODO(다음 단계): 샘플 앱(pleos-sample-vehicle-app)에서 정확한 클래스/패키지를 확인한 뒤
 *  Brake / Steeringwheel / Odometer(Trip) / HVAC / Window / Seat 리스너를 등록해 [VehicleSnapshot]으로 합친다.
 */
class PleosVehicleSource(context: Context) : VehicleSource {
    private val vehicle = Vehicle(context)

    override fun start(onUpdate: (VehicleSnapshot) -> Unit) {
        vehicle.initialize()
        // 도메인별 register 호출은 샘플 앱 기준으로 추가
    }

    override fun stop() {
        vehicle.release()
    }
}
