package com.connor.pendant.net

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ChronicleSnapshotCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var cachedAt: Long = 0L
        private set

    fun load(): ChronicleSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val root = JSONObject(raw)
            cachedAt = root.optLong(KEY_SAVED_AT, 0L)
            ChronicleSnapshot(
                status = root.optJSONObject("status") ?: JSONObject().put("counts", JSONObject()),
                memories = root.optJSONObject("memories") ?: JSONObject().put("count", 0).put("memories", JSONArray()),
                actionItems = root.optJSONObject("actionItems") ?: JSONObject().put("count", 0).put("items", JSONArray()),
                approvals = root.optJSONObject("approvals") ?: JSONObject().put("pending", JSONArray()).put("recent", JSONArray()),
                live = root.optJSONObject("live") ?: JSONObject().put("segments", JSONArray()),
                listening = root.optJSONObject("listening") ?: JSONObject().put("on", false),
                health = root.optJSONObject("health") ?: JSONObject().put("today", JSONObject()),
                translation = root.optJSONObject("translation") ?: defaultTranslation(),
                graph = root.optJSONObject("graph") ?: JSONObject().put("nodes", JSONArray()).put("edges", JSONArray()),
                insights = root.optJSONObject("insights") ?: JSONObject(),
                audio = root.optJSONObject("audio") ?: JSONObject().put("raw_bytes", 0).put("raw_chunks", 0).put("decoded_wavs", 0),
                audioHistory = root.optJSONObject("audioHistory") ?: JSONObject().put("days", JSONArray()),
                rawTranscripts = root.optJSONObject("rawTranscripts") ?: JSONObject().put("count", 0).put("items", JSONArray()),
                errors = root.optJSONObject("errors") ?: JSONObject(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load cached Chronicle snapshot: ${t.message}")
            null
        }
    }

    fun save(snapshot: ChronicleSnapshot) {
        val now = System.currentTimeMillis()
        val root = JSONObject()
            .put(KEY_SAVED_AT, now)
            .put("status", snapshot.status)
            .put("memories", snapshot.memories)
            .put("actionItems", snapshot.actionItems)
            .put("approvals", snapshot.approvals)
            .put("live", snapshot.live)
            .put("listening", snapshot.listening)
            .put("health", snapshot.health)
            .put("translation", snapshot.translation)
            .put("graph", snapshot.graph)
            .put("insights", snapshot.insights)
            .put("audio", snapshot.audio)
            .put("audioHistory", snapshot.audioHistory)
            .put("rawTranscripts", snapshot.rawTranscripts)
            .put("errors", snapshot.errors)
        prefs.edit()
            .putString(KEY_SNAPSHOT, root.toString())
            .putLong(KEY_SAVED_AT, now)
            .apply()
        cachedAt = now
    }

    fun savedAt(): Long = prefs.getLong(KEY_SAVED_AT, cachedAt)

    fun clear() {
        prefs.edit().clear().apply()
        cachedAt = 0L
    }

    private fun defaultTranslation(): JSONObject =
        JSONObject()
            .put("manual_override", "auto")
            .put("target_lang", "en")
            .put("engaged", false)

    companion object {
        private const val TAG = "ChronicleSnapshotCache"
        private const val PREFS_NAME = "chronicle_snapshot_cache"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
