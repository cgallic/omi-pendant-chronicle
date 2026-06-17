package com.connor.pendant.context

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Collects the "context block" sampled alongside pendant audio:
 *   - GPS (FusedLocationProvider, current location with high accuracy)
 *   - Step counter (since last boot — caller takes deltas)
 *   - Foreground app (UsageStatsManager, last 1 min)
 *
 * Each signal is independent and degrades gracefully when its permission isn't granted —
 * the resulting JSON simply omits that field. The agent enriches conversations from these
 * heartbeats at finalization time.
 */
class ContextCollector(private val context: Context) : SensorEventListener {

    private val tag = "ContextCollector"

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val usageStats: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile private var stepsSinceBoot: Float? = null

    fun start() {
        if (!hasActivityPerm()) {
            Log.i(tag, "Step counter not started — ACTIVITY_RECOGNITION not granted")
            return
        }
        val s = stepCounter ?: run {
            Log.w(tag, "Device has no TYPE_STEP_COUNTER sensor")
            return
        }
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Build a JSON snapshot for the heartbeat. Always returns — fields are best-effort.
     * GPS is the only slow path; bounded by the FusedLocationProvider timeout.
     */
    suspend fun snapshot(): JSONObject {
        val out = JSONObject()
        out.put("ts_ms", System.currentTimeMillis())

        // ---- Steps ----
        stepsSinceBoot?.let { out.put("steps_since_boot", it.toLong()) }

        // ---- Foreground app ----
        usageStats?.let { mgr ->
            if (hasUsageStatsPerm()) {
                val now = System.currentTimeMillis()
                try {
                    val events = mgr.queryEvents(now - 60_000L, now)
                    var lastForeground: String? = null
                    val ev = android.app.usage.UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev)
                        if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            lastForeground = ev.packageName
                        }
                    }
                    lastForeground?.let { out.put("foreground_app", it) }
                } catch (t: Throwable) {
                    Log.w(tag, "usage stats query failed: ${t.message}")
                }
            }
        }

        // ---- GPS ----
        if (hasLocationPerm()) {
            try {
                val loc = readLocation()
                if (loc != null) {
                    val locObj = JSONObject()
                    locObj.put("lat", loc.latitude)
                    locObj.put("lon", loc.longitude)
                    locObj.put("accuracy_m", loc.accuracy)
                    locObj.put("fix_ms", loc.time)
                    if (loc.hasAltitude()) locObj.put("altitude_m", loc.altitude)
                    if (loc.hasSpeed()) locObj.put("speed_mps", loc.speed)
                    out.put("location", locObj)
                }
            } catch (t: Throwable) {
                Log.w(tag, "location read failed: ${t.message}")
            }
        }

        return out
    }

    private suspend fun readLocation() = suspendCancellableCoroutine<android.location.Location?> { cont ->
        // Try last-known first (instant, may be stale).
        @Suppress("MissingPermission")
        fused.lastLocation
            .addOnSuccessListener { last ->
                // If lastLocation is reasonably fresh (<2 min), use it. Otherwise request a fresh fix.
                val ageMs = if (last != null) System.currentTimeMillis() - last.time else Long.MAX_VALUE
                if (last != null && ageMs < 2 * 60 * 1000L) {
                    if (cont.isActive) cont.resume(last)
                } else {
                    @Suppress("MissingPermission")
                    fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { fresh -> if (cont.isActive) cont.resume(fresh ?: last) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(last) }
                }
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    // ---- SensorEventListener ----

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
            stepsSinceBoot = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }

    // ---- Permission helpers ----

    private fun hasLocationPerm(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasActivityPerm(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasUsageStatsPerm(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
