package com.estareng.pawpilot

/** Preview driving time only. Parking and time away from the app never advance it. */
class TripState(elapsedMs: Long = 0, restAtMs: Long = 0, restCount: Int = 0) {
    var elapsedMs: Long = elapsedMs.coerceAtLeast(0)
        private set
    var restAtMs: Long = restAtMs.coerceIn(0, this.elapsedMs)
        private set
    var restCount: Int = restCount.coerceAtLeast(0)
        private set

    fun updateElapsed(value: Long) { elapsedMs = maxOf(elapsedMs, value) }

    fun remainingMinutes(interval: Int): Int {
        require(interval > 0)
        val remaining = (interval * 60_000L - (elapsedMs - restAtMs)).coerceAtLeast(0)
        return ((remaining + 59_999) / 60_000).toInt()
    }

    fun progress(interval: Int): Int {
        require(interval > 0)
        return (((elapsedMs - restAtMs).coerceAtMost(interval * 60_000L) * 100) /
            (interval * 60_000L)).toInt()
    }

    fun completeRest(): Boolean {
        if (elapsedMs <= restAtMs) return false
        restAtMs = elapsedMs
        if (restCount < Int.MAX_VALUE) restCount++
        return true
    }

    fun reset() { elapsedMs = 0; restAtMs = 0; restCount = 0 }
}

data class PetProfile(val name: String = "우리 아이", val kind: Int = 0,
    val breed: String = "", val size: String = "선택 안 함", val quietPreferred: Boolean = false) {
    companion object {
        val kinds = listOf("강아지", "고양이", "기타")
        fun nameError(value: String): String? = when {
            value.trim().isEmpty() -> "이름을 입력해 주세요."
            value.trim().length > 20 -> "이름은 20자 이내로 입력해 주세요."
            value.any { it.isISOControl() } -> "이름에 줄바꿈이나 제어 문자를 넣을 수 없어요."
            else -> null
        }
        fun breedError(value: String): String? = when {
            value.length > 40 -> "품종·특징은 40자 이내로 적어 주세요."
            value.any { it.isISOControl() } -> "줄바꿈이나 제어 문자를 넣을 수 없어요."
            else -> null
        }
        val sizes = listOf("선택 안 함", "소형", "중형", "대형")
    }
}
