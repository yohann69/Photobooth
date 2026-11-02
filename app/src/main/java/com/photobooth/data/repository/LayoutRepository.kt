package com.photobooth.data.repository

import com.photobooth.data.db.dao.EventLayoutConfigDao
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.PhotoAspect
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(
    private val dao: EventLayoutConfigDao,
) {
    fun observeForEvent(eventId: String): Flow<List<EventLayoutConfigEntity>> = dao.observeForEvent(eventId)

    fun observeEnabledForEvent(eventId: String): Flow<List<EventLayoutConfigEntity>> =
        dao.observeEnabledForEvent(eventId)

    suspend fun getForEvent(eventId: String): List<EventLayoutConfigEntity> = dao.getForEvent(eventId)

    suspend fun get(eventId: String, type: LayoutType): EventLayoutConfigEntity? = dao.get(eventId, type)

    suspend fun update(config: EventLayoutConfigEntity) = dao.update(config)

    suspend fun setEnabled(eventId: String, type: LayoutType, enabled: Boolean) =
        dao.setEnabled(eventId, type, enabled)

    /** Inserts default presets for any [LayoutType] the event does not have yet. Returns how many were added. */
    suspend fun ensureDefaults(eventId: String, aspect: PhotoAspect = PhotoAspect.RATIO_4_3): Int {
        val existing = dao.getTypes(eventId).toSet()
        val missing = DefaultLayoutPresets.all(aspect)
            .filter { it.type !in existing }
            .map {
                EventLayoutConfigEntity(
                    eventId = eventId,
                    layoutType = it.type,
                    isEnabled = true,
                    orientation = it.orientation,
                    canvasWidthPx = it.canvasWidthPx,
                    canvasHeightPx = it.canvasHeightPx,
                    photoSlots = it.slots,
                )
            }
        if (missing.isNotEmpty()) dao.insertAllIgnore(missing)
        return missing.size
    }

    suspend fun setFramePath(eventId: String, type: LayoutType, path: String?) {
        val current = dao.get(eventId, type) ?: return
        dao.update(current.copy(customFramePngPath = path))
    }

    suspend fun setBackgroundColor(eventId: String, type: LayoutType, hex: String) {
        val current = dao.get(eventId, type) ?: return
        dao.update(current.copy(backgroundColorHex = hex))
    }

    suspend fun setCalibration(eventId: String, type: LayoutType, paddingPx: Int, offsetXPx: Int, offsetYPx: Int) {
        val current = dao.get(eventId, type) ?: return
        dao.update(current.copy(slotPaddingPx = paddingPx, slotOffsetXPx = offsetXPx, slotOffsetYPx = offsetYPx))
    }

    /** Rebuilds every layout's slots for [aspect], keeping frames, backgrounds and calibration. */
    suspend fun applyAspect(eventId: String, aspect: PhotoAspect) {
        dao.getForEvent(eventId).forEach { current ->
            val preset = DefaultLayoutPresets.forType(current.layoutType, aspect)
            dao.update(current.copy(canvasWidthPx = preset.canvasWidthPx, canvasHeightPx = preset.canvasHeightPx, photoSlots = preset.slots))
        }
    }
}
