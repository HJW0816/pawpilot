package com.estareng.pawpilot.navigation

data class PetFacility(val kind: String, val location: String, val hours: String, val closed: String)

data class PetRestStop(val name: String, val facilities: List<PetFacility>) {
    // Preserve the source direction, e.g. 매송(목포) and 매송(서울) are different stops.
    val searchKeyword: String get() = if ('(' in name) name.replace("(", "휴게소(") else "${name}휴게소"
}

fun groupRestStops(rows: List<Pair<String, PetFacility>>): List<PetRestStop> = rows
    .groupBy({ it.first }, { it.second })
    .map { (name, facilities) -> PetRestStop(name, facilities) }
    .sortedBy { it.name }
