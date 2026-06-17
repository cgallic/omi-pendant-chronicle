package com.connor.pendant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.connor.pendant.BuildConfig
import com.connor.pendant.MainActivity
import com.connor.pendant.audio.AudioOutClient
import com.connor.pendant.audio.AudioOutPlayer
import com.connor.pendant.ble.OmiBleClient
import com.connor.pendant.context.ContextCollector
import com.connor.pendant.context.HeartbeatPoster
import com.connor.pendant.health.HealthConnectBackfill
import com.connor.pendant.health.HealthConnectReader
import com.connor.pendant.net.AgentUploader
import com.connor.pendant.net.ChronicleApi
import java.net.URL
import java.net.URI
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.connor.pendant.store.AudioStore
import com.connor.pendant.work.RetentionWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Single owner of the phone-side pendant work.
 *
 * Battery mode keeps BLE disconnected while idle. A manual sync holds a
 * PARTIAL_WAKE_LOCK until the pendant storage sync reports done/failed, drains the
 * pendant storage service, posts frames to the agent, and lets the BLE link
 * disconnect again.
 */
@UnstableApi
class PendantForegroundService : LifecycleService() {

    private val tag = "PendantFgService"
    private val channelId = "pendant_fg"
    private val notifId = 1
    private val storageRecoveryMode = false

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var ble: OmiBleClient
    private lateinit var uploader: AgentUploader
    private lateinit var store: AudioStore
    private lateinit var api: ChronicleApi
    private var cloudPollJob: Job? = null
    private lateinit var contextCollector: ContextCollector
    private lateinit var heartbeat: HeartbeatPoster
    private lateinit var healthReader: HealthConnectReader
    private var audioOutClient: AudioOutClient? = null
    private var audioOutPlayer: AudioOutPlayer? = null
    @Volatile private var liveMode = false
    @Volatile private var buttonWatchArmed = false
    @Volatile var lastButtonAction: String? = null
        private set
    @Volatile var lastButtonActionAt: Long = 0L
        private set
    private var buttonArmTimeoutJob: Job? = null
    private var pendingAudioReplayJob: Job? = null
    @Volatile private var lastAutoStorageSyncRequestedAt: Long = 0L
    @Volatile private var nextAutoStorageSyncAt: Long = 0L
    @Volatile private var lastAutoStorageSyncSkipReason: String? = null
    private val inFlightSegmentUploads = mutableSetOf<Long>()
    private val unreplayableSegmentIds = mutableSetOf<Long>()
    private var storageSyncHasRun = false
    private var recordingProofHasRun = false

    val bleClient: OmiBleClient? get() = if (::ble.isInitialized) ble else null
    val agentUploader: AgentUploader? get() = if (::uploader.isInitialized) uploader else null
    val audioStore: AudioStore? get() = if (::store.isInitialized) store else null
    val heartbeatPoster: HeartbeatPoster? get() = if (::heartbeat.isInitialized) heartbeat else null
    val isLiveMode: Boolean get() = liveMode
    val lastAutoSyncRequestedAt: Long get() = lastAutoStorageSyncRequestedAt
    val nextAutoSyncAt: Long get() = nextAutoStorageSyncAt
    val lastAutoSyncSkipReason: String? get() = lastAutoStorageSyncSkipReason

    override fun onCreate() {
        super.onCreate()
        instance = this

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pendant::StorageSync").apply {
            setReferenceCounted(false)
        }
        Log.i(tag, "Wake-lock initialized for storage sync windows")

        startInForeground(includeMediaPlayback = false)

        uploader = AgentUploader(BuildConfig.AGENT_URL, BuildConfig.PENDANT_SECRET)
        val apiBase = run {
            val raw = URL(BuildConfig.AGENT_URL)
            "${raw.protocol}://${raw.host}:8772"
        }
        val audioOutHttpBase = run {
            val raw = URI(BuildConfig.AUDIO_OUT_WS_URL)
            val scheme = if (raw.scheme == "wss") "https" else "http"
            val port = if (raw.port > 0) ":${raw.port}" else ""
            "$scheme://${raw.host}$port"
        }
        api = ChronicleApi(apiBase, audioOutHttpBase, BuildConfig.PENDANT_SECRET)
        store = AudioStore(this)
        ble = OmiBleClient(this)
        ble.setStorageFileSafetyCheckpoint { payload ->
            val result = store.persistStorageFile(
                index = payload.index,
                timestamp = payload.timestamp,
                expectedSize = payload.size,
                frames = payload.frames,
            ) ?: return@setStorageFileSafetyCheckpoint false
            val (segmentId, startedAtMs) = result
            uploadStorageSegmentAsync(segmentId, payload.frames, startedAtMs, reason = "fresh storage sync")
            true
        }

        // Storage frames are uploaded from the durable phone-local file path, not from this event stream.
        ble.frameEvents
            .onEach { frame ->
                if (frame.source == OmiBleClient.FrameSource.LIVE) {
                    store.acceptFrame(frame.bytes)
                    uploader.postFrame(frame.bytes)
                }
            }
            .launchIn(lifecycleScope)
        ble.buttonEvents
            .onEach { event -> handleButtonEvent(event) }
            .launchIn(lifecycleScope)
        ble.state
            .onEach { state -> handleBleStateChange(state) }
            .launchIn(lifecycleScope)
        ble.storageSync
            .onEach { sync ->
                if (sync.active) {
                    storageSyncHasRun = true
                }
                if (!sync.active && !liveMode && wakeLock.isHeld) {
                    wakeLock.release()
                    Log.i(tag, "Wake-lock released after storage sync")
                }
                if (!sync.active && storageSyncHasRun) {
                    requestPendingAudioReplay("storage sync complete")
                    if (sync.lastError == null && !liveMode && !ble.storageStats.value.active && !ble.storageStats.value.proofActive) {
                        Log.i(tag, "Storage sync complete — arming button watch")
                        armButton()
                    }
                }
            }
            .launchIn(lifecycleScope)
        ble.storageStats
            .onEach { stats ->
                if (stats.active || stats.proofActive) {
                    recordingProofHasRun = true
                }
                if (!stats.active && !stats.proofActive && !ble.storageSync.value.active && !liveMode && wakeLock.isHeld) {
                    wakeLock.release()
                    Log.i(tag, "Wake-lock released after recording proof")
                }
                if (recordingProofHasRun && !stats.active && !stats.proofActive && !ble.storageSync.value.active && !liveMode) {
                    Log.i(tag, "Recording proof complete or idle — arming button watch")
                    armButton()
                }
            }
            .launchIn(lifecycleScope)

        // Periodic retention sweep (every 6h, deletes audio older than 3 days).
        RetentionWorker.schedule(this)
        requestPendingAudioReplay("service start")
        startAutomaticStorageSyncLoop()

        // Poll battery every 5 min. refreshBattery() no-ops if GATT isn't ready,
        // so this is safe to fire regardless of connection state.
        lifecycleScope.launch {
            while (true) {
                delay(BATTERY_POLL_MS)
                ble.refreshBattery()
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(PENDING_AUDIO_REPLAY_MS)
                requestPendingAudioReplay("periodic retry")
            }
        }

        // Context heartbeats: GPS + steps + foreground app every 5 min, plus Health Connect
        // biometrics (HR / steps / sleep / distance / calories / SpO2) merged under "health".
        contextCollector = ContextCollector(this)
        contextCollector.start()
        heartbeat = HeartbeatPoster(BuildConfig.AGENT_URL, BuildConfig.PENDANT_SECRET)
        healthReader = HealthConnectReader(applicationContext)
        lifecycleScope.launch {
            // Initial delay so we don't hammer the agent at boot before perms are granted.
            delay(15_000L)
            while (true) {
                try {
                    val snap = contextCollector.snapshot()
                    val health = healthReader.snapshot()
                    if (health.length() > 0) snap.put("health", health)
                    addPendantHealth(snap)
                    heartbeat.postSnapshot(snap.toString())
                } catch (t: Throwable) {
                    Log.w(tag, "heartbeat loop error: ${t.message}")
                }
                delay(HEARTBEAT_MS)
            }
        }

        startCloudListeningPollLoop()

        Log.i(tag, "Battery mode active: BLE waits for automatic hourly or explicit sync")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_RUN_BACKFILL) {
            val days = intent.getIntExtra(EXTRA_DAYS, 30).coerceIn(1, 365)
            Log.i(tag, "ACTION_RUN_BACKFILL days=$days — dispatching")
            lifecycleScope.launch {
                try {
                    val stats = HealthConnectBackfill(
                        context = applicationContext,
                        agentUrl = BuildConfig.AGENT_URL,
                        secret = BuildConfig.PENDANT_SECRET,
                        daysBack = days,
                    ).run()
                    Log.i(tag, "backfill complete: $stats")
                } catch (t: Throwable) {
                    Log.e(tag, "backfill failed: ${t.message}", t)
                }
            }
        }
        if (intent?.action == ACTION_SYNC_NOW) {
            Log.i(tag, "ACTION_SYNC_NOW received")
            lifecycleScope.launch {
                delay(1000L)
                syncNow()
            }
        }
        if (intent?.action == ACTION_PROVE_RECORDING) {
            proveRecording()
        }
        if (intent?.action == ACTION_START_LIVE) {
            startLive()
        }
        if (intent?.action == ACTION_STOP_LIVE) {
            stopLive()
        }
        if (intent?.action == ACTION_RECONNECT_PENDANT) {
            reconnectPendant()
        }
        if (intent?.action == ACTION_RECONNECT_AUDIO) {
            reconnectAudio()
        }
        if (intent?.action == ACTION_ARM_BUTTON) {
            armButton()
        }
        if (intent?.action == ACTION_SET_MIC_GAIN_HIGH) {
            setMicGainHigh()
        }
        // START_STICKY so the OS resurrects us if it kills the service.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "Service destroying")
        buttonWatchArmed = false
        buttonArmTimeoutJob?.cancel()
        buttonArmTimeoutJob = null
        cloudPollJob?.cancel()
        cloudPollJob = null
        stopAudioOut()
        ble.disconnect()
        if (::contextCollector.isInitialized) contextCollector.stop()
        if (::store.isInitialized) store.close()
        if (wakeLock.isHeld) wakeLock.release()
        nextAutoStorageSyncAt = 0L
        instance = null
        super.onDestroy()
    }

    private fun uploadStorageSegmentAsync(segmentId: Long, frames: List<ByteArray>, startedAtMs: Long? = null, reason: String) {
        if (!beginSegmentUpload(segmentId)) return
        lifecycleScope.launch {
            try {
                val ok = uploader.postFramesNow(frames, startedAtMs)
                if (ok) {
                    store.markPosted(segmentId)
                    Log.i(tag, "Phone audio upload ack id=$segmentId frames=${frames.size} reason=$reason")
                } else {
                    Log.w(tag, "Phone audio upload pending id=$segmentId frames=${frames.size} reason=$reason error=${uploader.lastError ?: "HTTP ${uploader.lastStatus}"}")
                }
            } finally {
                finishSegmentUpload(segmentId)
            }
        }
    }

    private fun requestPendingAudioReplay(reason: String) {
        if (!::store.isInitialized || !::uploader.isInitialized) return
        if (pendingAudioReplayJob?.isActive == true) return
        pendingAudioReplayJob = lifecycleScope.launch {
            replayPendingAudio(reason)
        }
    }

    private suspend fun replayPendingAudio(reason: String) {
        val pending = store.pendingSegments(PENDING_AUDIO_REPLAY_LIMIT)
            .filter { !isUnreplayableSegment(it.id) }
        if (pending.isEmpty()) return

        Log.i(tag, "Phone audio replay starting reason=$reason segments=${pending.size}")
        for (seg in pending) {
            if (!beginSegmentUpload(seg.id)) continue
            try {
                val frames = store.readSegmentFrames(seg)
                if (frames.isNullOrEmpty()) {
                    markUnreplayableSegment(seg.id)
                    continue
                }

                val ok = uploader.postFramesNow(frames, seg.started_at)
                if (ok) {
                    store.markPosted(seg.id)
                    Log.i(tag, "Phone audio replay ack id=${seg.id} frames=${frames.size} bytes=${seg.byte_count}")
                } else {
                    Log.w(tag, "Phone audio replay still pending id=${seg.id} frames=${frames.size} error=${uploader.lastError ?: "HTTP ${uploader.lastStatus}"}")
                    break
                }
            } finally {
                finishSegmentUpload(seg.id)
            }
        }
    }

    private fun beginSegmentUpload(segmentId: Long): Boolean =
        synchronized(inFlightSegmentUploads) {
            inFlightSegmentUploads.add(segmentId)
        }

    private fun finishSegmentUpload(segmentId: Long) {
        synchronized(inFlightSegmentUploads) {
            inFlightSegmentUploads.remove(segmentId)
        }
    }

    private fun isUnreplayableSegment(segmentId: Long): Boolean =
        synchronized(unreplayableSegmentIds) {
            segmentId in unreplayableSegmentIds
        }

    private fun markUnreplayableSegment(segmentId: Long) {
        synchronized(unreplayableSegmentIds) {
            unreplayableSegmentIds += segmentId
        }
    }

    private fun startAutomaticStorageSyncLoop() {
        lifecycleScope.launch {
            var delayMs = AUTO_STORAGE_SYNC_INITIAL_DELAY_MS
            while (true) {
                nextAutoStorageSyncAt = System.currentTimeMillis() + delayMs
                delay(delayMs)
                val requested = requestAutomaticStorageSyncIfIdle()
                delayMs = if (requested) {
                    if ((ble.settingsTelemetry.value?.sdBacklog ?: 0L) > 0L) {
                        AUTO_STORAGE_SYNC_BACKLOG_INTERVAL_MS
                    } else {
                        AUTO_STORAGE_SYNC_INTERVAL_MS
                    }
                } else {
                    AUTO_STORAGE_SYNC_RETRY_MS
                }
            }
        }
    }

    private fun startCloudListeningPollLoop() {
        if (cloudPollJob?.isActive == true) return
        cloudPollJob = lifecycleScope.launch {
            while (true) {
                try {
                    val (_, listening) = api.liveState()
                    val on = listening.optBoolean("on", false)
                    if (on && !liveMode) {
                        Log.i(tag, "Cloud listening flag set to ON — starting live capture")
                        startLive()
                    } else if (!on && liveMode) {
                        Log.i(tag, "Cloud listening flag set to OFF — stopping live capture")
                        stopLive()
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "Failed to poll cloud listening state: ${t.message}")
                }
                delay(10000L)
            }
        }
    }

    private fun requestAutomaticStorageSyncIfIdle(): Boolean {
        if (!::ble.isInitialized) return skipAutomaticStorageSync("BLE not initialized")
        val sync = ble.storageSync.value
        val stats = ble.storageStats.value
        return when {
            liveMode -> skipAutomaticStorageSync("live capture active")
            sync.active -> skipAutomaticStorageSync("storage sync already active")
            stats.active || stats.proofActive -> skipAutomaticStorageSync("recording proof active")
            wakeLock.isHeld -> skipAutomaticStorageSync("wake lock held by another task")
            else -> {
                lastAutoStorageSyncRequestedAt = System.currentTimeMillis()
                lastAutoStorageSyncSkipReason = null
                requestStorageSync("Automatic hourly")
                true
            }
        }
    }

    private fun skipAutomaticStorageSync(reason: String): Boolean {
        lastAutoStorageSyncSkipReason = reason
        Log.i(tag, "Automatic hourly storage sync skipped: $reason")
        return false
    }

    private fun addPendantHealth(snapshot: JSONObject) {
        if (!::ble.isInitialized) return
        val sync = ble.storageSync.value
        val stats = ble.storageStats.value
        val telemetry = ble.settingsTelemetry.value
        val pendant = JSONObject()
            .put("live_mode", liveMode)
            .put("button_watch_armed", buttonWatchArmed)
            .put("ble_state", ble.state.value.name)
            .put("ble_error", ble.lastError ?: JSONObject.NULL)
            .put("mtu", ble.mtu)
            .put("device_addr", ble.deviceAddr ?: JSONObject.NULL)
            .put("battery_pct", ble.batteryPct.value ?: JSONObject.NULL)
            .put("last_battery_read_at", ble.lastBatteryReadAt)
            .put("last_auto_sync_requested_at", lastAutoStorageSyncRequestedAt)
            .put("next_auto_sync_at", nextAutoStorageSyncAt)
            .put("last_auto_sync_skip_reason", lastAutoStorageSyncSkipReason ?: JSONObject.NULL)
            .put("sync", JSONObject()
                .put("active", sync.active)
                .put("files_done", sync.filesDone)
                .put("files_total", sync.filesTotal)
                .put("bytes_done", sync.bytesDone)
                .put("bytes_total", sync.bytesTotal)
                .put("frames_done", sync.framesDone)
                .put("cleanup_deleted", sync.cleanupDeleted)
                .put("cleanup_failed", sync.cleanupFailed)
                .put("last_completed_at", sync.lastCompletedAt)
                .put("last_error", sync.lastError ?: JSONObject.NULL))
            .put("storage_stats", JSONObject()
                .put("active", stats.active)
                .put("proof_active", stats.proofActive)
                .put("used_bytes", stats.usedBytes)
                .put("file_count", stats.fileCount)
                .put("last_read_at", stats.lastReadAt)
                .put("last_error", stats.lastError ?: JSONObject.NULL))
        if (telemetry != null) {
            pendant.put("telemetry", JSONObject()
                .put("battery_voltage", telemetry.batteryVoltage)
                .put("battery_percentage", telemetry.batteryPercentage)
                .put("charging_state", telemetry.chargingState)
                .put("sd_backlog", telemetry.sdBacklog)
                .put("sd_status", telemetry.sdStatus)
                .put("mic_status", telemetry.micStatus)
                .put("vad_status", telemetry.vadStatus)
                .put("codec_status", telemetry.codecStatus))
        }
        snapshot.put("pendant", pendant)
    }

    fun syncNow() {
        requestStorageSync("Manual")
    }

    private fun requestStorageSync(reason: String) {
        if (!::ble.isInitialized) return
        buttonWatchArmed = false
        buttonArmTimeoutJob?.cancel()
        buttonArmTimeoutJob = null
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        Log.i(tag, "$reason storage sync requested")
        ble.syncStorageNow()
    }

    fun proveRecording() {
        if (!::ble.isInitialized) return
        buttonWatchArmed = false
        buttonArmTimeoutJob?.cancel()
        buttonArmTimeoutJob = null
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        Log.i(tag, "Recording proof requested")
        ble.proveRecordingToStorage()
    }

    fun startLive() {
        if (!::ble.isInitialized) return
        liveMode = true
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        startAudioOut()
        startInForeground(includeMediaPlayback = true)
        Log.i(tag, "Live capture + voice requested")
        ble.startLiveStream()

        lifecycleScope.launch {
            try {
                api.setListening(true)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to update cloud listening state on startLive: ${t.message}")
            }
        }
    }

    fun stopLive() {
        liveMode = false
        if (::ble.isInitialized) ble.stopLiveStream()
        stopAudioOut()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        startInForeground(includeMediaPlayback = false)
        Log.i(tag, "Live capture + voice stopped")
        buttonWatchArmed = false
        buttonArmTimeoutJob?.cancel()
        buttonArmTimeoutJob = null

        armButton()

        lifecycleScope.launch {
            try {
                api.setListening(false)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to update cloud listening state on stopLive: ${t.message}")
            }
        }
    }

    fun reconnectPendant() {
        if (!::ble.isInitialized) return
        Log.i(tag, "Pendant reconnect requested")
        ble.disconnect()
        if (liveMode) {
            ble.startLiveStream()
        } else {
            beginTemporaryButtonWatch("Button link opening: double tap starts live capture, single tap syncs files")
        }
    }

    fun reconnectAudio() {
        if (!liveMode) {
            Log.i(tag, "Audio reconnect ignored outside live mode")
            return
        }
        Log.i(tag, "Audio-out reconnect requested")
        stopAudioOut()
        startAudioOut()
        startInForeground(includeMediaPlayback = true)
    }

    fun armButton() {
        if (!::ble.isInitialized) return
        if (storageRecoveryMode) {
            buttonWatchArmed = false
            Log.i(tag, "Button watch suppressed during storage recovery")
            return
        }
        beginTemporaryButtonWatch("Button link opening: double tap starts live capture, single tap syncs files")
    }

    fun setMicGainHigh() {
        if (!::ble.isInitialized) return
        Log.i(tag, "Mic gain max requested")
        ble.setMicGain(OmiBleClient.CAPTURE_MIC_GAIN_LEVEL)
    }

    private fun beginTemporaryButtonWatch(action: String) {
        buttonWatchArmed = true
        buttonArmTimeoutJob?.cancel()
        startInForeground(includeMediaPlayback = liveMode)
        noteButtonAction(action)
        ble.armButtonWatch()
        buttonArmTimeoutJob = lifecycleScope.launch {
            delay(BUTTON_ARM_WINDOW_MS)
            if (buttonWatchArmed && !liveMode && !ble.storageSync.value.active && !ble.storageStats.value.active && !ble.storageStats.value.proofActive) {
                buttonWatchArmed = false
                noteButtonAction("Button link closed: returning to offline storage")
                ble.disconnect()
                startInForeground(includeMediaPlayback = false)
            }
        }
    }

    private fun handleButtonEvent(status: OmiBleClient.ButtonStatus) {
        when (status.event) {
            OmiBleClient.ButtonEvent.SINGLE_TAP -> {
                noteButtonAction("Single tap: sync now")
                buttonWatchArmed = false
                buttonArmTimeoutJob?.cancel()
                buttonArmTimeoutJob = null
                syncAfterStoppingLiveIfNeeded()
            }
            OmiBleClient.ButtonEvent.DOUBLE_TAP -> {
                if (liveMode) {
                    noteButtonAction("Double tap: pause live capture")
                    stopLive()
                } else {
                    noteButtonAction("Double tap: start live capture")
                    buttonWatchArmed = false
                    buttonArmTimeoutJob?.cancel()
                    buttonArmTimeoutJob = null
                    startLive()
                }
            }
            OmiBleClient.ButtonEvent.TRIPLE_TAP -> {
                if (liveMode) {
                    noteButtonAction("Triple tap: stop live and act now")
                    stopLive()
                    ble.playHaptic(2) // Vibrate pendant (50ms pulse)
                    if (::uploader.isInitialized) {
                        lifecycleScope.launch {
                            uploader.forceProcess()
                        }
                    }
                } else {
                    noteButtonAction("Triple tap: ignored (not listening)")
                }
            }
            OmiBleClient.ButtonEvent.LONG_TAP -> {
                noteButtonAction("Long press: pendant firmware may power off")
                buttonWatchArmed = false
                buttonArmTimeoutJob?.cancel()
                buttonArmTimeoutJob = null
            }
            OmiBleClient.ButtonEvent.RELEASE -> {
                noteButtonAction("Button released")
            }
            OmiBleClient.ButtonEvent.PRESS -> noteButtonAction("Button pressed")
            OmiBleClient.ButtonEvent.IDLE -> Unit
            OmiBleClient.ButtonEvent.UNKNOWN -> noteButtonAction("Button event ${status.rawCode}")
        }
    }

    private fun handleBleStateChange(state: OmiBleClient.State) {
        Log.i(tag, "BLE state changed to: $state")
        if (state == OmiBleClient.State.DISCONNECTED) {
            if (liveMode) {
                Log.w(tag, "BLE disconnected during live capture — attempting auto-reconnect")
                lifecycleScope.launch {
                    delay(3000L)
                    if (liveMode && ble.state.value == OmiBleClient.State.DISCONNECTED) {
                        ble.startLiveStream()
                    }
                }
                } else if (buttonWatchArmed && !ble.storageSync.value.active) {
                    Log.w(tag, "BLE disconnected while button watch armed — attempting auto-reconnect")
                    lifecycleScope.launch {
                        delay(5000L)
                        if (buttonWatchArmed && !liveMode && !ble.storageSync.value.active && ble.state.value == OmiBleClient.State.DISCONNECTED) {
                            ble.armButtonWatch()
                        }
                    }
                }
            }
    }

    private fun syncAfterStoppingLiveIfNeeded() {
        if (!liveMode) {
            syncNow()
            return
        }
        stopLive()
        lifecycleScope.launch {
            delay(750L)
            syncNow()
        }
    }

    private fun noteButtonAction(action: String) {
        lastButtonAction = action
        lastButtonActionAt = System.currentTimeMillis()
        Log.i(tag, action)
    }

    private fun startAudioOut() {
        if (audioOutClient != null || audioOutPlayer != null) return
        val client = AudioOutClient(BuildConfig.AUDIO_OUT_WS_URL, BuildConfig.PENDANT_SECRET)
        val player = AudioOutPlayer(this)
        audioOutClient = client
        audioOutPlayer = player
        player.start(lifecycleScope, client.audioFrames)
        client.start(lifecycleScope)
        Log.i(tag, "Audio-out connected to ${BuildConfig.AUDIO_OUT_WS_URL}")
    }

    private fun stopAudioOut() {
        val client = audioOutClient
        val player = audioOutPlayer
        audioOutClient = null
        audioOutPlayer = null
        if (client == null && player == null) return
        lifecycleScope.launch {
            client?.stop()
            player?.stop()
        }
    }

    private fun startInForeground(includeMediaPlayback: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Chronicle companion", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Chronicle companion")
            .setContentText(notificationText(includeMediaPlayback))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (includeMediaPlayback) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(
                notifId,
                notification,
                type
            )
        } else {
            startForeground(notifId, notification)
        }
    }

    private fun notificationText(includeMediaPlayback: Boolean): String =
        when {
            liveMode || includeMediaPlayback -> "Live capture and voice are active"
            buttonWatchArmed -> "Button link is open briefly. Offline storage resumes after BLE disconnects"
            else -> "Ready to sync pendant files and surface useful nudges"
        }

    companion object {
        private const val BATTERY_POLL_MS = 5L * 60L * 1000L
        private const val HEARTBEAT_MS = 5L * 60L * 1000L
        private const val BUTTON_ARM_WINDOW_MS = 90L * 1000L
        private const val PENDING_AUDIO_REPLAY_MS = 60L * 1000L
        private const val PENDING_AUDIO_REPLAY_LIMIT = 12
        private const val AUTO_STORAGE_SYNC_INITIAL_DELAY_MS = 30L * 1000L
        private const val AUTO_STORAGE_SYNC_BACKLOG_INTERVAL_MS = 5L * 60L * 1000L
        private const val AUTO_STORAGE_SYNC_INTERVAL_MS = 60L * 60L * 1000L
        private const val AUTO_STORAGE_SYNC_RETRY_MS = 2L * 60L * 1000L

        const val ACTION_RUN_BACKFILL = "com.connor.pendant.action.RUN_BACKFILL"
        const val ACTION_SYNC_NOW = "com.connor.pendant.action.SYNC_NOW"
        const val ACTION_PROVE_RECORDING = "com.connor.pendant.action.PROVE_RECORDING"
        const val ACTION_START_LIVE = "com.connor.pendant.action.START_LIVE"
        const val ACTION_STOP_LIVE = "com.connor.pendant.action.STOP_LIVE"
        const val ACTION_RECONNECT_PENDANT = "com.connor.pendant.action.RECONNECT_PENDANT"
        const val ACTION_RECONNECT_AUDIO = "com.connor.pendant.action.RECONNECT_AUDIO"
        const val ACTION_ARM_BUTTON = "com.connor.pendant.action.ARM_BUTTON"
        const val ACTION_SET_MIC_GAIN_HIGH = "com.connor.pendant.action.SET_MIC_GAIN_HIGH"
        const val EXTRA_DAYS = "days"

        @Volatile var instance: PendantForegroundService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun syncNow(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_SYNC_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun proveRecording(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_PROVE_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun startLive(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_START_LIVE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stopLive(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_STOP_LIVE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun reconnectPendant(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_RECONNECT_PENDANT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun reconnectAudio(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_RECONNECT_AUDIO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun armButton(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_ARM_BUTTON
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun setMicGainHigh(ctx: Context) {
            val i = Intent(ctx, PendantForegroundService::class.java).apply {
                action = ACTION_SET_MIC_GAIN_HIGH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PendantForegroundService::class.java))
        }
    }
}
