package com.connor.pendant.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays each audio blob received from [AudioOutClient.audioFrames] through whatever
 * A2DP sink the OS has bound (typically Bluetooth headphones paired to the phone).
 *
 * Strategy: write each MP3 blob to a temp file under `cacheDir/audio-out/`, enqueue
 * it on a single ExoPlayer instance. ExoPlayer handles the queue so utterances
 * arriving faster than playback play in arrival order. Temp files get cleaned up
 * when their MediaItem reports ENDED.
 *
 * Audio attributes: USAGE_ASSISTANT + CONTENT_TYPE_SPEECH so the OS routes the
 * stream like a navigation/Assistant cue — meaning it ducks music, respects
 * STREAM_MUSIC volume, and goes to BT headphones if connected.
 */
@UnstableApi
class AudioOutPlayer(
    private val context: Context,
) {
    private val tag = "AudioOutPlayer"

    private val cacheRoot: File by lazy {
        File(context.cacheDir, "audio-out").apply { mkdirs() }
    }
    private val seq = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var collectJob: Job? = null

    // Track temp files we own so we can clean up after playback ends.
    private val pendingFiles = ArrayDeque<File>()

    fun start(scope: CoroutineScope, source: SharedFlow<ByteArray>) {
        if (collectJob?.isActive == true) {
            Log.w(tag, "start() while already running; ignoring")
            return
        }
        mainHandler.post {
            if (player == null) {
                player = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_ASSISTANT)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        // Media3 only allows auto-focus for USAGE_MEDIA / USAGE_GAME — we
                        // want assistant semantics (ducks-friendly playback over A2DP) so
                        // disable auto-focus to avoid the runtime assertion crash.
                        /* handleAudioFocus = */ false,
                    )
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                                // Whatever finished is at the head of pendingFiles; reap it.
                                pendingFiles.removeFirstOrNull()?.let { f ->
                                    try {
                                        if (f.exists()) f.delete()
                                    } catch (_: Throwable) {
                                    }
                                }
                                if (state == Player.STATE_ENDED && mediaItemCount == 0) {
                                    Log.d(tag, "queue drained")
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.w(tag, "player error: ${error.errorCodeName}: ${error.message}")
                            pendingFiles.removeFirstOrNull()?.let { f ->
                                try {
                                    if (f.exists()) f.delete()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    })
                    playWhenReady = true
                }
                Log.i(tag, "ExoPlayer initialized")
            }
        }

        collectJob = scope.launch(Dispatchers.IO) {
            source.collect { bytes ->
                if (bytes.isEmpty()) return@collect
                val out = File(cacheRoot, "u-${System.currentTimeMillis()}-${seq.incrementAndGet()}.mp3")
                try {
                    out.writeBytes(bytes)
                } catch (t: Throwable) {
                    Log.w(tag, "write temp failed: ${t.message}")
                    return@collect
                }
                pendingFiles.addLast(out)
                mainHandler.post {
                    val p = player ?: return@post
                    p.addMediaItem(MediaItem.fromUri(out.toURI().toString()))
                    if (p.playbackState == Player.STATE_IDLE) {
                        p.prepare()
                    }
                }
            }
        }
    }

    suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        withContext(Dispatchers.Main) {
            player?.release()
            player = null
        }
        // Best-effort cleanup of leftover temp files.
        try {
            cacheRoot.listFiles()?.forEach {
                try {
                    it.delete()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        pendingFiles.clear()
    }
}
