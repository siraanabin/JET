package com.jet.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.jet.downloader.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DiagnosticRecorder(private val context: Context, private val preferences: PreferencesManager, private val client: okhttp3.OkHttpClient) {
    companion object {
        private const val BASE_URL = "https://jet-diagnostics.siraanabin.workers.dev"
        private const val MAX_EVENTS = 200
        private const val MAX_BATCH = 15
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EVENT_FIELDS = listOf(
            "event_type", "status", "initStep", "initError", "engine", "fallbackReason",
            "appVersion", "androidVersion", "deviceModel", "networkType",
            "speedMbps", "durationMs", "format", "resolution"
        )
    }

    private val queueFile = File(context.filesDir, "jet-diagnostic-queue.json")
    private val flushMutex = Mutex()
    private val apiKey get() = BuildConfig.INGEST_API_KEY.trim()
    val isConfigured get() = apiKey.isNotEmpty()

    private fun tomorrowUtc() = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()

    private suspend fun blockedToday(): Boolean {
        val until = runCatching { Instant.parse(preferences.getDiagnosticStopUntilUtc()) }.getOrNull() ?: return false
        return Instant.now().isBefore(until)
    }

    private fun readQueue() = runCatching {
        if (queueFile.isFile) JSONObject(queueFile.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private fun writeQueue(q: JSONObject) {
        val tmp = File(queueFile.parentFile, queueFile.name + ".tmp")
        tmp.writeText(q.toString())
        if (!tmp.renameTo(queueFile)) {
            queueFile.writeText(q.toString())
            tmp.delete()
        }
    }

    private fun parseStop(value: String): String? {
        if (value.isBlank()) return null
        try { return Instant.parse(value).toString() } catch (_: Exception) {}
        return try {
            LocalDate.parse(value).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
        } catch (_: Exception) { null }
    }

    private suspend fun request(path: String, payload: JSONObject?, method: String = "POST"): JSONObject {
        val key = apiKey
        if (key.isBlank()) throw IOException("Diagnostics are not configured")
        val id = preferences.getOrCreateDiagnosticInstallId()
        require(id.matches(Regex("[A-Za-z0-9._-]{16,128}")))
        val builder = Request.Builder()
            .url(BASE_URL + path)
            .header("Authorization", "Bearer $key")
            .header("X-Install-Id", id)
            .header("Accept", "application/json")
        if (method == "DELETE") builder.delete()
        else builder.post((payload ?: JSONObject()).toString().toRequestBody(JSON_TYPE))

        repeat(2) { attempt ->
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 400 || response.code == 401 || response.code == 429) {
                        val server = runCatching { JSONObject(body).optString("stopUntilUtc") }.getOrDefault("")
                        preferences.setDiagnosticStopUntilUtc(
                            if (response.code == 429) parseStop(server) ?: tomorrowUtc() else tomorrowUtc()
                        )
                        throw UploadBlockedException()
                    }
                    if (!response.isSuccessful) throw IOException("Diagnostic HTTP ${response.code}")
                    return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                }
            } catch (blocked: UploadBlockedException) {
                throw blocked
            } catch (error: IOException) {
                if (attempt == 1) throw error
                delay(500L)
            }
        }
        throw IOException("Diagnostic request failed")
    }

    private fun networkType(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val network = manager.activeNetwork ?: return "offline"
        val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun safeCode(value: String, max: Int = 80): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(max).ifBlank { "unknown" }

    private fun newEvent(
        status: String,
        engine: String,
        initStep: String = "unknown",
        initError: String = "none",
        fallbackReason: String = "none",
        speedMbps: Double = 0.0,
        durationMs: Long = 0,
        format: String = "unknown",
        resolution: String = "unknown"
    ) = JSONObject().apply {
        put("event_type", status)
        put("status", status)
        put("initStep", safeCode(initStep, 64))
        put("initError", safeCode(initError, 64))
        put("engine", safeCode(engine, 48))
        val safeFallback = fallbackReason.substringBefore(':').substringBefore('/').substringBefore('?').substringBefore(' ')
        put("fallbackReason", safeCode(safeFallback, 80))
        put("appVersion", BuildConfig.VERSION_NAME.take(32))
        put("androidVersion", Build.VERSION.RELEASE.orEmpty().take(32))
        put("deviceModel", (Build.MANUFACTURER + " " + Build.MODEL).take(80))
        put("networkType", networkType())
        put("speedMbps", speedMbps.coerceAtLeast(0.0))
        put("durationMs", durationMs.coerceAtLeast(0))
        put("format", safeCode(format, 20))
        put("resolution", safeCode(resolution, 20))
    }

    suspend fun settingsJson() = JSONObject().apply {
        put("diagnosticsEnabled", preferences.isDiagnosticsEnabled())
        put("topLinkCountingEnabled", false)
        put("apiKeyConfigured", isConfigured)
    }.toString()

    // Kept compatible with the existing JavaScript bridge; top-link collection is always disabled.
    suspend fun setPreference(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (name == "diagnostics") preferences.setDiagnosticPreference("diagnostics", enabled && isConfigured)
        else if (name == "topLink") preferences.setDiagnosticPreference("topLink", false)
        else throw IllegalArgumentException("Unknown diagnostic preference")
        if (name != "diagnostics" || !enabled || !isConfigured) {
            val q = readQueue()
            if (name == "diagnostics") {
                q.put("events", JSONArray())
                q.put("recent", JSONArray())
            }
            q.put("topLinks", JSONArray())
            writeQueue(q)
        }
    }

    suspend fun recentLogsJson() = withContext(Dispatchers.IO) {
        if (!isConfigured || !preferences.isDiagnosticsEnabled()) return@withContext "[]"
        val a = readQueue().optJSONArray("recent") ?: JSONArray()
        JSONArray().also { out ->
            for (i in (a.length() - 20).coerceAtLeast(0) until a.length()) out.put(a.getJSONObject(i))
        }.toString()
    }

    private suspend fun enqueue(event: JSONObject) = withContext(Dispatchers.IO) {
        if (!isConfigured || !preferences.isDiagnosticsEnabled()) return@withContext
        val q = readQueue()
        val events = q.optJSONArray("events") ?: JSONArray()
        events.put(event)
        while (events.length() > MAX_EVENTS) events.remove(0)
        q.put("events", events)
        val recent = q.optJSONArray("recent") ?: JSONArray()
        recent.put(event)
        while (recent.length() > 20) recent.remove(0)
        q.put("recent", recent)
        writeQueue(q)
    }

    suspend fun recordFallback(reason: String) = recordFallback(reason, "newpipe")
    suspend fun recordFallback(reason: String, engine: String) {
        enqueue(newEvent("fallback", engine, fallbackReason = reason))
    }

    suspend fun recordFailure() = recordFailure("unknown", "unknown", "download_failed")
    suspend fun recordFailure(engine: String, initStep: String, initError: String) {
        enqueue(newEvent("failure", engine, initStep, initError))
    }

    suspend fun recordInitFailure(engine: String, initStep: String, initError: String) {
        enqueue(newEvent("init_failure", engine, initStep, initError))
    }

    // Legacy overload retained until the app's observer call is updated.
    suspend fun recordSuccess(url: String, format: String, resolution: String, size: Long, duration: Long) {
        recordSuccess("download", format, resolution, 0.0, duration, "unknown", "none", "none")
    }

    suspend fun recordSuccess(
        engine: String,
        format: String,
        resolution: String,
        speedMbps: Double,
        duration: Long,
        initStep: String,
        initError: String,
        fallbackReason: String
    ) {
        enqueue(newEvent("success", engine, initStep, initError, fallbackReason, speedMbps, duration, format, resolution))
    }

    suspend fun flushPending() = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isConfigured || !preferences.isDiagnosticsEnabled() || blockedToday()) return@withContext
            val q = readQueue()
            val events = q.optJSONArray("events") ?: JSONArray()
            while (events.length() > 0 && !blockedToday()) {
                val selected = (0 until events.length()).map { it to events.getJSONObject(it) }
                    .sortedWith(
                        compareBy<Pair<Int, JSONObject>> {
                            when (it.second.optString("status")) {
                                "failure", "init_failure" -> 0
                                "fallback" -> 1
                                else -> 2
                            }
                        }.thenBy { it.first }
                    ).take(MAX_BATCH)
                val batch = JSONArray()
                selected.forEach { (_, source) ->
                    val clean = JSONObject()
                    EVENT_FIELDS.forEach { field -> if (source.has(field)) clean.put(field, source.get(field)) }
                    batch.put(clean)
                }
                try {
                    request("/v1/ingest", JSONObject().put("events", batch))
                    selected.map { it.first }.sortedDescending().forEach { events.remove(it) }
                    q.put("events", events)
                    writeQueue(q)
                } catch (_: UploadBlockedException) {
                    return@withContext
                } catch (_: IOException) {
                    return@withContext
                }
            }
        }
    }

    suspend fun submitReport(category: String, description: String, includeLogs: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured || blockedToday()) return@withContext false
        require(category in setOf("bug", "crash", "performance", "other"))
        require(description.length <= 500)
        val logs = JSONArray()
        if (includeLogs && preferences.isDiagnosticsEnabled()) {
            val recent = readQueue().optJSONArray("recent") ?: JSONArray()
            for (i in (recent.length() - 20).coerceAtLeast(0) until recent.length()) {
                val src = recent.getJSONObject(i)
                val clean = JSONObject()
                EVENT_FIELDS.forEach { field -> if (src.has(field)) clean.put(field, src.get(field)) }
                logs.put(clean)
            }
        }
        try {
            request(
                "/v1/bug-report",
                JSONObject().put("category", category)
                    .put("description", description)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("logs", logs)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteMyData(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured || blockedToday()) return@withContext false
        try {
            request("/v1/me/data", null, "DELETE")
            queueFile.delete()
            preferences.resetDiagnosticInstallId()
            true
        } catch (_: Exception) {
            false
        }
    }

    private class UploadBlockedException : IOException("Diagnostic upload paused for UTC day")
}
