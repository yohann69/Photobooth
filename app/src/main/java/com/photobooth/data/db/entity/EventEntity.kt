package com.photobooth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.photobooth.domain.model.PhotoAspect

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val storageDirectoryUri: String,
    val isActive: Boolean,
    val countdownSeconds: Int = 5,
    val maxPrintsPerSession: Int = 5,
    val allowRetakes: Boolean = true,
    val enableDigitalQRShare: Boolean = true,
    /** Unused: the boomerang feature was removed; the column stays to avoid rebuilding the events table. */
    val enableGifBoomerang: Boolean = true,
    val mirrorLivePreview: Boolean = true,
    /** Day of the event as UTC midnight; sent with every upload. */
    @ColumnInfo(defaultValue = "0") val eventDate: Long = 0,
    /** Ken Burns slideshow of this event's recent photos behind the welcome screen. */
    @ColumnInfo(defaultValue = "1") val attractSlideshow: Boolean = true,
    /** Native ratio of the event's camera; layout slots are generated with it. */
    @ColumnInfo(defaultValue = "'RATIO_4_3'") val photoAspect: PhotoAspect = PhotoAspect.RATIO_4_3,
)
