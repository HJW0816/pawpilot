package com.estareng.pawpilot.reviews

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Private device drafts only. These are never presented as other families' reviews. */
class VisitReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("pawpilot_reviews", Context.MODE_PRIVATE)
    fun read(place: String): VisitReview? = runCatching {
        val json = JSONObject(prefs.getString(place, null) ?: return null)
        VisitReview(place, json.getInt("stars"), json.getInt("petKind"),
            json.getJSONArray("tags").let { array -> List(array.length()) { array.getString(it) } },
            json.getString("comment"), json.getLong("updatedAt")).takeIf { it.error() == null }
    }.getOrNull()
    fun save(review: VisitReview) {
        require(review.error() == null)
        prefs.edit().putString(review.place, JSONObject().put("stars", review.stars).put("petKind", review.petKind)
            .put("tags", JSONArray(review.tags)).put("comment", review.comment).put("updatedAt", review.updatedAt).toString()).apply()
    }
    fun remove(place: String) { prefs.edit().remove(place).apply() }
}
