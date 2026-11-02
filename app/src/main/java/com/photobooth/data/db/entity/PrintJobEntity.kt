package com.photobooth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photobooth.domain.model.LayoutType

/** SIMULATED = completed in dry-run mode; it never counts against the paper roll. */
enum class PrintJobStatus { QUEUED, PRINTING, DONE, FAILED, CANCELLED, SIMULATED }

enum class PrintFinish { GLOSSY, MATTE }

@Entity(
    tableName = "print_jobs",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId"), Index("status")],
)
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val sessionId: String?,
    val imagePath: String,
    /** Physical 10x15 cm sheets to print. */
    val copies: Int = 1,
    val status: PrintJobStatus = PrintJobStatus.QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val layoutType: LayoutType? = null,
    @ColumnInfo(defaultValue = "'GLOSSY'") val finish: PrintFinish = PrintFinish.GLOSSY,
    @ColumnInfo(defaultValue = "0") val copiesPrinted: Int = 0,
) {
    val copiesRemaining: Int get() = (copies - copiesPrinted).coerceAtLeast(0)
}
