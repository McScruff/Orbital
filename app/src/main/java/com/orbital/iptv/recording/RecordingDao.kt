package com.orbital.iptv.recording

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert suspend fun insert(r: RecordingEntity): Long
    @Update suspend fun update(r: RecordingEntity)
    @Delete suspend fun delete(r: RecordingEntity)

    @Query("SELECT * FROM recordings ORDER BY scheduledStart DESC")
    fun allFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Int): RecordingEntity?

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: RecordingStatus)

    @Query("UPDATE recordings SET status = :status, filePath = :path WHERE id = :id")
    suspend fun updateStatusAndPath(id: Int, status: RecordingStatus, path: String)
}
