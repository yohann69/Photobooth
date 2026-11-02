package com.photobooth.domain.layout

import com.photobooth.domain.model.PhotoSlot

private const val MIN_SLOT_FRACTION = 0.02f

/**
 * Shrinks every slot around its centre so its width loses [paddingPx] on each side (the height shrinks
 * proportionally, keeping the 19:9 / 1:1 aspect), then shifts it by the offsets. Sizes are in canvas pixels.
 */
fun List<PhotoSlot>.withCalibration(
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    paddingPx: Int,
    offsetXPx: Int,
    offsetYPx: Int,
): List<PhotoSlot> {
    if (paddingPx == 0 && offsetXPx == 0 && offsetYPx == 0) return this
    return map { s ->
        val widthPx = s.width * canvasWidthPx
        val scale = ((widthPx - 2f * paddingPx) / widthPx).coerceAtLeast(MIN_SLOT_FRACTION / s.width)
        val width = s.width * scale
        val height = s.height * scale
        val centerX = s.x + s.width / 2f + offsetXPx.toFloat() / canvasWidthPx
        val centerY = s.y + s.height / 2f + offsetYPx.toFloat() / canvasHeightPx
        s.copy(x = centerX - width / 2f, y = centerY - height / 2f, width = width, height = height)
    }
}
