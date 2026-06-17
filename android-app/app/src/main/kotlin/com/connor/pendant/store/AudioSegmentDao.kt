package com.connor.pendant.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AudioSegmentDao {

    @Insert suspend fun insert(seg: AudioSegment): Long

    @Update suspend fun update(seg: AudioSegment)

    @Query("SELECT * FROM audio_segments WHERE id = :id")
    suspend fun get(id: Long): AudioSegment?

    @Query(
        "SELECT * FROM audio_segments " +
        "WHERE posted_at IS NULL AND deleted_at IS NULL " +
        "ORDER BY started_at ASC LIMIT :limit"
    )
    suspend fun unposted(limit: Int = 50): List<AudioSegment>

    @Query("SELECT * FROM audio_segments WHERE deleted_at IS NULL AND started_at < :cutoffMs")
    suspend fun olderThan(cutoffMs: Long): List<AudioSegment>

    @Query("SELECT COUNT(*) FROM audio_segments WHERE posted_at IS NULL AND deleted_at IS NULL")
    suspend fun queueCount(): Int

    @Query("SELECT COALESCE(SUM(byte_count), 0) FROM audio_segments WHERE deleted_at IS NULL")
    suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(SUM(ended_at - started_at), 0) FROM audio_segments WHERE deleted_at IS NULL")
    suspend fun totalDurationMs(): Long

    @Query("SELECT MIN(started_at) FROM audio_segments WHERE deleted_at IS NULL")
    suspend fun oldestStart(): Long?

    @Query("SELECT MAX(ended_at) FROM audio_segments WHERE deleted_at IS NULL")
    suspend fun newestEnd(): Long?

    @Query("UPDATE audio_segments SET posted_at = :ts WHERE id = :id")
    suspend fun markPosted(id: Long, ts: Long)

    @Query("UPDATE audio_segments SET deleted_at = :ts WHERE id = :id")
    suspend fun markDeleted(id: Long, ts: Long)
}
