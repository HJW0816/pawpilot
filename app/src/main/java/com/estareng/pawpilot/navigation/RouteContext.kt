package com.estareng.pawpilot.navigation

data class NaviPlace(
    val name: String, val latitude: Double, val longitude: Double,
    val poiId: String, val poiSubId: String?, val provider: String, val address: String,
) {
    val usable: Boolean get() = name.isNotBlank() && poiId.isNotBlank() && !poiSubId.isNullOrBlank() &&
        provider.isNotBlank() && validCoordinates(latitude, longitude)
    val identity: String get() = listOf(provider, poiId, poiSubId, latitude, longitude).toString()
    fun samePlace(other: NaviPlace): Boolean =
        (provider == other.provider && poiId.isNotBlank() && poiId == other.poiId && poiSubId == other.poiSubId) ||
            (latitude == other.latitude && longitude == other.longitude)
}

data class RouteContext(
    val connected: Boolean = false,
    val arrivalName: String? = null,
    val loading: Boolean = true,
    val guiding: Boolean = false,
    val destination: NaviPlace? = null,
    val waypoints: List<NaviPlace> = emptyList(),
    val waypointInfoReceived: Boolean = false,
    val instructions: List<String> = emptyList(),
    val instructionInfoReceived: Boolean = false,
    val remainingMeters: Int? = null,
    val remainingSeconds: Int? = null,
    val message: String = "현대 내비의 경로를 확인하고 있어요…",
) {
    val key: String get() = listOf(destination?.identity, waypoints.map { it.identity }).toString()
}

/** Only a facility explicitly named in received guidance can match; this is not a route corridor search. */
fun guidanceMatches(stops: List<PetRestStop>, instructions: List<String>): List<PetRestStop> {
    return stops.filter { stop ->
        val base = Regex.escape(stop.name.substringBefore('('))
        val direction = stop.name.substringAfter('(', "").substringBefore(')')
        // Bind the direction to this exact rest area, never to another clause or destination.
        direction.isNotEmpty() && instructions.any { text ->
            Regex("(?<![가-힣A-Za-z0-9])${base}휴게소[（(]${Regex.escape(direction)}(?:방면|방향)?[）)]")
                .containsMatchIn(text.replace(Regex("\\s"), ""))
        }
    }
}

data class RouteStopCandidate(val stop: PetRestStop, val directionConfirmed: Boolean)

/** A stable candidate set for recommendations; changing turn distances do not change this list. */
fun guidanceRestAreaNames(instructions: List<String>): List<String> =
    instructions.flatMap { instruction ->
        Regex("[가-힣A-Za-z0-9·]+휴게소(?:\\([^)]*\\))?").findAll(instruction).map { it.value }.toList()
    }.distinct().sorted()

fun recommendationRequestKey(route: RouteContext, matches: List<RouteStopCandidate>, profileKey: String, restDue: Boolean): String =
    listOf(route.key, guidanceRestAreaNames(route.instructions),
        matches.map { it.stop.name to it.directionConfirmed }.sortedBy { it.first }, profileKey, restDue).toString()

/** Names from the received guidance only; ambiguous carriageways remain explicit candidates. */
fun routeStopCandidates(stops: List<PetRestStop>, instructions: List<String>): List<RouteStopCandidate> =
    instructions.flatMap { instruction ->
        val text = instruction.replace(Regex("\\s"), "")
        val named = stops.filter { stop ->
            val base = Regex.escape(stop.name.substringBefore('('))
            Regex("(?<![가-힣])${base}휴게소").containsMatchIn(text)
        }
        val confirmed = guidanceMatches(named, listOf(instruction)).toSet()
        // If a direction is explicitly supplied, do not offer the opposite carriageway.
        (if (confirmed.isNotEmpty()) named.filter { it in confirmed } else named)
            .map { RouteStopCandidate(it, it in confirmed) }
    }.groupBy { it.stop.name }.values.map { candidates ->
        candidates.firstOrNull { it.directionConfirmed } ?: candidates.first()
    }

fun canAppendStop(route: RouteContext, expectedRouteKey: String, stop: NaviPlace): Boolean =
    route.connected && !route.loading && route.guiding && route.destination != null &&
        route.waypointInfoReceived && route.key == expectedRouteKey && route.waypoints.size < 2 &&
        stop.poiId.isNotBlank() && stop.poiSubId != null &&
        stop.latitude.isFinite() && stop.longitude.isFinite() &&
        stop.latitude in -90.0..90.0 && stop.longitude in -180.0..180.0 &&
        !(stop.latitude == 0.0 && stop.longitude == 0.0) &&
        !route.destination.samePlace(stop) && route.waypoints.none { it.samePlace(stop) }

fun validCoordinates(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

/** A complete route proposal, never inferred from community address text. */
data class NativeRoutePlan(val destination: NaviPlace, val stops: List<NaviPlace>) {
    val valid: Boolean get() = destination.usable && stops.size <= 2 && stops.all { it.usable } &&
        stops.none { it.samePlace(destination) } &&
        stops.indices.none { i -> stops.take(i).any { it.samePlace(stops[i]) } }
    fun matches(route: RouteContext): Boolean = route.guiding && route.destination?.samePlace(destination) == true &&
        route.waypointInfoReceived && route.waypoints.size == stops.size &&
        stops.indices.all { stops[it].samePlace(route.waypoints[it]) }
}
