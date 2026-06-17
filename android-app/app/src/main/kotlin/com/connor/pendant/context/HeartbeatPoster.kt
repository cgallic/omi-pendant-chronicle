package com.connor.pendant.context

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.concurrent.atomic.AtomicLong

/**
 * POSTs a context snapshot to the agent every 5 min.
 *
 * The agent URL is the same host:port as the raw-frame sink (raw_sink.py @ 8773), with
 * `/raw` swapped for `/phone-state`. Same X-Pendant-Secret header. Best-effort —
 * dropped heartbeats are not retried; the next one supersedes.
 */
class HeartbeatPoster(rawFrameUrl: String, private val secret: String) {

    private val tag = "HeartbeatPoster"

    val targetUrl: String = rawFrameUrl.trimEnd('/').replaceFirst(Regex("/raw$"), "") + "/phone-state"

    private val posted = AtomicLong(0)
    private val failed = AtomicLong(0)
    fun posted(): Long = posted.get()
    fun failed(): Long = failed.get()

    @Volatile var lastStatus: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 3_000
            socketTimeout = 8_000
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    suspend fun postSnapshot(payload: String) {
        try {
            val resp = client.post(targetUrl) {
                header("X-Pendant-Secret", secret)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            lastStatus = resp.status.value
            if (resp.status.value !in 200..299) {
                lastError = "HTTP ${resp.status.value}"
                failed.incrementAndGet()
                Log.w(tag, "POST ${resp.status.value}")
            } else {
                posted.incrementAndGet()
                Log.d(tag, "heartbeat posted (n=${posted.get()})")
            }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "?")
            failed.incrementAndGet()
            Log.w(tag, "POST failed: ${t.message}")
        }
    }
}
