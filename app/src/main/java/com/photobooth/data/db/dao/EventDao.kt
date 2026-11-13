package com.photobooth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.photobooth.data.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventEntity)

    @Update
    suspend fun update(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: Long): EventEntity?

    @Query("UPDATE events SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAll()

    @Query("UPDATE events SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Transaction
    suspend fun setActive(id: String) {
        deactivateAll()
        activate(id)
    }
}
