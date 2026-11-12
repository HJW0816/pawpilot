package com.estareng.pawpilot.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.estareng.pawpilot.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/** Capability tokens live only in memory. HTTPS only; redirecting tokens to another host is forbidden. */
class CompanionClient(context: Context) {
    private val base = context.getString(R.string.companion_base_url).trim().trimEnd('/')
    val configured: Boolean get() = runCatching { URI(base).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null && it.path.isNullOrEmpty() } }.getOrDefault(false)
    val mobileUrl get() = "$base/companion"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    var session: JSONObject? = null
        private set
    var routeKey = ""
        private set
    fun create(names: List<String>, route: String, done: (JSONObject?) -> Unit) {
        if (!configured) { done(null); return }
        end()
        val token = ++generation
        worker.execute {
            val result = request("POST", "/v1/companion", JSONObject().put("candidates", JSONArray(names)))
            main.post { if (token == generation) { session = result; routeKey = route; done(result) } }
        }
    }
    fun read(done: (JSONObject?) -> Unit) {
        val current = session ?: run { done(null); return }
        val token = generation
        worker.execute {
            val result = request("GET", "/v1/companion/${current.optString("id")}", owner = current.optString("owner"))
            main.post { if (token == generation) done(result) }
        }
    }
    fun end() {
        generation++
        val old = session; session = null; routeKey = ""
        if (old != null && !worker.isShutdown) worker.execute {
            request("DELETE", "/v1/companion/${old.optString("id")}", owner = old.optString("owner"))
        }
    }
    fun close() { end(); worker.shutdown() }
    private fun request(method: String, path: String, body: JSONObject? = null, owner: String? = null): JSONObject? = try {
        val conn = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.requestMethod = method
            conn.connectTimeout = 5_000; conn.readTimeout = 5_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (owner != null) conn.setRequestProperty("Authorization", "Bearer $owner")
            if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 32_768) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 32_769 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size > 32_768) null else JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
