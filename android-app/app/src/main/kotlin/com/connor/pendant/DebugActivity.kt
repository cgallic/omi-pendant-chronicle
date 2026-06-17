package com.connor.pendant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.connor.pendant.ble.OmiBleClient
import com.connor.pendant.service.PendantForegroundService
import com.connor.pendant.store.AudioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Original M1 debug surface — BLE state, frames, posted/failed, audio store status.
 * Hidden behind a long-press shortcut on the launcher icon. The default app icon
 * tap opens [MainActivity] (the Chronicle WebView) instead.
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var storeStatus: AudioStore.Status? = null
    private var permissionAction: (() -> Unit)? = null
    private val ticker = object : Runnable {
        override fun run() {
            statusText.text = buildStatus()
            handler.postDelayed(this, 1_000)
        }
    }
    private val storeTicker = object : Runnable {
        override fun run() {
            val svc = PendantForegroundService.instance
            val store = svc?.audioStore
            if (store != null) {
                lifecycleScope.launch {
                    val s = withContext(Dispatchers.IO) { store.status() }
                    storeStatus = s
                }
            }
            handler.postDelayed(this, 2_000)
        }
    }

    private fun fmtBytes(n: Long): String {
        if (n < 1024) return "${n}B"
        if (n < 1024 * 1024) return "%.1fKB".format(n / 1024.0)
        if (n < 1024L * 1024 * 1024) return "%.1fMB".format(n / 1048576.0)
        return "%.2fGB".format(n / 1073741824.0)
    }
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        if (s < 60) return "${s}s"
        val m = s / 60
        if (m < 60) return "${m}m ${s % 60}s"
        val h = m / 60
        return "${h}h ${m % 60}m"
    }
    private fun ago(ms: Long?): String {
        if (ms == null) return "—"
        val a = System.currentTimeMillis() - ms
        if (a < 0) return "just now"
        return when {
            a < 60_000 -> "${a / 1000}s ago"
            a < 3_600_000 -> "${a / 60_000}m ago"
            else -> "${a / 3_600_000}h ago"
        }
    }

    private fun buildStatus(): String {
        val svc = PendantForegroundService.instance ?: return "Service not started."
        val ble = svc.bleClient
        val up = svc.agentUploader
        val st = storeStatus
        val sb = StringBuilder()
        sb.append("Live mode: ").append(if (svc.isLiveMode) "on" else "off").append('\n')
        sb.append("BLE state: ").append(ble?.state?.value ?: "—").append('\n')
        sb.append("Device:    ").append(ble?.deviceAddr ?: "(none yet — scanning)").append('\n')
        sb.append("RSSI:      ").append(ble?.lastRssi?.let { "${it} dBm" } ?: "—").append('\n')
        val bat = ble?.batteryPct?.value
        val batAge = if (ble != null && ble.lastBatteryReadAt > 0)
            ago(ble.lastBatteryReadAt) else "—"
        sb.append("Battery:   ").append(bat?.let { "${it}%" } ?: "—").append("  (read ").append(batAge).append(")\n")
        sb.append("MTU:       ").append(ble?.mtu ?: 0).append('\n')
        sb.append("Codec:     ").append(ble?.codec?.value ?: "—").append('\n')
        val mic = ble?.micGain?.value
        val micValue = mic?.value
        sb.append("Mic gain:  ")
            .append(micValue?.let { "$it (${OmiBleClient.micGainLabel(it)})" } ?: "—")
            .append("  target=")
            .append(mic?.target ?: OmiBleClient.CAPTURE_MIC_GAIN_LEVEL)
            .append(" (")
            .append(OmiBleClient.micGainLabel(mic?.target ?: OmiBleClient.CAPTURE_MIC_GAIN_LEVEL))
            .append(")")
            .append("  set=")
            .append(if (mic != null && mic.lastSetAt > 0) ago(mic.lastSetAt) else "—")
            .append('\n')
        if (mic?.lastError != null) sb.append("Mic err:   ").append(mic.lastError).append('\n')
        sb.append("Frames:    ").append(ble?.frameCount() ?: 0).append("  last=").append(ble?.lastFrameSize ?: 0).append("B\n")
        sb.append("BLE err:   ").append(ble?.lastError ?: "—").append('\n')
        val sync = ble?.storageSync?.value
        if (sync != null) {
            sb.append("Sync:      ")
                .append(if (sync.active) "active" else "idle")
                .append(" files=").append(sync.filesDone).append('/').append(sync.filesTotal)
                .append(" frames=").append(sync.framesDone)
                .append(" bytes=").append(fmtBytes(sync.bytesDone))
                .append('/').append(fmtBytes(sync.bytesTotal))
                .append('\n')
            if (sync.lastError != null) sb.append("Sync err:  ").append(sync.lastError).append('\n')
        }
        sb.append('\n')
        sb.append("POST url:  ").append(up?.targetUrl ?: "—").append('\n')
        sb.append("Posted:    ").append(up?.posted() ?: 0).append('\n')
        sb.append("Failed:    ").append(up?.failed() ?: 0).append("  dropped=").append(up?.dropped() ?: 0).append("  last_http=").append(up?.lastStatus ?: 0).append('\n')
        sb.append("Batches:   ").append(up?.batches() ?: 0).append("  last_batch=").append(up?.lastBatchSize ?: 0).append('\n')
        sb.append("HTTP err:  ").append(up?.lastError ?: "—").append('\n')
        sb.append('\n')
        if (st == null) {
            sb.append("Audio:     (loading…)\n")
        } else {
            sb.append("Stored:    ").append(fmtMs(st.storedMs)).append("  /  ").append(fmtBytes(st.storedBytes)).append('\n')
            sb.append("Newest:    ").append(ago(st.newestEnd)).append('\n')
            sb.append("Oldest:    ").append(ago(st.oldestStart)).append('\n')
            sb.append("Sync queue:").append(' ').append(st.queueCount).append(" segments\n")
            sb.append("Current:   ").append(st.currentFrames).append(" frames / ")
              .append(fmtBytes(st.currentBytes)).append(" / ")
              .append(fmtMs(st.currentSegmentAgeMs)).append('\n')
        }
        return sb.toString()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            val action = permissionAction ?: { startService() }
            permissionAction = null
            action()
        } else {
            permissionAction = null
            statusText.text = "Permissions denied — cannot start BLE.\n\nGranted map:\n" +
                results.entries.joinToString("\n") { "  ${it.key.substringAfterLast('.')} = ${it.value}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        statusText = TextView(this).apply {
            text = "Pendant debug — BLE state, frames, audio store."
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val startBtn = Button(this).apply {
            text = "Start"
            setOnClickListener { askPermissionsThenStart() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                PendantForegroundService.stop(this@DebugActivity)
                handler.removeCallbacks(ticker)
                statusText.text = "Stopped."
            }
        }
        val syncBtn = Button(this).apply {
            text = "Sync Now"
            setOnClickListener {
                PendantForegroundService.syncNow(this@DebugActivity)
                handler.post(ticker)
                handler.post(storeTicker)
            }
        }
        val micHighBtn = Button(this).apply {
            text = "Set Mic Max (+40dB)"
            setOnClickListener {
                askPermissionsThen {
                    PendantForegroundService.setMicGainHigh(this@DebugActivity)
                    handler.post(ticker)
                }
            }
        }
        val liveBtn = Button(this).apply {
            text = "Start Live"
            setOnClickListener {
                askPermissionsThen { startLiveService() }
            }
        }
        val stopLiveBtn = Button(this).apply {
            text = "Stop Live"
            setOnClickListener {
                PendantForegroundService.stopLive(this@DebugActivity)
                handler.post(ticker)
                handler.post(storeTicker)
            }
        }
        val chronicleBtn = Button(this).apply {
            text = "Open Chronicle"
            setOnClickListener {
                startActivity(android.content.Intent(this@DebugActivity, MainActivity::class.java))
            }
        }
        root.addView(statusText)
        root.addView(startBtn)
        root.addView(micHighBtn)
        root.addView(syncBtn)
        root.addView(liveBtn)
        root.addView(stopLiveBtn)
        root.addView(stopBtn)
        root.addView(chronicleBtn)
        setContentView(root)

        // If the service is already running (long-press came in mid-session), just start the tickers.
        if (PendantForegroundService.instance != null) {
            handler.post(ticker)
            handler.post(storeTicker)
        }
    }

    private fun askPermissionsThenStart() {
        askPermissionsThen { startService() }
    }

    private fun askPermissionsThen(action: () -> Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            permissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startService() {
        PendantForegroundService.start(this)
        handler.post(ticker)
        handler.post(storeTicker)
    }

    private fun startLiveService() {
        PendantForegroundService.startLive(this)
        handler.post(ticker)
        handler.post(storeTicker)
    }

    override fun onResume() {
        super.onResume()
        if (PendantForegroundService.instance != null) {
            handler.post(ticker)
            handler.post(storeTicker)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(storeTicker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(storeTicker)
        super.onDestroy()
    }
}
