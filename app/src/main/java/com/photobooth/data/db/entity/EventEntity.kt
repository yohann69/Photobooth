package com.photobooth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.photobooth.domain.model.PhotoAspect
import com.photobooth.domain.model.PhotoFilterType

/** Art-direction defaults, shared by the entity column defaults, the migration and the theme editor. */
object EventThemeDefaults {
    const val PRIMARY = "#FF2E7E"
    const val SECONDARY = "#00E5FF"
    const val BACKGROUND = "#07070D"
    const val TEXT = "#FFFFFF"
    val FILTERS: List<PhotoFilterType> = PhotoFilterType.ALL
    const val FILTERS_SQL = "ORIGINAL,BLACK_AND_WHITE,SEPIA,VINTAGE,GLAM"
}

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
    /** Buttons, active borders, countdown highlights. */
    @ColumnInfo(defaultValue = "'${EventThemeDefaults.PRIMARY}'") val primaryColorHex: String = EventThemeDefaults.PRIMARY,
    /** Accents and badges. */
    @ColumnInfo(defaultValue = "'${EventThemeDefaults.SECONDARY}'") val secondaryColorHex: String = EventThemeDefaults.SECONDARY,
    /** Solid fallback behind every guest screen. */
    @ColumnInfo(defaultValue = "'${EventThemeDefaults.BACKGROUND}'") val backgroundColorHex: String = EventThemeDefaults.BACKGROUND,
    /** Primary text and icon tint. */
    @ColumnInfo(defaultValue = "'${EventThemeDefaults.TEXT}'") val textColorHex: String = EventThemeDefaults.TEXT,
    /** Fullscreen image behind all guest screens, stored in this event's folder. */
    val customBackgroundImagePath: String? = null,
    /** Looks the guest may pick from; empty or only ORIGINAL hides the filter bar. */
    @ColumnInfo(defaultValue = "'${EventThemeDefaults.FILTERS_SQL}'") val enabledFilters: List<PhotoFilterType> = EventThemeDefaults.FILTERS,
    /** Native ratio of the event's camera; layout slots are generated with it. */
    @ColumnInfo(defaultValue = "'RATIO_4_3'") val photoAspect: PhotoAspect = PhotoAspect.RATIO_4_3,
)
