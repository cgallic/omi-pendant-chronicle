package com.connor.pendant.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk store for raw Opus frames from the pendant.
 *
 *  - Appends incoming frames to a "current" segment file at filesDir/audio/<date>/<HHMMSS>.opusraw
 *  - Rolls to a new segment file every [segmentMillis] (default 60s)
 *  - On roll: inserts a row in the Room DB capturing the file's metadata
 *  - Sweeps segments older than [retentionDays] (deletes file + marks DB row deleted_at)
 *
 * Live capture files are optimistic because the uploader posts those frames in real
 * time. Storage-sync files stay pending until their length-prefixed phone copy is
 * uploaded and marked posted.
 */
class AudioStore(
    context: Context,
    private val segmentMillis: Long = 60_000L,
    private val retentionDays: Int = 3,
    private val flushEveryFrames: Int = 32,
) {
    private val tag = "AudioStore"
    private val ctx = context.applicationContext
    private val db = PendantDatabase.get(ctx)
    private val dao = db.audioSegments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val rootDir: File = File(ctx.filesDir, "audio").apply { mkdirs() }

    @Volatile private var currentFile: File? = null
    @Volatile private var currentStream: FileOutputStream? = null
    @Volatile private var currentDateKey: String = ""
    @Volatile private var currentStartedAt: Long = 0L
    private val currentFrames = AtomicInteger(0)
    private val currentBytes = AtomicLong(0)
    private var framesSinceFlush = 0

    private val dateFmt: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val timeFmt: SimpleDateFormat = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Called from the BLE frame callback in the foreground service. Non-blocking. */
    fun acceptFrame(frame: ByteArray) {
        scope.launch {
            writeFrame(frame)
        }
    }

    suspend fun persistStorageFile(
        index: Int,
        timestamp: Long,
        expectedSize: Long,
        frames: List<ByteArray>,
    ): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) {
            Log.w(tag, "storage file persist refused index=$index frames=0")
            return@withContext null
        }

        mutex.withLock {
            val now = System.currentTimeMillis()
            finalizeCurrent(now)

            val timestampValid = isReasonableStorageTimestamp(timestamp)
            val startedAt = storageTimestampMillis(timestamp, now)
            val endedAt = startedAt + frames.size.toLong() * OPUS_FRAME_DURATION_MS
            val dateKey = dateFmt.format(Date(startedAt))
            val timeKey = timeFmt.format(Date(startedAt))
            val dayDir = File(rootDir, dateKey).apply { mkdirs() }
            val suffix = if (timestampValid) "storage" else "storage_notime"
            val baseName = "${timeKey}_idx${String.format(Locale.US, "%03d", index)}_$suffix"
            val finalFile = uniqueStorageFile(dayDir, baseName)
            val tmpFile = File(dayDir, "${finalFile.name}.tmp")
            val byteCount = frames.sumOf { it.size.toLong() }
            if (!timestampValid) {
                Log.w(tag, "storage file index=$index has missing/unusable timestamp=$timestamp; using sync time only")
            }

            try {
                FileOutputStream(tmpFile, false).use { fos ->
                    val out = DataOutputStream(fos.buffered())
                    writeStorageFrames(out, frames)
                    out.flush()
                    fos.fd.sync()
                    out.close()
                }

                if (!tmpFile.renameTo(finalFile)) {
                    throw IllegalStateException("rename failed")
                }

                val segmentId = dao.insert(
                    AudioSegment(
                        path = finalFile.absolutePath,
                        date_key = dateKey,
                        started_at = startedAt,
                        ended_at = endedAt,
                        frame_count = frames.size,
                        byte_count = byteCount,
                        posted_at = null,
                    )
                )
                Log.i(
                    tag,
                    "storage file persisted index=$index id=$segmentId frames=${frames.size} bytes=$byteCount expected=$expectedSize path=${finalFile.absolutePath}"
                )
                Pair(segmentId, startedAt)
            } catch (t: Throwable) {
                try { tmpFile.delete() } catch (_: Throwable) {}
                Log.e(tag, "storage file persist failed index=$index: ${t.message}")
                null
            }
        }
    }

    suspend fun pendingSegments(limit: Int = 20): List<AudioSegment> =
        withContext(Dispatchers.IO) { dao.unposted(limit) }

    suspend fun markPosted(id: Long, ts: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) { dao.markPosted(id, ts) }
    }

    suspend fun readSegmentFrames(seg: AudioSegment): List<ByteArray>? =
        withContext(Dispatchers.IO) {
            readStorageFrames(File(seg.path), seg.id, seg.frame_count)
        }

    private suspend fun writeFrame(frame: ByteArray) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val cur = currentFile
            if (cur == null || (now - currentStartedAt) >= segmentMillis) {
                finalizeCurrent(now)
                openNewSegment(now)
            }
            try {
                val out = currentStream ?: return
                out.write(frame)
                currentFrames.incrementAndGet()
                currentBytes.addAndGet(frame.size.toLong())
                framesSinceFlush++
                if (framesSinceFlush >= flushEveryFrames) {
                    out.flush()
                    framesSinceFlush = 0
                }
            } catch (e: Exception) {
                Log.e(tag, "frame write failed: ${e.message}")
            }
        }
    }

    private fun openNewSegment(now: Long) {
        val dateKey = dateFmt.format(Date(now))
        val timeKey = timeFmt.format(Date(now))
        val dayDir = File(rootDir, dateKey).apply { mkdirs() }
        val file = File(dayDir, "$timeKey.opusraw")
        currentFile = file
        currentStream = FileOutputStream(file, /* append = */ true)
        currentDateKey = dateKey
        currentStartedAt = now
        currentFrames.set(0)
        currentBytes.set(0)
        framesSinceFlush = 0
        Log.i(tag, "new segment: ${file.absolutePath}")
    }

    /** Closes the current segment and inserts the DB row. Caller holds the mutex. */
    private suspend fun finalizeCurrent(endedAt: Long) {
        val file = currentFile ?: return
        try { currentStream?.flush(); currentStream?.close() } catch (_: Exception) {}
        val frames = currentFrames.get()
        val bytes = currentBytes.get()
        currentFile = null
        currentStream = null
        if (frames == 0 || bytes == 0L) {
            // Don't keep empty segments.
            file.delete()
            return
        }
        val seg = AudioSegment(
            path = file.absolutePath,
            date_key = currentDateKey,
            started_at = currentStartedAt,
            ended_at = endedAt,
            frame_count = frames,
            byte_count = bytes,
            posted_at = endedAt,  // optimistic; Phase B will set this from agent ack
        )
        try {
            dao.insert(seg)
        } catch (e: Exception) {
            Log.e(tag, "dao.insert failed: ${e.message}")
        }
    }

    /**
     * Force-close the current segment. Call from the foreground service's onDestroy
     * so a half-written file gets a DB row.
     */
    fun close() {
        scope.launch {
            mutex.withLock { finalizeCurrent(System.currentTimeMillis()) }
        }
    }

    /** Sweep files + DB rows older than the retention window. Safe to call from a worker. */
    suspend fun sweepOldSegments(): Int {
        val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 86_400_000L
        val old = dao.olderThan(cutoff)
        var deleted = 0
        val now = System.currentTimeMillis()
        for (seg in old) {
            val f = File(seg.path)
            if (f.exists()) {
                if (!f.delete()) {
                    Log.w(tag, "could not delete file: ${seg.path}")
                    continue
                }
            }
            dao.markDeleted(seg.id, now)
            deleted++
        }
        if (deleted > 0) Log.i(tag, "retention sweep deleted $deleted segments")
        return deleted
    }

    private fun uniqueStorageFile(dayDir: File, baseName: String): File {
        var suffix = 0
        while (true) {
            val suffixText = if (suffix == 0) "" else "_$suffix"
            val candidate = File(dayDir, "$baseName$suffixText.opusraw")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun writeStorageFrames(out: DataOutputStream, frames: List<ByteArray>) {
        out.write(STORAGE_FRAME_MAGIC)
        out.writeInt(STORAGE_FRAME_VERSION)
        out.writeInt(frames.size)
        for (frame in frames) {
            out.writeInt(frame.size)
            out.write(frame)
        }
    }

    private fun readStorageFrames(file: File, segmentId: Long, expectedFrames: Int): List<ByteArray>? {
        if (!file.exists()) {
            Log.w(tag, "pending storage file missing id=$segmentId path=${file.absolutePath}")
            return null
        }
        return try {
            val bytes = file.readBytes()
            if (!hasStorageFrameMagic(bytes)) {
                return readLegacyConcatenatedFrames(bytes, segmentId, expectedFrames)
            }

            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.skipBytes(STORAGE_FRAME_MAGIC.size)
                val version = input.readInt()
                if (version != STORAGE_FRAME_VERSION) {
                    Log.w(tag, "pending storage file has unsupported version id=$segmentId version=$version")
                    return null
                }

                val count = input.readInt()
                if (count <= 0 || count > MAX_REPLAY_FRAMES) {
                    Log.w(tag, "pending storage file has invalid frame count id=$segmentId count=$count")
                    return null
                }

                val frames = ArrayList<ByteArray>(count)
                repeat(count) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_REPLAY_FRAME_BYTES) {
                        throw IllegalStateException("invalid frame length $len")
                    }
                    val frame = ByteArray(len)
                    input.readFully(frame)
                    frames += frame
                }
                frames
            }
        } catch (e: EOFException) {
            Log.w(tag, "pending storage file truncated id=$segmentId path=${file.name}")
            null
        } catch (t: Throwable) {
            Log.w(tag, "pending storage file replay read failed id=$segmentId path=${file.name}: ${t.message}")
            null
        }
    }

    private fun hasStorageFrameMagic(bytes: ByteArray): Boolean =
        bytes.size >= STORAGE_FRAME_MAGIC.size &&
            STORAGE_FRAME_MAGIC.indices.all { bytes[it] == STORAGE_FRAME_MAGIC[it] }

    private fun readLegacyConcatenatedFrames(bytes: ByteArray, segmentId: Long, expectedFrames: Int): List<ByteArray>? {
        if (expectedFrames <= 0 || bytes.size < expectedFrames * (OMI_FRAME_HEADER_BYTES + 1)) {
            Log.w(tag, "legacy pending file has invalid shape id=$segmentId frames=$expectedFrames bytes=${bytes.size}")
            return null
        }

        val frames = ArrayList<ByteArray>(expectedFrames)
        var offset = 0
        var seq = readU16Le(bytes, 0)
        for (i in 0 until expectedFrames) {
            if (!legacyHeaderMatches(bytes, offset, seq)) {
                Log.w(tag, "legacy pending file header mismatch id=$segmentId frame=$i offset=$offset")
                return null
            }

            val nextOffset = if (i == expectedFrames - 1) {
                bytes.size
            } else {
                val nextSeq = (seq + 1) and 0xFFFF
                val nextNextSeq = (seq + 2) and 0xFFFF
                findLegacyBoundary(
                    bytes = bytes,
                    offset = offset,
                    nextSeq = nextSeq,
                    nextNextSeq = nextNextSeq,
                    requireLookahead = i < expectedFrames - 2,
                ) ?: run {
                    Log.w(tag, "legacy pending file boundary missing id=$segmentId frame=$i offset=$offset")
                    return null
                }
            }

            val len = nextOffset - offset
            if (len < OMI_FRAME_HEADER_BYTES + 1 || len > MAX_LEGACY_FRAME_BYTES) {
                Log.w(tag, "legacy pending file invalid frame length id=$segmentId frame=$i len=$len")
                return null
            }
            frames += bytes.copyOfRange(offset, nextOffset)
            offset = nextOffset
            if (offset < bytes.size) {
                seq = readU16Le(bytes, offset)
            }
        }

        if (offset != bytes.size) {
            Log.w(tag, "legacy pending file parse ended early id=$segmentId offset=$offset bytes=${bytes.size}")
            return null
        }
        Log.i(tag, "legacy pending file parsed id=$segmentId frames=${frames.size} bytes=${bytes.size}")
        return frames
    }

    private fun findLegacyBoundary(
        bytes: ByteArray,
        offset: Int,
        nextSeq: Int,
        nextNextSeq: Int,
        requireLookahead: Boolean,
    ): Int? {
        val start = offset + OMI_FRAME_HEADER_BYTES + 1
        val end = minOf(offset + MAX_LEGACY_FRAME_BYTES, bytes.size - OMI_FRAME_HEADER_BYTES)
        for (pos in start..end) {
            if (!legacyHeaderMatches(bytes, pos, nextSeq)) continue
            if (!requireLookahead || hasLegacyBoundaryLookahead(bytes, pos, nextNextSeq)) {
                return pos
            }
        }
        return null
    }

    private fun hasLegacyBoundaryLookahead(bytes: ByteArray, offset: Int, seq: Int): Boolean {
        val start = offset + OMI_FRAME_HEADER_BYTES + 1
        val end = minOf(offset + MAX_LEGACY_FRAME_BYTES, bytes.size - OMI_FRAME_HEADER_BYTES)
        for (pos in start..end) {
            if (legacyHeaderMatches(bytes, pos, seq)) return true
        }
        return false
    }

    private fun legacyHeaderMatches(bytes: ByteArray, offset: Int, seq: Int): Boolean {
        if (offset + OMI_FRAME_HEADER_BYTES > bytes.size) return false
        return (bytes[offset].toInt() and 0xFF) == (seq and 0xFF) &&
            (bytes[offset + 1].toInt() and 0xFF) == ((seq shr 8) and 0xFF) &&
            bytes[offset + 2].toInt() == 0
    }

    private fun readU16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun storageTimestampMillis(timestamp: Long, fallback: Long): Long =
        if (isReasonableStorageTimestamp(timestamp)) {
            timestamp * 1000L
        } else {
            fallback
        }

    private fun isReasonableStorageTimestamp(timestamp: Long): Boolean =
        timestamp in MIN_REASONABLE_EPOCH_SECONDS..MAX_REASONABLE_EPOCH_SECONDS

    /** Snapshot for the MainActivity status surface. */
    data class Status(
        val storedMs: Long,
        val storedBytes: Long,
        val queueCount: Int,
        val oldestStart: Long?,
        val newestEnd: Long?,
        val currentFrames: Int,
        val currentBytes: Long,
        val currentSegmentAgeMs: Long,
    )

    suspend fun status(): Status {
        val now = System.currentTimeMillis()
        return Status(
            storedMs = dao.totalDurationMs(),
            storedBytes = dao.totalBytes(),
            queueCount = dao.queueCount(),
            oldestStart = dao.oldestStart(),
            newestEnd = dao.newestEnd(),
            currentFrames = currentFrames.get(),
            currentBytes = currentBytes.get(),
            currentSegmentAgeMs = if (currentStartedAt > 0) now - currentStartedAt else 0L,
        )
    }

    private companion object {
        private const val OPUS_FRAME_DURATION_MS = 20L
        private val STORAGE_FRAME_MAGIC = byteArrayOf(0x4F, 0x4D, 0x49, 0x46, 0x52, 0x31) // OMIFR1
        private const val STORAGE_FRAME_VERSION = 1
        private const val OMI_FRAME_HEADER_BYTES = 3
        private const val MAX_LEGACY_FRAME_BYTES = 160
        private const val MAX_REPLAY_FRAMES = 250_000
        private const val MAX_REPLAY_FRAME_BYTES = 1024
        private const val MIN_REASONABLE_EPOCH_SECONDS = 1_500_000_000L
        private const val MAX_REASONABLE_EPOCH_SECONDS = 4_102_444_800L
    }
}
