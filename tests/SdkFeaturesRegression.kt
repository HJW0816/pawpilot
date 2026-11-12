import com.estareng.pawpilot.navigation.*
import com.estareng.pawpilot.location.LocationFix

fun main() {
    val dest = NaviPlace("목적지", 37.0, 127.0, "dest", "0", "native", "")
    val first = dest.copy(name = "첫 쉼터", latitude = 36.8, poiId = "one")
    val second = dest.copy(name = "두 번째", latitude = 36.9, poiId = "two")
    val plan = NativeRoutePlan(dest, listOf(first, second))
    check(plan.valid)
    check(!NativeRoutePlan(dest, listOf(first, first)).valid)
    check(!NativeRoutePlan(dest, listOf(dest)).valid)
    check(!NativeRoutePlan(dest, listOf(first, second, first)).valid)
    check(!NativeRoutePlan(dest.copy(provider = ""), emptyList()).valid)
    check(!NativeRoutePlan(dest.copy(poiSubId = ""), emptyList()).valid)
    check(!NativeRoutePlan(dest.copy(latitude = Double.NaN), emptyList()).valid)
    val read = RouteContext(guiding = true, destination = dest, waypoints = listOf(first, second), waypointInfoReceived = true)
    check(plan.matches(read))
    check(!plan.matches(read.copy(waypoints = listOf(second, first))))
    check(!plan.matches(read.copy(waypointInfoReceived = false)))
    check(!plan.matches(read.copy(destination = first)))
    check(!plan.matches(read.copy(guiding = false)))
    check(!NativeRoutePlan(dest, emptyList()).matches(read))
    val now = 1_800_000L
    val fix = LocationFix(37.0, 127.0, now - 1000, .9f)
    check(fix.fresh(now))
    check(!fix.copy(timestamp = now + 1).fresh(now))
    check(!fix.copy(timestamp = now - 60_001).fresh(now))
    check(!fix.copy(confidence = Float.NaN).fresh(now))
    check(!fix.copy(latitude = Double.POSITIVE_INFINITY).fresh(now))
    check(!fix.copy(latitude = 0.0, longitude = 0.0).fresh(now))
    check(fix.distanceMeters(37.0, 127.0) == 0.0)
    check(fix.distanceMeters(38.0, 127.0) in 111_000.0..112_000.0)
    println("PASS SDK features: route POI validation, ordered waypoint confirmation, old/future/invalid fixes, straight-line distance")
}
