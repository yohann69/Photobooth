package com.photobooth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.photobooth.domain.layout.withCalibration
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.Orientation
import com.photobooth.domain.model.PhotoSlot

@Entity(
    tableName = "event_layout_configs",
    primaryKeys = ["eventId", "layoutType"],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventLayoutConfigEntity(
    val eventId: String,
    val layoutType: LayoutType,
    val isEnabled: Boolean,
    val orientation: Orientation,
    /** Transparent PNG at canvas resolution, stored in the owning event's Frames folder and drawn behind the photos. */
    val customFramePngPath: String? = null,
    val backgroundImagePath: String? = null,
    val backgroundColorHex: String = "#FFFFFF",
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val photoSlots: List<PhotoSlot>,
    /** Calibration applied on top of [photoSlots]: uniform inset and a shift, in canvas pixels. */
    @ColumnInfo(defaultValue = "0") val slotPaddingPx: Int = 0,
    @ColumnInfo(defaultValue = "0") val slotOffsetXPx: Int = 0,
    @ColumnInfo(defaultValue = "0") val slotOffsetYPx: Int = 0,
) {
    /** One slot per shot the guest takes (the first slot showing it); previews use its shape. */
    fun captureSlots(): List<PhotoSlot> {
        val bySource = java.util.TreeMap<Int, PhotoSlot>()
        effectiveSlots().forEachIndexed { i, slot -> bySource.putIfAbsent(slot.sourceIndex ?: i, slot) }
        return bySource.values.toList()
    }

    val photoCount: Int get() = photoSlots.withIndex().map { (i, slot) -> slot.sourceIndex ?: i }.distinct().size

    /** Slots with calibration applied; this is what previews and the compositor must use. */
    fun effectiveSlots(): List<PhotoSlot> =
        photoSlots.withCalibration(canvasWidthPx, canvasHeightPx, slotPaddingPx, slotOffsetXPx, slotOffsetYPx)
}
