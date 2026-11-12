import com.estareng.pawpilot.vehicle.ObservedDrivingClock
fun main() {
    val clock = ObservedDrivingClock()
    check(clock.sample(10f, 1000, 1000) == true && clock.elapsedMs == 0L)
    clock.sample(10f, 2000, 2000); check(clock.elapsedMs == 1000L)
    clock.sample(0f, 3000, 3000); clock.sample(0f, 4000, 4000); check(clock.elapsedMs == 1000L)
    clock.sample(-2f, 5000, 5000); clock.sample(-2f, 6000, 6000); check(clock.elapsedMs == 2000L)
    clock.sample(10f, 20_000, 20_000); check(clock.elapsedMs == 2000L) // signal gap
    check(clock.sample(Float.NaN, 21_000, 21_000) == null)
    clock.sample(10f, 22_000, 22_000); check(clock.elapsedMs == 2000L)
    check(clock.sample(10f, 23_000, 22_000) == null) // future
    check(clock.sample(10f, 23_000, 30_000) == null) // stale
    clock.restore(5000); clock.sample(10f, 31_000, 31_000); clock.invalidate()
    clock.sample(10f, 32_000, 32_000); check(clock.elapsedMs == 5000L)
    clock.sample(10f, 32_000, 32_000); check(clock.elapsedMs == 5000L)
    println("PASS observed driving: stopped, reverse, stale/future/invalid/duplicate samples, loss, reconnect, restore; no extrapolation")
}
