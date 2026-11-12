package com.estareng.pawpilot.recommendation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.estareng.pawpilot.R
import com.estareng.pawpilot.PetProfile
import com.estareng.pawpilot.navigation.RouteContext
import com.estareng.pawpilot.navigation.RouteStopCandidate
import com.estareng.pawpilot.navigation.guidanceRestAreaNames
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class SourceEvidence(val type: String, val summary: String, val url: String, val publishedAt: String)
data class RecommendationReason(val text: String, val sources: List<SourceEvidence>)
data class RecommendationItem(val id: String, val name: String, val directionConfirmed: Boolean,
    val reasons: List<RecommendationReason>, val cautions: List<String>, val reviewCount: Int, val averageRating: Double?)
data class RecommendationResult(val status: String, val ranked: List<RecommendationItem>)

/** Optional HTTPS gateway. API credentials and licensed review corpus stay on the server. */
class RecommendationClient(context: Context) {
    private val base = context.getString(R.string.recommendation_base_url).trim()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var serial = 0
    fun cancel() { serial++ }
    fun close() { cancel(); executor.shutdownNow() }

    fun request(route: RouteContext, stops: List<RouteStopCandidate>, profile: PetProfile, restMinutes: Int,
        callback: (RecommendationResult) -> Unit) {
        val token = ++serial
        if (base.isEmpty()) { main.post { if (token == serial) callback(RecommendationResult("not_configured", emptyList())) }; return }
        if (!base.startsWith("https://")) { main.post { if (token == serial) callback(RecommendationResult("unavailable", emptyList())) }; return }
        val candidates = JSONArray()
        val seen = mutableSetOf<String>()
        stops.forEach { candidate ->
            if (seen.add(candidate.stop.name)) candidates.put(JSONObject().put("id", candidate.stop.name)
                .put("name", candidate.stop.searchKeyword).put("directionConfirmed", candidate.directionConfirmed))
        }
        guidanceRestAreaNames(route.instructions).forEach { name ->
            if (seen.add(name) && candidates.length() < 20) candidates.put(JSONObject().put("id", name)
                .put("name", name).put("directionConfirmed", false))
        }
        val kind = listOf("dog", "cat", "other")[profile.kind.coerceIn(0..2)]
        val input = JSONObject().put("profile", JSONObject().put("kind", kind).put("breed", profile.breed)
            .put("size", profile.size).put("quietPreferred", profile.quietPreferred))
            .put("restMinutes", restMinutes).put("candidates", candidates)
        executor.execute {
            val answer = try {
                val conn = URL(base.trimEnd('/') + "/v1/recommendations").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"; conn.connectTimeout = 8_000; conn.readTimeout = 25_000
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) throw IllegalStateException("Gateway error")
                    val payload = conn.inputStream.bufferedReader().use { it.readText().take(64_000) }
                    decode(JSONObject(payload))
                } finally { conn.disconnect() }
            } catch (_: Exception) { RecommendationResult("unavailable", emptyList()) }
            main.post { if (token == serial) callback(answer) }
        }
    }

    private fun decode(json: JSONObject): RecommendationResult {
        val ranked = json.optJSONArray("ranked") ?: JSONArray()
        return RecommendationResult(json.optString("status", "unavailable"), List(ranked.length().coerceAtMost(3)) { index ->
            val item = ranked.getJSONObject(index)
            val reasonsJson = item.optJSONArray("reasons") ?: JSONArray()
            val reasons = List(reasonsJson.length().coerceAtMost(3)) { ri ->
                val reason = reasonsJson.getJSONObject(ri)
                val sourcesJson = reason.optJSONArray("sources") ?: JSONArray()
                RecommendationReason(reason.optString("text").take(300), List(sourcesJson.length().coerceAtMost(12)) { si ->
                    val source = sourcesJson.getJSONObject(si)
                    SourceEvidence(source.optString("type"), source.optString("summary").take(600),
                        source.optString("url"), source.optString("publishedAt"))
                })
            }
            val cautions = item.optJSONArray("cautions") ?: JSONArray()
            RecommendationItem(item.optString("id"), item.optString("name"), item.optBoolean("directionConfirmed"),
                reasons, List(cautions.length().coerceAtMost(4)) { cautions.optString(it).take(240) },
                item.optInt("reviewCount"), if (item.isNull("averageRating")) null else item.optDouble("averageRating"))
        })
    }
}
