import com.estareng.pawpilot.PetProfile
import com.estareng.pawpilot.TripState
import com.estareng.pawpilot.vehicle.DrivingTimer

fun main() {
    val trip = TripState()
    check(!trip.completeRest())
    trip.updateElapsed(59_999)
    check(trip.remainingMinutes(30) == 30)
    trip.updateElapsed(60_000)
    check(trip.remainingMinutes(30) == 29)
    trip.updateElapsed(30 * 60_000)
    check(trip.remainingMinutes(30) == 0 && trip.progress(30) == 100)
    trip.updateElapsed(180 * 60_000)
    check(trip.remainingMinutes(30) == 0) // no automatic reset after deadline
    check(trip.completeRest())
    check(!trip.completeRest() && trip.restCount == 1)
    check(trip.remainingMinutes(30) == 30)
    trip.updateElapsed(trip.elapsedMs + 1_234)
    val restored = TripState(trip.elapsedMs, trip.restAtMs, trip.restCount)
    check(restored.remainingMinutes(30) == 30 && restored.restCount == 1)
    check(restored.remainingMinutes(60) == 60) // interval change retains baseline
    check(restored.restAtMs == trip.restAtMs)
    restored.updateElapsed(0)
    check(restored.elapsedMs == trip.elapsedMs)
    restored.reset()
    check(restored.elapsedMs == 0L && restored.restCount == 0 && restored.remainingMinutes(30) == 30)

    val corrupt = TripState(-1, Long.MAX_VALUE, -2)
    check(corrupt.elapsedMs == 0L && corrupt.restAtMs == 0L && corrupt.restCount == 0)
    check(TripState(Long.MAX_VALUE).remainingMinutes(120) == 0)
    check(TripState(Long.MAX_VALUE).progress(120) == 100)
    check(runCatching { trip.remainingMinutes(0) }.isFailure)

    var now = 0L
    val timer = DrivingTimer { now }
    timer.restore(59_500)
    timer.start()
    now = 500
    check(timer.minutes() == 1)
    timer.stop()
    now = 3_600_500 // background / parked time is excluded
    timer.start()
    check(timer.elapsedMs() == 60_000L)
    timer.restore(0)
    check(timer.elapsedMs() == 0L)

    check(PetProfile.nameError("  보리  ") == null)
    check(PetProfile.nameError("   ") != null)
    check(PetProfile.nameError("가".repeat(21)) != null)
    check(PetProfile.nameError("가".repeat(20)) == null)
    check(PetProfile.nameError("보리\n") != null)
    println("PASS: rest deadlines, duplicate completion, exact restoration, interval changes, reset, invalid stored data, parked time, profile validation")
}
