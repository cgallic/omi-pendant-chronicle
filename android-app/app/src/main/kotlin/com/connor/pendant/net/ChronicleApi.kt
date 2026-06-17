package com.connor.pendant.net

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class ChronicleSnapshot(
    val status: JSONObject,
    val memories: JSONObject,
    val actionItems: JSONObject,
    val approvals: JSONObject,
    val live: JSONObject,
    val listening: JSONObject,
    val health: JSONObject,
    val translation: JSONObject,
    val graph: JSONObject = JSONObject(),
    val insights: JSONObject = JSONObject(),
    val audio: JSONObject = JSONObject(),
    val audioHistory: JSONObject = JSONObject(),
    val rawTranscripts: JSONObject = JSONObject(),
    val errors: JSONObject = JSONObject(),
)

class ChronicleApi(
    private val baseUrl: String,
    private val audioOutBaseUrl: String,
    private val secret: String,
) {

    suspend fun snapshot(): ChronicleSnapshot = supervisorScope {
        val errors = ConcurrentHashMap<String, String>()
        fun fallback(name: String, throwable: Throwable, value: JSONObject): JSONObject {
            errors[name] = throwable.message ?: throwable.javaClass.simpleName
            return value.put("_error", errors[name])
        }

        val status = async {
            runCatching { getJson("/pendant/api/v3/status", readTimeoutMs = 45_000) }
                .getOrElse { fallback("status", it, JSONObject().put("counts", JSONObject())) }
        }
        val memories = async {
            runCatching { getJson("/pendant/api/v3/memories?days_back=14&limit=50") }
                .getOrElse { fallback("memories", it, JSONObject().put("count", 0).put("memories", JSONArray())) }
        }
        val actionItems = async {
            runCatching { getJson("/pendant/api/v2/action-items?days_back=14&completed=false") }
                .getOrElse { fallback("actionItems", it, JSONObject().put("count", 0).put("items", JSONArray())) }
        }
        val approvals = async {
            runCatching { getJson("/pendant/api/v3/approvals") }
                .getOrElse { fallback("approvals", it, JSONObject().put("pending", JSONArray()).put("recent", JSONArray())) }
        }
        val live = async {
            runCatching { getJson("/pendant/api/v3/live") }
                .getOrElse { fallback("live", it, JSONObject().put("segments", JSONArray())) }
        }
        val listening = async {
            runCatching { getJson("/pendant/api/v3/listening") }
                .getOrElse { fallback("listening", it, JSONObject().put("on", false)) }
        }
        val health = async {
            runCatching { getJson("/pendant/api/v3/health") }
                .getOrElse { fallback("health", it, JSONObject().put("today", JSONObject())) }
        }
        val translation = async {
            runCatching { getAudioOutJson("GET", "/state", null) }
                .getOrElse { fallback("translation", it, defaultTranslationState()) }
        }
        val graph = async {
            runCatching { getJson("/pendant/api/v3/graph?days_back=14", readTimeoutMs = 12_000) }
                .getOrElse { fallback("graph", it, JSONObject().put("nodes", JSONArray()).put("edges", JSONArray())) }
        }
        val insights = async {
            runCatching { getJson("/pendant/api/v3/insights", readTimeoutMs = 8_000) }
                .getOrElse { fallback("insights", it, JSONObject()) }
        }
        val audio = async {
            runCatching { getJson("/pendant/api/v3/audio", readTimeoutMs = 20_000) }
                .getOrElse { fallback("audio", it, JSONObject().put("raw_bytes", 0).put("raw_chunks", 0).put("decoded_wavs", 0)) }
        }
        val audioHistory = async {
            val days = JSONArray()
            recentUtcDates(daysBack = 1).forEach { date ->
                val day = runCatching {
                    getJson("/pendant/api/v3/audio?date=$date", readTimeoutMs = 18_000)
                }.getOrElse {
                    fallback("audio:$date", it, JSONObject()
                        .put("date", date)
                        .put("raw_bytes", 0)
                        .put("raw_chunks", 0)
                        .put("decoded_wavs", 0))
                }
                if (!day.has("date")) day.put("date", date)
                days.put(day)
            }
            JSONObject().put("days", days)
        }
        val rawTranscripts = async {
            runCatching { getJson("/pendant/api/v3/raw?limit=12", readTimeoutMs = 18_000) }
                .getOrElse { fallback("rawTranscripts", it, JSONObject().put("count", 0).put("items", JSONArray())) }
        }

        ChronicleSnapshot(
            status = status.await(),
            memories = memories.await(),
            actionItems = actionItems.await(),
            approvals = approvals.await(),
            live = live.await(),
            listening = listening.await(),
            health = health.await(),
            translation = translation.await(),
            graph = graph.await(),
            insights = insights.await(),
            audio = audio.await(),
            audioHistory = audioHistory.await(),
            rawTranscripts = rawTranscripts.await(),
            errors = JSONObject(errors.toMap()),
        )
    }

    suspend fun cockpitSnapshot(): ChronicleSnapshot = supervisorScope {
        val errors = ConcurrentHashMap<String, String>()
        fun fallback(name: String, throwable: Throwable, value: JSONObject): JSONObject {
            errors[name] = throwable.message ?: throwable.javaClass.simpleName
            return value.put("_error", errors[name])
        }

        fun deferred(value: JSONObject): JSONObject =
            value.put("_error", "Full refresh pending")

        val status = async {
            runCatching { getJson("/pendant/api/v3/status", readTimeoutMs = 4_000) }
                .getOrElse { fallback("status", it, JSONObject().put("counts", JSONObject())) }
        }
        val memories = async {
            runCatching { getJson("/pendant/api/v3/memories?days_back=14&limit=50", readTimeoutMs = 8_000) }
                .getOrElse { fallback("memories", it, JSONObject().put("count", 0).put("memories", JSONArray())) }
        }
        val actionItems = async {
            runCatching { getJson("/pendant/api/v2/action-items?days_back=14&completed=false", readTimeoutMs = 8_000) }
                .getOrElse { fallback("actionItems", it, JSONObject().put("count", 0).put("items", JSONArray())) }
        }
        val approvals = async {
            runCatching { getJson("/pendant/api/v3/approvals", readTimeoutMs = 6_000) }
                .getOrElse { fallback("approvals", it, JSONObject().put("pending", JSONArray()).put("recent", JSONArray())) }
        }
        val live = async {
            runCatching { getJson("/pendant/api/v3/live", readTimeoutMs = 5_000) }
                .getOrElse { fallback("live", it, JSONObject().put("segments", JSONArray())) }
        }
        val listening = async {
            runCatching { getJson("/pendant/api/v3/listening", readTimeoutMs = 4_000) }
                .getOrElse { fallback("listening", it, JSONObject().put("on", false)) }
        }
        val translation = async {
            runCatching { getAudioOutJson("GET", "/state", null) }
                .getOrElse { fallback("translation", it, defaultTranslationState()) }
        }

        ChronicleSnapshot(
            status = status.await(),
            memories = memories.await(),
            actionItems = actionItems.await(),
            approvals = approvals.await(),
            live = live.await(),
            listening = listening.await(),
            health = deferred(JSONObject().put("today", JSONObject())),
            translation = translation.await(),
            graph = deferred(JSONObject().put("nodes", JSONArray()).put("edges", JSONArray())),
            insights = deferred(JSONObject()),
            audio = deferred(JSONObject().put("raw_bytes", 0).put("raw_chunks", 0).put("decoded_wavs", 0)),
            audioHistory = deferred(JSONObject().put("days", JSONArray())),
            rawTranscripts = deferred(JSONObject().put("count", 0).put("items", JSONArray())),
            errors = JSONObject(errors.toMap()),
        )
    }

    suspend fun conversation(id: String): JSONObject =
        getJson("/pendant/api/v3/conversation/$id")

    suspend fun actionItems(completed: Boolean = false, daysBack: Int = 14): JSONObject =
        getJson("/pendant/api/v2/action-items?days_back=$daysBack&completed=$completed")

    suspend fun approvals(): JSONObject =
        getJson("/pendant/api/v3/approvals")

    suspend fun liveState(): Pair<JSONObject, JSONObject> = supervisorScope {
        val live = async {
            runCatching { getJson("/pendant/api/v3/live", readTimeoutMs = 5_000) }
                .getOrElse { JSONObject().put("segments", JSONArray()).put("_error", it.message ?: "Live refresh failed") }
        }
        val listening = async {
            runCatching { getJson("/pendant/api/v3/listening", readTimeoutMs = 4_000) }
                .getOrElse { JSONObject().put("on", false).put("_error", it.message ?: "Live state refresh failed") }
        }
        live.await() to listening.await()
    }

    suspend fun toggleAction(convId: String, index: Int): JSONObject =
        postJson("/pendant/api/v2/action-items/$convId/$index/toggle")

    suspend fun setListening(on: Boolean): JSONObject =
        postJson(
            "/pendant/api/v3/listening",
            JSONObject().put("on", on),
            connectTimeoutMs = 3_000,
            readTimeoutMs = 4_000,
        )

    suspend fun decideApproval(id: String, approve: Boolean): JSONObject =
        postJson("/pendant/api/v3/approvals/$id/${if (approve) "approve" else "reject"}")

    suspend fun chat(message: String, context: JSONObject? = null): JSONObject {
        val body = JSONObject()
            .put("query", message)
            .put("message", message)
        if (context != null) body.put("context", context)
        return postJson("/pendant/api/v3/chat", body)
    }

    suspend fun setTranslationMode(mode: String, targetLang: String): JSONObject =
        getAudioOutJson(
            "POST",
            "/state",
            JSONObject()
                .put("manual_override", mode)
                .put("target_lang", targetLang.ifBlank { "en" }),
        )

    private suspend fun getJson(
        path: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 25_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        requestJson("GET", path, null, connectTimeoutMs, readTimeoutMs)
    }

    private suspend fun postJson(
        path: String,
        body: JSONObject? = null,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 25_000,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            requestJson("POST", path, body, connectTimeoutMs, readTimeoutMs)
        }

    private fun requestJson(
        method: String,
        path: String,
        body: JSONObject?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): JSONObject {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        if (body != null) {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("$path HTTP $code ${text.take(160)}")
        }
        if (text.isBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
    }

    private suspend fun getAudioOutJson(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
        val conn = (URL(audioOutBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Pendant-Secret", secret)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        if (body != null) {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("$path HTTP $code ${text.take(160)}")
        }
        if (text.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        }
    }

    private fun defaultTranslationState(): JSONObject =
        JSONObject()
            .put("manual_override", "auto")
            .put("target_lang", "en")
            .put("engaged", false)

    private fun recentUtcDates(daysBack: Int = 3): List<String> {
        val today = LocalDate.now(ZoneOffset.UTC)
        return (0..daysBack).map { today.minusDays(it.toLong()).toString() }
    }
}
