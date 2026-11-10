package com.photobooth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.photobooth.data.db.entity.PrintJobEntity
import com.photobooth.data.db.entity.PrintJobStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintJobDao {
    @Insert
    suspend fun insert(job: PrintJobEntity): Long

    @Update
    suspend fun update(job: PrintJobEntity)

    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextWithStatus(status: PrintJobStatus): PrintJobEntity?

    @Query("SELECT * FROM print_jobs WHERE status IN ('QUEUED', 'PRINTING') ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextRunnable(): PrintJobEntity?

    @Query("SELECT * FROM print_jobs WHERE status IN ('QUEUED', 'PRINTING') ORDER BY createdAt ASC")
    fun observePending(): Flow<List<PrintJobEntity>>

    @Query("UPDATE print_jobs SET status = 'CANCELLED', updatedAt = :now WHERE id = :id AND status IN ('QUEUED', 'PRINTING')")
    suspend fun cancel(id: Long, now: Long): Int

    @Query("UPDATE print_jobs SET status = 'CANCELLED', updatedAt = :now WHERE status IN ('QUEUED', 'PRINTING')")
    suspend fun cancelAll(now: Long): Int

    @Query("SELECT * FROM print_jobs WHERE eventId = :eventId ORDER BY createdAt DESC")
    fun observeForEvent(eventId: String): Flow<List<PrintJobEntity>>

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status IN (:statuses)")
    fun observeCount(statuses: List<PrintJobStatus>): Flow<Int>

    @Query("UPDATE print_jobs SET status = :to, updatedAt = :now WHERE status = :from")
    suspend fun requeue(from: PrintJobStatus, to: PrintJobStatus, now: Long): Int

    @Query("DELETE FROM print_jobs WHERE status = 'DONE' AND updatedAt < :before")
    suspend fun purgeCompleted(before: Long): Int

    @Query("SELECT eventId, COALESCE(SUM(copies), 0) AS total FROM print_jobs WHERE status = 'DONE' GROUP BY eventId")
    fun observePrintTotals(): Flow<List<EventPrintTotal>>

    @Query("SELECT COALESCE(SUM(copies), 0) FROM print_jobs WHERE status = 'DONE'")
    suspend fun totalPrinted(): Int
}

data class EventPrintTotal(val eventId: String, val total: Int)
