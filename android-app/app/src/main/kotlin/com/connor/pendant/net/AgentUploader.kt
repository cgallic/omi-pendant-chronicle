package com.connor.pendant.net

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Posts raw audio frames (with 3-byte Omi header still attached) to the agent-side sink.
 *
 * Battery mode: frames are queued and uploaded as length-prefixed batches to
 * `/raw-batch`. If the agent has not been upgraded yet, a batch falls back to the
 * original one-frame-per-POST `/raw` endpoint so sync remains functional.
 */
class AgentUploader(
    private val url: String,
    private val secret: String
) {
    private val tag = "AgentUploader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batchUrl = url.trimEnd('/').replaceFirst(Regex("/raw$"), "/raw-batch")
    private val queue = Channel<ByteArray>(capacity = QUEUE_CAPACITY)

    private val posted = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val batches = AtomicLong(0)

    fun posted(): Long = posted.get()
    fun failed(): Long = failed.get()
    fun dropped(): Long = dropped.get()
    fun batches(): Long = batches.get()

    @Volatile var lastError: String? = null
        private set
    @Volatile var lastStatus: Int = 0
        private set
    @Volatile var lastBatchSize: Int = 0
        private set
    val targetUrl: String = url

    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 2_000
            socketTimeout = 5_000
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
        }
    }

    init {
        scope.launch { drainLoop() }
    }

    suspend fun postFrame(frame: ByteArray) {
        queue.send(frame.copyOf())
    }

    suspend fun forceProcess(): Boolean {
        val forceUrl = url.trimEnd('/').replaceFirst(Regex("/raw$"), "/raw/force-process")
        return try {
            val resp = client.post(forceUrl) {
                header("X-Pendant-Secret", secret)
            }
            resp.status.value in 200..299
        } catch (t: Throwable) {
            Log.e(tag, "Failed to send force-process: ${t.message}", t)
            false
        }
    }

    suspend fun postFramesNow(frames: List<ByteArray>, startedAtMs: Long? = null): Boolean {
        if (frames.isEmpty()) return true

        val batch = ArrayList<ByteArray>(MAX_BATCH_FRAMES)
        var bytes = 0

        for (frame in frames) {
            val wouldOverflow = batch.isNotEmpty() &&
                (batch.size >= MAX_BATCH_FRAMES || bytes + frame.size > MAX_BATCH_BYTES)
            if (wouldOverflow) {
                if (!postBatch(batch, startedAtMs)) return false
                batch.clear()
                bytes = 0
            }

            batch += frame
            bytes += frame.size
        }

        return batch.isEmpty() || postBatch(batch, startedAtMs)
    }

    private suspend fun drainLoop() {
        while (scope.isActive) {
            val first = queue.receive()
            val batch = ArrayList<ByteArray>(MAX_BATCH_FRAMES)
            var bytes = 0
            batch += first
            bytes += first.size

            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < MAX_BATCH_FRAMES && bytes < MAX_BATCH_BYTES) {
                    val next = queue.receive()
                    batch += next
                    bytes += next.size
                }
            }

            postBatch(batch)
        }
    }

    private suspend fun postBatch(frames: List<ByteArray>, startedAtMs: Long? = null): Boolean {
        if (frames.isEmpty()) return true

        try {
            val resp = if (frames.size == 1) {
                postSingle(frames.first(), startedAtMs)
            } else {
                client.post(batchUrl) {
                    header("X-Pendant-Secret", secret)
                    header("X-Pendant-Batch", "length-prefixed-v1")
                    if (startedAtMs != null) {
                        header("X-Pendant-Session-Start", startedAtMs.toString())
                    }
                    contentType(ContentType.Application.OctetStream)
                    setBody(encodeBatch(frames))
                }
            }
            lastStatus = resp.status.value
            if (resp.status.value in 200..299) {
                posted.addAndGet(frames.size.toLong())
                if (frames.size > 1) batches.incrementAndGet()
                lastBatchSize = frames.size
                val n = posted.get()
                if (n % 500L < frames.size) {
                    Log.d(tag, "posted=$n failed=${failed.get()} dropped=${dropped.get()} batch=${frames.size}")
                }
                return true
            }

            if (frames.size > 1 && resp.status.value in listOf(404, 405)) {
                return postIndividually(frames, startedAtMs)
            }

            lastError = "HTTP ${resp.status.value}"
            val n = failed.addAndGet(frames.size.toLong())
            if (n < 10L) Log.w(tag, "POST ${resp.status.value}")
            return false
        } catch (t: Throwable) {
            if (frames.size > 1) {
                try {
                    return postIndividually(frames, startedAtMs)
                } catch (fallback: Throwable) {
                    lastError = fallback.javaClass.simpleName + ": " + (fallback.message ?: "?")
                }
            } else {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "?")
            }
            val n = failed.addAndGet(frames.size.toLong())
            if (n < 10L || n % 50L == 0L) Log.w(tag, "POST failed (#$n): ${lastError}")
            return false
        }
    }

    private suspend fun postIndividually(frames: List<ByteArray>, startedAtMs: Long? = null): Boolean {
        var allPosted = true
        for (frame in frames) {
            val resp = postSingle(frame, startedAtMs)
            lastStatus = resp.status.value
            if (resp.status.value !in 200..299) {
                lastError = "HTTP ${resp.status.value}"
                failed.incrementAndGet()
                Log.w(tag, "fallback POST ${resp.status.value}")
                allPosted = false
                continue
            }
            posted.incrementAndGet()
        }
        lastBatchSize = 1
        return allPosted
    }

    private suspend fun postSingle(frame: ByteArray, startedAtMs: Long? = null) =
        client.post(url) {
            header("X-Pendant-Secret", secret)
            if (startedAtMs != null) {
                header("X-Pendant-Session-Start", startedAtMs.toString())
            }
            contentType(ContentType.Application.OctetStream)
            setBody(frame)
        }

    private fun encodeBatch(frames: List<ByteArray>): ByteArray {
        val totalBytes = 4 + frames.sumOf { 4 + it.size }
        val out = ByteBuffer.allocate(totalBytes)
        out.putInt(frames.size)
        frames.forEach { frame ->
            out.putInt(frame.size)
            out.put(frame)
        }
        return out.array()
    }

    companion object {
        private const val QUEUE_CAPACITY = 8_192
        private const val MAX_BATCH_FRAMES = 128
        private const val MAX_BATCH_BYTES = 256 * 1024
        private const val BATCH_WINDOW_MS = 750L
    }
}
