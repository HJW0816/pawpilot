import com.estareng.pawpilot.PetComfortEngine
import com.estareng.pawpilot.ComfortLevel
import com.estareng.pawpilot.vehicle.DrivingTimer
import com.estareng.pawpilot.vehicle.VehicleSnapshot

fun main() {
    var now = 0L
    val timer = DrivingTimer { now }
    timer.start()
    check(timer.minutes() == 0)
    now = 1_000L
    check(timer.minutes() == 0)
    now = 59_999L
    check(timer.minutes() == 0)
    timer.start() // 중복 시작으로 초기화하지 않음
    now = 60_000L
    check(timer.minutes() == 1)
    now = 90_000L
    timer.stop()
    now = 690_000L // 중지 중 시간 제외
    timer.stop()
    check(timer.elapsedMs() == 90_000L)
    timer.start()
    now = 720_000L
    check(timer.minutes() == 2)
    now = 900_000L // 콜백이 늦어져도 실제 경과 시간 반영
    check(timer.minutes() == 5)

    for ((minutes, remaining) in listOf(null to 60, -1 to 60, 0 to 60, 54 to 6,
        55 to 5, 59 to 1, 60 to 0, 61 to 0, 120 to 0, Int.MAX_VALUE to 0)) {
        val state = PetComfortEngine().update(VehicleSnapshot(true, drivingMinutes = minutes))
        check(state.minutesToRest == remaining) { "$minutes: $state" }
        check(state.level == if (remaining <= 5) ComfortLevel.REST_SOON else ComfortLevel.CALM)
    }
    check(runCatching { PetComfortEngine(0) }.isFailure)
    check(runCatching { PetComfortEngine(-1) }.isFailure)
    check(PetComfortEngine(30).update(VehicleSnapshot(true, drivingMinutes = 30)).minutesToRest == 0)
    println("PASS: timer boundaries, delayed updates, duplicate start/stop, resume, rest deadline")
}
