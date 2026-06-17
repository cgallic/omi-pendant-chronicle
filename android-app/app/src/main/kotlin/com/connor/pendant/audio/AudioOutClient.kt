package com.connor.pendant.audio

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-lived WebSocket subscriber for pendant-audio-out (agent:8774/ws).
 *
 * Emits each received binary frame as a [ByteArray] on [audioFrames]. JSON control
 * frames (state updates, pongs) are logged but not surfaced — Phase 1 keeps the
 * surface narrow on purpose.
 *
 * Reconnects with capped exponential backoff (1s → 2s → 4s → 8s → 16s, then 16s
 * forever). The agent will rotate clients on its own when sends fail, so dropping
 * here is cheap; persistence matters more than uptime.
 */
class AudioOutClient(
    private val wsUrl: String,
    private val secret: String,
) {
    private val tag = "AudioOutClient"

    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            // WebSocket plugin manages keep-alive itself; explicit ping period
            // makes server-side disconnects visible quickly.
            pingIntervalMillis = 15_000L
        }
        engine {
            // OkHttp engine settings (no HttpTimeout plugin — it rejects 0/long-lived).
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)    // 0 = no timeout
                writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val audioFrames: SharedFlow<ByteArray> = _audioFrames.asSharedFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) {
            Log.w(tag, "start() called while already running; ignoring")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    Log.i(tag, "connecting → $wsUrl")
                    client.webSocket(
                        urlString = wsUrl,
                        request = { header("X-Pendant-Secret", secret) },
                    ) {
                        backoff = 1_000L
                        Log.i(tag, "connected")
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    val bytes = frame.readBytes()
                                    if (bytes.isNotEmpty()) {
                                        _audioFrames.emit(bytes)
                                    }
                                }
                                is Frame.Text -> {
                                    Log.d(tag, "ctrl: ${frame.readBytes().decodeToString().take(200)}")
                                }
                                is Frame.Close -> {
                                    Log.i(tag, "server closed: ${frame.readReason()}")
                                }
                                else -> Unit
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "ws loop error: ${t.message}")
                }
                if (!isActive) break
                Log.i(tag, "reconnecting in ${backoff}ms")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(16_000L)
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        withContext(Dispatchers.IO) {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }
}
