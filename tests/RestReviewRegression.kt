import com.estareng.pawpilot.RestReminder
import com.estareng.pawpilot.TripState
import com.estareng.pawpilot.PetProfile
import com.estareng.pawpilot.reviews.VisitReview

fun main() {
    val trip = TripState()
    val reminder = RestReminder()
    check(reminder.consume(trip, 30) == null)
    trip.updateElapsed(29 * 60_000L)
    check(reminder.consume(trip, 30) == null)
    trip.updateElapsed(30 * 60_000L)
    val due = reminder.consume(trip, 30)
    check(due != null && reminder.consume(trip, 30) == null)
    // Route, candidate and destination changes do not reset the deadline.
    trip.updateElapsed(45 * 60_000L)
    check(reminder.consume(trip, 30) == null && trip.remainingMinutes(30) == 0)
    check(trip.completeRest())
    check(reminder.consume(trip, 30) == null && trip.remainingMinutes(30) == 30)
    trip.updateElapsed(75 * 60_000L)
    check(reminder.consume(trip, 30) != null)
    check(PetProfile.breedError("푸들") == null)
    check(PetProfile.breedError("a".repeat(41)) != null)
    check(VisitReview("매송(서울)", 5, 1, listOf("산책 공간"), "조용했어요", 1L).error() == null)
    check(VisitReview("매송(서울)", 0, 1, emptyList(), "", 1L).error() != null)
    check(VisitReview("매송(서울)", 5, 1, listOf("가짜 시설"), "", 1L).error() != null)
    println("PASS rest reminders: route-independent deadline and once-per-rest; review/profile: validated species, stars, tags")
}
