package com.connor.pendant.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One finalized 60-second audio segment on disk.
 *
 * Files live at `${context.filesDir}/audio/<date_key>/<HHMMSS>.opusraw` —
 * raw Omi-framed Opus bytes (3-byte header + Opus payload, no Ogg wrapping yet).
 *
 * `posted_at` is set when the segment's frames are believed to have reached the
 * agent. In M2.5 Phase A this is set immediately if the foreground service's
 * uploader has been healthy during the segment; Phase B will use per-segment
 * ack from the agent.
 */
@Entity(
    tableName = "audio_segments",
    indices = [
        Index("started_at"),
        Index("posted_at"),
        Index("deleted_at"),
    ]
)
data class AudioSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val date_key: String,
    val started_at: Long,
    val ended_at: Long,
    val frame_count: Int,
    val byte_count: Long,
    val posted_at: Long? = null,
    val deleted_at: Long? = null,
) {
    val duration_ms: Long get() = (ended_at - started_at).coerceAtLeast(0)
}
