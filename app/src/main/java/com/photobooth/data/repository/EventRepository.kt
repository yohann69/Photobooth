package com.photobooth.data.repository

import androidx.room.withTransaction
import androidx.core.net.toUri
import com.photobooth.data.db.PhotoboothDatabase
import com.photobooth.data.db.dao.EventDao
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.storage.EventStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val db: PhotoboothDatabase,
    private val dao: EventDao,
    private val layouts: LayoutRepository,
    private val storage: EventStorage,
) {
    fun observeAll(): Flow<List<EventEntity>> = dao.observeAll()

    fun observeActive(): Flow<EventEntity?> = dao.observeActive()

    suspend fun getActive(): EventEntity? = dao.getActive()

    suspend fun getAll(): List<EventEntity> = dao.getAll()

    suspend fun count(): Int = dao.count()

    suspend fun update(event: EventEntity) = dao.update(event)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun setActive(id: String) = dao.setActive(id)

    /** Creates the event's folder tree under Pictures/Photobooth and the event itself. */
    suspend fun createWithStorage(name: String, eventDate: Long, makeActive: Boolean = true): EventEntity {
        val id = UUID.randomUUID().toString()
        val dir = withContext(Dispatchers.IO) { storage.createEventDirectory(id, name) }
        return createEvent(name, dir.toUri().toString(), makeActive, id = id, eventDate = eventDate)
    }

    /**
     * Creates an event with clean default layout presets (no frame, no artwork carried over from any other event);
     * optionally makes it the active one.
     */
    suspend fun createEvent(
        name: String,
        storageDirectoryUri: String,
        makeActive: Boolean = true,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        eventDate: Long = 0,
    ): EventEntity = db.withTransaction {
        val event = EventEntity(
            id = id,
            name = name,
            createdAt = now,
            eventDate = eventDate,
            storageDirectoryUri = storageDirectoryUri,
            isActive = false,
        )
        dao.insert(event)
        layouts.ensureDefaults(event.id, event.photoAspect)
        if (makeActive) dao.setActive(event.id)
        event.copy(isActive = makeActive)
    }
}
