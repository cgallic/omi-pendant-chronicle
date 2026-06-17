package com.connor.pendant.health

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Read-only Health Connect adapter.
 *
 * Same shape as [com.connor.pendant.context.ContextCollector]: snapshot() returns a JSON
 * object suitable for merging into the 5-min heartbeat. Fails open — if HC is missing,
 * not installed, or no permissions are granted, snapshot() returns an empty object and
 * the rest of the heartbeat is unaffected.
 *
 * Window for "current" metrics (steps / distance / calories / HR) is the last 60 minutes.
 * Sleep is "most recent completed session", which references last night and is constant
 * across many heartbeats — the server-side aggregator dedupes it per conversation.
 */
class HealthConnectReader(private val context: Context) {

    private val tag = "HealthConnectReader"

    private var client: HealthConnectClient? = null
    @Volatile private var available: Boolean = false

    /**
     * Set of HC permission strings we request. Single-batch grant — user toggles
     * finer-grained metrics later in HC system Settings if they want.
     */
    val requestedPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    /**
     * Probes Health Connect availability and caches the client. Returns true if HC is
     * installed on the device. Safe to call repeatedly; cheap.
     */
    fun init(): Boolean {
        if (available) return true
        return try {
            val status = HealthConnectClient.getSdkStatus(context)
            when (status) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    client = HealthConnectClient.getOrCreate(context)
                    available = true
                    true
                }
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    Log.i(tag, "Health Connect SDK unavailable on this device")
                    false
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Log.i(tag, "Health Connect requires provider update")
                    false
                }
                else -> {
                    Log.i(tag, "Health Connect status=$status")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "init failed: ${t.message}")
            false
        }
    }

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        return try {
            val granted = c.permissionController.getGrantedPermissions()
            granted.containsAll(requestedPermissions)
        } catch (t: Throwable) {
            Log.w(tag, "permission check failed: ${t.message}")
            false
        }
    }

    /**
     * Builds the ActivityResultContract for the perm screen. Caller wires this up via
     * [androidx.activity.result.ActivityResultCaller.registerForActivityResult] inside
     * MainActivity, then invokes [launchPermissionRequest].
     */
    fun permissionContract() =
        PermissionController.createRequestPermissionResultContract()

    fun launchPermissionRequest(launcher: ActivityResultLauncher<Set<String>>) {
        launcher.launch(requestedPermissions)
    }

    /**
     * Builds the heartbeat-merge JSON block. Returns an empty object if HC is
     * unavailable or no permissions are granted — caller can merge unconditionally.
     */
    suspend fun snapshot(): JSONObject {
        val out = JSONObject()
        if (!init()) return out
        val c = client ?: return out
        if (!hasAllPermissions()) {
            // Partial perms might still let us read some records — keep going, but
            // log so it's visible if metrics are missing.
            Log.d(tag, "running snapshot with partial Health Connect permissions")
        }

        val now = Instant.now()
        val windowStart = now.minusSeconds(60 * 60)
        val window = TimeRangeFilter.between(windowStart, now)
        // Wider diagnostic window: probes whether HC has ANY data, so we can
        // distinguish "no recent activity" from "Fit isn't writing to HC at all".
        val dayWindow = TimeRangeFilter.between(now.minusSeconds(24 * 60 * 60), now)

        // ---- Steps ----
        runOrNull(tag, "steps") {
            val records = c.readRecords(ReadRecordsRequest(StepsRecord::class, window)).records
            val total = records.sumOf { it.count }
            val dayRecords = c.readRecords(ReadRecordsRequest(StepsRecord::class, dayWindow)).records
            val dayTotal = dayRecords.sumOf { it.count }
            Log.i(tag, "steps: hour=${records.size}rec/${total}, day=${dayRecords.size}rec/${dayTotal}")
            if (total > 0L) out.put("steps_hour", total)
            if (dayTotal > 0L) out.put("steps_today", dayTotal)
        }

        // ---- Distance ----
        runOrNull(tag, "distance") {
            val records = c.readRecords(ReadRecordsRequest(DistanceRecord::class, window)).records
            val totalMeters = records.sumOf { it.distance.inMeters }
            Log.i(tag, "distance: hour=${records.size}rec/${totalMeters}m")
            if (totalMeters > 0.0) out.put("distance_m_hour", totalMeters)
        }

        // ---- Active calories ----
        runOrNull(tag, "active_kcal") {
            val records = c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, window)).records
            val totalKcal = records.sumOf { it.energy.inKilocalories }
            Log.i(tag, "active_kcal: hour=${records.size}rec/${totalKcal}kcal")
            if (totalKcal > 0.0) out.put("active_kcal_hour", totalKcal)
        }

        // ---- Heart rate (most recent sample) ----
        runOrNull(tag, "heart_rate") {
            val records = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, window)).records
            val dayHr = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, dayWindow)).records
            Log.i(tag, "hr: hour=${records.size}rec, day=${dayHr.size}rec")
            var latestBpm: Long? = null
            var latestTime: Instant? = null
            var latestSource: String? = null
            for (rec in records) {
                for (sample in rec.samples) {
                    if (latestTime == null || sample.time.isAfter(latestTime)) {
                        latestTime = sample.time
                        latestBpm = sample.beatsPerMinute
                        latestSource = rec.metadata.dataOrigin.packageName
                    }
                }
            }
            if (latestBpm != null && latestTime != null) {
                val hr = JSONObject()
                hr.put("bpm", latestBpm)
                hr.put("ts_ms", latestTime!!.toEpochMilli())
                latestSource?.let { hr.put("source", it) }
                out.put("heart_rate", hr)

                // Also include full HR samples in the window so server-side downsampling
                // sees the true series rather than just one point per heartbeat. The
                // ContextCollector window will be much larger than 5 min in most cases.
                val series = JSONArray()
                var nSamples = 0
                var minBpm: Long = Long.MAX_VALUE
                var maxBpm: Long = Long.MIN_VALUE
                var sumBpm: Long = 0
                for (rec in records) {
                    for (sample in rec.samples) {
                        val arr = JSONArray()
                        arr.put(sample.time.toEpochMilli())
                        arr.put(sample.beatsPerMinute)
                        series.put(arr)
                        nSamples += 1
                        if (sample.beatsPerMinute < minBpm) minBpm = sample.beatsPerMinute
                        if (sample.beatsPerMinute > maxBpm) maxBpm = sample.beatsPerMinute
                        sumBpm += sample.beatsPerMinute
                    }
                }
                if (nSamples > 0) {
                    val stats = JSONObject()
                    stats.put("n_samples", nSamples)
                    stats.put("avg", sumBpm.toDouble() / nSamples)
                    stats.put("min", minBpm)
                    stats.put("max", maxBpm)
                    out.put("hr_window_stats", stats)
                    out.put("hr_window_series", series)
                }
            }
        }

        // ---- Oxygen saturation (most recent in window) ----
        runOrNull(tag, "spo2") {
            val records = c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, window)).records
            val latest = records.maxByOrNull { it.time }
            if (latest != null) out.put("spo2_pct", latest.percentage.value)
        }

        // ---- Sleep (most recent completed session, look back 36h) ----
        runOrNull(tag, "sleep") {
            val sleepWindow = TimeRangeFilter.between(now.minusSeconds(36 * 60 * 60), now)
            val records = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepWindow)).records
            Log.i(tag, "sleep: 36h=${records.size}rec")
            val latest = records.maxByOrNull { it.endTime }
            if (latest != null) {
                val sleep = JSONObject()
                sleep.put("start_ms", latest.startTime.toEpochMilli())
                sleep.put("end_ms", latest.endTime.toEpochMilli())
                sleep.put("duration_min", (latest.endTime.toEpochMilli() - latest.startTime.toEpochMilli()) / 60_000L)
                if (latest.stages.isNotEmpty()) {
                    val stagesByType = mutableMapOf("rem" to 0L, "deep" to 0L, "light" to 0L, "awake" to 0L)
                    for (stage in latest.stages) {
                        val ms = stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()
                        val key = when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                            SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
                            else -> null
                        }
                        if (key != null) stagesByType[key] = stagesByType.getOrDefault(key, 0L) + ms / 60_000L
                    }
                    val stagesJson = JSONObject()
                    stagesByType.forEach { (k, v) -> stagesJson.put(k, v) }
                    sleep.put("stages", stagesJson)
                }
                out.put("sleep_last", sleep)
            }
        }

        return out
    }

    private suspend inline fun runOrNull(tag: String, label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(tag, "$label read failed: ${t.message}")
        }
    }
}
