package com.photobooth.data.repository

import com.photobooth.data.db.dao.SessionLogDao
import com.photobooth.data.db.entity.SessionLogEntity
import com.photobooth.data.db.entity.SessionOutcome
import com.photobooth.domain.model.LayoutType
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionLogRepository @Inject constructor(
    private val dao: SessionLogDao,
) {
    suspend fun start(eventId: String, layoutType: LayoutType?): SessionLogEntity {
        val session = SessionLogEntity(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            startedAt = System.currentTimeMillis(),
            layoutType = layoutType,
        )
        dao.upsert(session)
        return session
    }

    suspend fun update(session: SessionLogEntity) = dao.update(session)

    suspend fun finish(session: SessionLogEntity, outcome: SessionOutcome, error: String? = null) =
        dao.update(session.copy(endedAt = System.currentTimeMillis(), outcome = outcome, errorMessage = error))

    /** Marks sessions left IN_PROGRESS by a crash or reboot as abandoned. */
    suspend fun closeDanglingSessions(): Int = dao.closeDanglingSessions(System.currentTimeMillis())

    fun observeRecent(eventId: String, limit: Int = 50): Flow<List<SessionLogEntity>> =
        dao.observeRecent(eventId, limit)

    fun observeCount(eventId: String): Flow<Int> = dao.observeCount(eventId)

    fun observePhotoTotal(eventId: String): Flow<Int> = dao.observePhotoTotal(eventId)
}
