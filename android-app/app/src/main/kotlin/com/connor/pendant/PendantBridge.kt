package com.connor.pendant

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import com.connor.pendant.service.PendantForegroundService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * JS bridge injected into the Chronicle WebView as `window.Pendant`.
 *
 * v3's /pendant/v3/status page checks `typeof window.Pendant.status === 'function'`
 * and renders the phone-side block when present. Returns a JSON string so the page
 * can pretty-print without worrying about marshaling.
 *
 * The Settings drawer's "Debug (Android)" row fires [openDebug] to launch the
 * native TextView surface ([DebugActivity]).
 */
class PendantBridge(private val context: Context) {

    @JavascriptInterface
    fun status(): String {
        val svc = PendantForegroundService.instance
        val ble = svc?.bleClient
        val up = svc?.agentUploader
        val store = svc?.audioStore
        val obj = JSONObject()

        obj.put("live_mode", svc?.isLiveMode ?: false)
        if (svc != null) {
            val autoSyncObj = JSONObject()
            autoSyncObj.put("last_requested_at", svc.lastAutoSyncRequestedAt)
            autoSyncObj.put("next_at", svc.nextAutoSyncAt)
            autoSyncObj.put("last_skip_reason", svc.lastAutoSyncSkipReason ?: JSONObject.NULL)
            obj.put("auto_sync", autoSyncObj)
        }
        obj.put("ble_state", ble?.state?.value?.name ?: "—")
        obj.put("device", ble?.deviceAddr ?: "(none)")
        obj.put("rssi", ble?.lastRssi ?: JSONObject.NULL)
        obj.put("battery", ble?.batteryPct?.value ?: JSONObject.NULL)
        obj.put("codec", ble?.codec?.value?.name ?: "—")
        obj.put("mtu", ble?.mtu ?: 0)
        obj.put("frames", ble?.frameCount() ?: 0)
        obj.put("last_frame_size", ble?.lastFrameSize ?: 0)
        val sync = ble?.storageSync?.value
        if (sync != null) {
            val syncObj = JSONObject()
            syncObj.put("active", sync.active)
            syncObj.put("files_total", sync.filesTotal)
            syncObj.put("files_done", sync.filesDone)
            syncObj.put("bytes_done", sync.bytesDone)
            syncObj.put("bytes_total", sync.bytesTotal)
            syncObj.put("frames_done", sync.framesDone)
            syncObj.put("last_completed_at", sync.lastCompletedAt)
            syncObj.put("last_error", sync.lastError ?: JSONObject.NULL)
            obj.put("sync", syncObj)
        }
        val stats = ble?.storageStats?.value
        if (stats != null) {
            val statsObj = JSONObject()
            statsObj.put("active", stats.active)
            statsObj.put("proof_active", stats.proofActive)
            statsObj.put("used_bytes", stats.usedBytes)
            statsObj.put("file_count", stats.fileCount)
            statsObj.put("free_bytes", stats.freeBytes ?: JSONObject.NULL)
            statsObj.put("status_flags", stats.statusFlags)
            statsObj.put("delta_bytes", stats.deltaBytes ?: JSONObject.NULL)
            statsObj.put("delta_files", stats.deltaFiles ?: JSONObject.NULL)
            statsObj.put("last_read_at", stats.lastReadAt)
            statsObj.put("message", stats.message ?: JSONObject.NULL)
            statsObj.put("last_error", stats.lastError ?: JSONObject.NULL)
            obj.put("storage_stats", statsObj)
        }

        obj.put("posted", up?.posted() ?: 0)
        obj.put("failed", up?.failed() ?: 0)
        obj.put("dropped", up?.dropped() ?: 0)
        obj.put("batches", up?.batches() ?: 0)
        obj.put("last_batch_size", up?.lastBatchSize ?: 0)
        obj.put("last_http", up?.lastStatus ?: 0)
        obj.put("post_url", up?.targetUrl ?: "—")

        if (store != null) {
            // status() is suspend — we're called from JS thread, blocking is acceptable
            // (cheap DAO read, IO-bound).
            val s = runBlocking { store.status() }
            obj.put("stored_ms", s.storedMs)
            obj.put("stored_bytes", s.storedBytes)
            obj.put("queue", s.queueCount)
            obj.put("oldest_start", s.oldestStart ?: JSONObject.NULL)
            obj.put("newest_end", s.newestEnd ?: JSONObject.NULL)
            obj.put("current_frames", s.currentFrames)
            obj.put("current_bytes", s.currentBytes)
            obj.put("current_age_ms", s.currentSegmentAgeMs)
        } else {
            obj.put("stored_ms", JSONObject.NULL)
            obj.put("stored_bytes", JSONObject.NULL)
            obj.put("queue", 0)
        }
        return obj.toString()
    }

    /**
     * Open the native [DebugActivity] TextView surface. Called from the Chronicle
     * Settings drawer when the user taps "Debug (Android)".
     *
     * NEW_TASK flag is required because the bridge runs off the JS thread, not an
     * Activity context — without it Android refuses to start the activity.
     */
    @JavascriptInterface
    fun openDebug() {
        val intent = Intent(context, DebugActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun syncNow() {
        PendantForegroundService.syncNow(context)
    }

    @JavascriptInterface
    fun proveRecording() {
        PendantForegroundService.proveRecording(context)
    }

    @JavascriptInterface
    fun startLive() {
        PendantForegroundService.startLive(context)
    }

    @JavascriptInterface
    fun stopLive() {
        PendantForegroundService.stopLive(context)
    }
}
