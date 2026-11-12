package com.estareng.pawpilot.reviews

data class VisitReview(
    val place: String,
    val stars: Int,
    val petKind: Int,
    val tags: List<String>,
    val comment: String,
    val updatedAt: Long,
) {
    fun error(): String? = when {
        place.isBlank() -> "휴게소를 확인해 주세요."
        stars !in 1..5 -> "별점을 선택해 주세요."
        petKind !in 0..2 -> "동반한 반려동물을 선택해 주세요."
        comment.length > 200 -> "후기는 200자 이내로 적어 주세요."
        tags.any { it !in availableTags } -> "시설 항목을 확인해 주세요."
        else -> null
    }
    companion object { val availableTags = listOf("산책 공간", "그늘", "급수대", "깨끗해요", "혼잡해요") }
}
