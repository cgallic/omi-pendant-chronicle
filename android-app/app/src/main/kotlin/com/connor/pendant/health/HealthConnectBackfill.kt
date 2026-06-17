package com.connor.pendant.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * One-shot Health Connect historical backfill.
 *
 * Reads the last N days (default 30) of HC records and streams them to the agent in
 * chunks of [CHUNK_SIZE] records per POST. Each chunk arrives at the agent's
 * /pendant/phone-state-backfill endpoint and is appended verbatim to
 * /srv/ai/pendant/state/phone_state_backfill.jsonl. The enrich script on the agent
 * then walks conversation files and aggregates overlapping samples into context.health.
 *
 * Trigger: BackfillReceiver broadcast (adb-fireable) or in-app entry.
 *
 * One backfill run is one session_id (uuid); the server can detect orphaned partials by
 * counting chunks per session. Idempotent on the phone side: re-running just appends
 * another session's samples — the agent-side enrich script dedupes by (kind, ts_ms).
 */
class HealthConnectBackfill(
    private val context: Context,
    private val agentUrl: String,
    private val secret: String,
    private val daysBack: Int = 30,
) {
    data class Stats(
        val sessionId: String,
        val daysBack: Int,
        val recordsByKind: Map<String, Int>,
        val chunksPosted: Int,
        val chunksFailed: Int,
        val durationMs: Long,
    )

    private val tag = "HealthConnectBackfill"
    private val backfillUrl: String =
        agentUrl.trimEnd('/').replaceFirst(Regex("/raw$"), "") + "/phone-state-backfill"

    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 5_000
            socketTimeout = 30_000
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
    }

    suspend fun run(): Stats {
        val started = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()

        val hc = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val start = end.minusSeconds(daysBack * 24L * 3600L)
        val window = TimeRangeFilter.between(start, end)
        val rangeStartMs = start.toEpochMilli()
        val rangeEndMs = end.toEpochMilli()

        Log.i(tag, "backfill session=$sessionId days=$daysBack start=$start end=$end")

        val counts = mutableMapOf<String, Int>()
        var chunksPosted = 0
        var chunksFailed = 0

        suspend fun ship(kind: String, samples: List<JSONObject>) {
            if (samples.isEmpty()) {
                counts[kind] = 0
                return
            }
            counts[kind] = samples.size
            val chunks = samples.chunked(CHUNK_SIZE)
            chunks.forEachIndexed { idx, batch ->
                val payload = JSONObject().apply {
                    put("backfill", true)
                    put("kind", kind)
                    put("session_id", sessionId)
                    put("chunk_idx", idx)
                    put("chunk_total", chunks.size)
                    put("range_start_ms", rangeStartMs)
                    put("range_end_ms", rangeEndMs)
                    put("posted_at_ms", System.currentTimeMillis())
                    put("samples", JSONArray().also { arr -> batch.forEach { arr.put(it) } })
                }
                if (postChunk(payload.toString())) chunksPosted++ else chunksFailed++
            }
            Log.i(tag, "shipped kind=$kind samples=${samples.size} chunks=${chunks.size}")
        }

        // ---- Heart rate (flatten HeartRateRecord.samples) ----
        runOrLog("hr") {
            val records = readAllPages(hc, HeartRateRecord::class, window)
            val out = mutableListOf<JSONObject>()
            for (rec in records) {
                val source = rec.metadata.dataOrigin.packageName
                for (s in rec.samples) {
                    out += JSONObject().apply {
                        put("ts_ms", s.time.toEpochMilli())
                        put("bpm", s.beatsPerMinute)
                        put("source", source)
                    }
                }
            }
            ship("hr", out)
        }

        // ---- Steps ----
        runOrLog("steps") {
            val records = readAllPages(hc, StepsRecord::class, window)
            val out = records.map {
                JSONObject().apply {
                    put("start_ms", it.startTime.toEpochMilli())
                    put("end_ms", it.endTime.toEpochMilli())
                    put("count", it.count)
                    put("source", it.metadata.dataOrigin.packageName)
                }
            }
            ship("steps", out)
        }

        // ---- Distance ----
        runOrLog("distance") {
            val records = readAllPages(hc, DistanceRecord::class, window)
            val out = records.map {
                JSONObject().apply {
                    put("start_ms", it.startTime.toEpochMilli())
                    put("end_ms", it.endTime.toEpochMilli())
                    put("meters", it.distance.inMeters)
                    put("source", it.metadata.dataOrigin.packageName)
                }
            }
            ship("distance", out)
        }

        // ---- Active calories ----
        runOrLog("active_kcal") {
            val records = readAllPages(hc, ActiveCaloriesBurnedRecord::class, window)
            val out = records.map {
                JSONObject().apply {
                    put("start_ms", it.startTime.toEpochMilli())
                    put("end_ms", it.endTime.toEpochMilli())
                    put("kcal", it.energy.inKilocalories)
                    put("source", it.metadata.dataOrigin.packageName)
                }
            }
            ship("active_kcal", out)
        }

        // ---- SpO2 ----
        runOrLog("spo2") {
            val records = readAllPages(hc, OxygenSaturationRecord::class, window)
            val out = records.map {
                JSONObject().apply {
                    put("ts_ms", it.time.toEpochMilli())
                    put("pct", it.percentage.value)
                    put("source", it.metadata.dataOrigin.packageName)
                }
            }
            ship("spo2", out)
        }

        // ---- Sleep ----
        runOrLog("sleep") {
            val records = readAllPages(hc, SleepSessionRecord::class, window)
            val out = records.map { sess ->
                val durMin = (sess.endTime.toEpochMilli() - sess.startTime.toEpochMilli()) / 60_000L
                val stages = JSONObject().apply {
                    val byType = mutableMapOf("rem" to 0L, "deep" to 0L, "light" to 0L, "awake" to 0L)
                    for (stage in sess.stages) {
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
                        if (key != null) byType[key] = byType.getOrDefault(key, 0L) + ms / 60_000L
                    }
                    byType.forEach { (k, v) -> put(k, v) }
                }
                JSONObject().apply {
                    put("start_ms", sess.startTime.toEpochMilli())
                    put("end_ms", sess.endTime.toEpochMilli())
                    put("duration_min", durMin)
                    put("stages", stages)
                    put("source", sess.metadata.dataOrigin.packageName)
                }
            }
            ship("sleep", out)
        }

        // ---- Exercise ----
        runOrLog("exercise") {
            val records = readAllPages(hc, ExerciseSessionRecord::class, window)
            val out = records.map {
                JSONObject().apply {
                    put("start_ms", it.startTime.toEpochMilli())
                    put("end_ms", it.endTime.toEpochMilli())
                    put("exercise_type", it.exerciseType)
                    put("source", it.metadata.dataOrigin.packageName)
                }
            }
            ship("exercise", out)
        }

        // ---- Marker chunk so the server knows this session is done ----
        postChunk(
            JSONObject().apply {
                put("backfill", true)
                put("kind", "_done")
                put("session_id", sessionId)
                put("chunk_idx", 0)
                put("chunk_total", 1)
                put("range_start_ms", rangeStartMs)
                put("range_end_ms", rangeEndMs)
                put("posted_at_ms", System.currentTimeMillis())
                put("counts", JSONObject().also { c -> counts.forEach { (k, v) -> c.put(k, v) } })
                put("samples", JSONArray())
            }.toString()
        )

        val stats = Stats(
            sessionId = sessionId,
            daysBack = daysBack,
            recordsByKind = counts.toMap(),
            chunksPosted = chunksPosted,
            chunksFailed = chunksFailed,
            durationMs = System.currentTimeMillis() - started,
        )
        Log.i(tag, "backfill done: $stats")
        return stats
    }

    private suspend fun postChunk(body: String): Boolean {
        return try {
            val resp = client.post(backfillUrl) {
                header("X-Pendant-Secret", secret)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val ok = resp.status.value in 200..299
            if (!ok) Log.w(tag, "POST ${resp.status.value} url=$backfillUrl")
            ok
        } catch (t: Throwable) {
            Log.w(tag, "POST failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private suspend inline fun runOrLog(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(tag, "$label read failed: ${t.message}")
        }
    }

    /**
     * Drains every page of [recordType] in [window]. HC's default pageSize is 1000;
     * Connor has ~9000 step records over 30 days so a single request truncates 88%
     * of history. Loop until pageToken is null.
     */
    private suspend fun <T : Record> readAllPages(
        hc: HealthConnectClient,
        recordType: KClass<T>,
        window: TimeRangeFilter,
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        var pages = 0
        do {
            val req = ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = window,
                pageSize = PAGE_SIZE,
                pageToken = token,
            )
            val resp = hc.readRecords(req)
            out += resp.records
            token = resp.pageToken
            pages += 1
            if (pages >= MAX_PAGES) {
                Log.w(tag, "${recordType.simpleName} hit MAX_PAGES=$MAX_PAGES — capping at ${out.size}")
                break
            }
        } while (token != null)
        Log.d(tag, "${recordType.simpleName} drained ${out.size} records in $pages pages")
        return out
    }

    companion object {
        const val CHUNK_SIZE = 500
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 50  // 50_000 records ceiling per kind — safety, not expected
    }
}
