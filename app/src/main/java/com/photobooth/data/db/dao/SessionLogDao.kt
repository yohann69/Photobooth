package com.photobooth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.photobooth.data.db.entity.SessionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionLogEntity)

    @Update
    suspend fun update(session: SessionLogEntity)

    @Query("SELECT * FROM session_logs WHERE id = :id")
    suspend fun getById(id: String): SessionLogEntity?

    @Query("SELECT * FROM session_logs WHERE eventId = :eventId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(eventId: String, limit: Int): Flow<List<SessionLogEntity>>

    @Query("SELECT COUNT(*) FROM session_logs WHERE eventId = :eventId")
    fun observeCount(eventId: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(photosCaptured), 0) FROM session_logs WHERE eventId = :eventId")
    fun observePhotoTotal(eventId: String): Flow<Int>

    @Query("UPDATE session_logs SET outcome = 'ABANDONED', endedAt = :now WHERE outcome = 'IN_PROGRESS'")
    suspend fun closeDanglingSessions(now: Long): Int
}
