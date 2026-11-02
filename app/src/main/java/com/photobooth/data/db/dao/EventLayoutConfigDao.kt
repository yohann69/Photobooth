package com.photobooth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.model.LayoutType
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLayoutConfigDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(configs: List<EventLayoutConfigEntity>)

    @Update
    suspend fun update(config: EventLayoutConfigEntity)

    @Query("SELECT * FROM event_layout_configs WHERE eventId = :eventId ORDER BY rowid")
    fun observeForEvent(eventId: String): Flow<List<EventLayoutConfigEntity>>

    @Query("SELECT * FROM event_layout_configs WHERE eventId = :eventId AND isEnabled = 1 ORDER BY rowid")
    fun observeEnabledForEvent(eventId: String): Flow<List<EventLayoutConfigEntity>>

    @Query("SELECT * FROM event_layout_configs WHERE eventId = :eventId ORDER BY rowid")
    suspend fun getForEvent(eventId: String): List<EventLayoutConfigEntity>

    @Query("SELECT * FROM event_layout_configs WHERE eventId = :eventId AND layoutType = :type")
    suspend fun get(eventId: String, type: LayoutType): EventLayoutConfigEntity?

    @Query("SELECT layoutType FROM event_layout_configs WHERE eventId = :eventId")
    suspend fun getTypes(eventId: String): List<LayoutType>

    @Query("UPDATE event_layout_configs SET isEnabled = :enabled WHERE eventId = :eventId AND layoutType = :type")
    suspend fun setEnabled(eventId: String, type: LayoutType, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM event_layout_configs")
    suspend fun count(): Int
}
