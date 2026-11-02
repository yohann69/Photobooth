package com.photobooth.data.repository

import com.photobooth.data.db.dao.EventPrintTotal
import com.photobooth.data.db.dao.PrintJobDao
import com.photobooth.data.db.entity.PrintFinish
import com.photobooth.data.db.entity.PrintJobEntity
import com.photobooth.data.db.entity.PrintJobStatus
import com.photobooth.domain.model.LayoutType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrintQueueRepository @Inject constructor(
    private val dao: PrintJobDao,
) {
    suspend fun enqueue(
        eventId: String,
        sessionId: String?,
        imagePath: String,
        copies: Int,
        layoutType: LayoutType?,
        finish: PrintFinish,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            PrintJobEntity(
                eventId = eventId,
                sessionId = sessionId,
                imagePath = imagePath,
                copies = copies,
                layoutType = layoutType,
                finish = finish,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun nextRunnable(): PrintJobEntity? = dao.nextRunnable()

    fun observePending(): Flow<List<PrintJobEntity>> = dao.observePending()

    suspend fun markPrinting(job: PrintJobEntity) = dao.update(
        job.copy(status = PrintJobStatus.PRINTING, attempts = job.attempts + 1, updatedAt = System.currentTimeMillis()),
    )

    /** Persists sheets already printed so a resume sends only what is left. */
    suspend fun recordProgress(job: PrintJobEntity, printed: Int): PrintJobEntity {
        val updated = job.copy(copiesPrinted = printed.coerceIn(0, job.copies), updatedAt = System.currentTimeMillis())
        dao.update(updated)
        return updated
    }

    suspend fun markDone(job: PrintJobEntity) = dao.update(
        job.copy(status = PrintJobStatus.DONE, copiesPrinted = job.copies, lastError = null, updatedAt = System.currentTimeMillis()),
    )

    suspend fun markSimulated(job: PrintJobEntity) = dao.update(
        job.copy(status = PrintJobStatus.SIMULATED, lastError = null, updatedAt = System.currentTimeMillis()),
    )

    /** Back to the queue (paper out, printer offline…) keeping the progress made so far. */
    suspend fun markWaiting(job: PrintJobEntity, reason: String) = dao.update(
        job.copy(status = PrintJobStatus.QUEUED, lastError = reason, updatedAt = System.currentTimeMillis()),
    )

    suspend fun markFailed(job: PrintJobEntity, error: String) = dao.update(
        job.copy(status = PrintJobStatus.FAILED, lastError = error, updatedAt = System.currentTimeMillis()),
    )

    /** Jobs left PRINTING by a crash or power loss go back to the queue. */
    suspend fun recoverInterrupted(): Int =
        dao.requeue(PrintJobStatus.PRINTING, PrintJobStatus.QUEUED, System.currentTimeMillis())

    fun observeForEvent(eventId: String): Flow<List<PrintJobEntity>> = dao.observeForEvent(eventId)

    fun observePendingCount(): Flow<Int> = dao.observeCount(listOf(PrintJobStatus.QUEUED, PrintJobStatus.PRINTING))

    suspend fun purgeCompletedOlderThan(before: Long): Int = dao.purgeCompleted(before)

    fun observePrintTotals(): Flow<List<EventPrintTotal>> = dao.observePrintTotals()

    suspend fun totalPrinted(): Int = dao.totalPrinted()
}
