package com.estareng.pawpilot.vehicle

/**
 * 차량 데이터 출처 추상화.
 * - [FakeVehicleSource]: UI 개발·시연용 시뮬레이션
 * - [PleosVehicleSource]: Pleos Vehicle SDK 실제 연동
 */
interface VehicleSource {
    fun start(onUpdate: (VehicleSnapshot) -> Unit)
    fun stop()
}
