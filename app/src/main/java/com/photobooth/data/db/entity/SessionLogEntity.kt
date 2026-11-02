package com.photobooth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photobooth.domain.model.LayoutType

enum class SessionOutcome { IN_PROGRESS, COMPLETED, ABANDONED, ERROR }

@Entity(
    tableName = "session_logs",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId"), Index("startedAt")],
)
data class SessionLogEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val layoutType: LayoutType? = null,
    val photosCaptured: Int = 0,
    val retakes: Int = 0,
    val printsRequested: Int = 0,
    val sharedViaQr: Boolean = false,
    val outcome: SessionOutcome = SessionOutcome.IN_PROGRESS,
    val errorMessage: String? = null,
)
