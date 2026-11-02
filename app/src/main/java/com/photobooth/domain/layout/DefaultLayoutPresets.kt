package com.photobooth.domain.layout

import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.Orientation
import com.photobooth.domain.model.PhotoAspect
import com.photobooth.domain.model.PhotoSlot
import kotlin.math.min
import kotlin.math.roundToInt

/** Canvas-pixel rectangle reserved for event logos, text and frame artwork; no photo slot touches it. */
data class SafeZone(val x: Int, val y: Int, val width: Int, val height: Int)

data class LayoutPreset(
    val type: LayoutType,
    val orientation: Orientation,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val slots: List<PhotoSlot>,
    val safeZone: SafeZone,
) {
    val safeZoneFraction: Float get() = safeZone.width.toFloat() * safeZone.height / (canvasWidthPx.toFloat() * canvasHeightPx)
}

/**
 * Slots take the event's [PhotoAspect] (the camera's native ratio, so nothing is cropped), except the square
 * mosaic. Every layout keeps a [SafeZone] free of photos for logos and artwork.
 */
object DefaultLayoutPresets {
    private const val MARGIN = 30
    private const val GAP = 30

    private enum class Side { HEADER, FOOTER, RIGHT }

    private sealed interface Plan {
        val side: Side
        val fraction: Float
    }

    /** [rows] x [cols] equal slots; with [repeatColumns] every column shows the same shots (row r = shot r). */
    private class Grid(
        override val side: Side, override val fraction: Float,
        val rows: Int, val cols: Int, val square: Boolean = false, val repeatColumns: Boolean = false,
    ) : Plan

    /** One big slot on top and two half-width slots below. */
    private class HeroPlusTwo(override val side: Side, override val fraction: Float) : Plan

    private fun plan(type: LayoutType): Plan = when (type) {
        LayoutType.LANDSCAPE_SINGLE -> Grid(Side.RIGHT, 0.25f, 1, 1)
        LayoutType.PORTRAIT_SINGLE -> Grid(Side.FOOTER, 0.45f, 1, 1)
        LayoutType.LANDSCAPE_DOUBLE -> Grid(Side.FOOTER, 0.25f, 1, 2)
        LayoutType.PORTRAIT_TRIPLE -> HeroPlusTwo(Side.FOOTER, 0.22f)
        LayoutType.GRID_2X2 -> Grid(Side.RIGHT, 0.25f, 2, 2)
        LayoutType.MOSAIC_6_SQUARE -> Grid(Side.FOOTER, 0.20f, 3, 2, square = true, repeatColumns = true)
    }

    fun all(aspect: PhotoAspect = PhotoAspect.RATIO_4_3): List<LayoutPreset> = LayoutType.entries.map { forType(it, aspect) }

    fun forType(type: LayoutType, aspect: PhotoAspect = PhotoAspect.RATIO_4_3): LayoutPreset {
        val orientation = type.defaultOrientation
        val canvasW = orientation.canvasWidthPx
        val canvasH = orientation.canvasHeightPx
        val plan = plan(type)

        val zone: SafeZone
        val region: SafeZone
        when (plan.side) {
            Side.FOOTER -> {
                val zoneH = (canvasH * plan.fraction).roundToInt()
                zone = SafeZone(0, canvasH - zoneH, canvasW, zoneH)
                region = SafeZone(0, 0, canvasW, canvasH - zoneH)
            }
            Side.HEADER -> {
                val zoneH = (canvasH * plan.fraction).roundToInt()
                zone = SafeZone(0, 0, canvasW, zoneH)
                region = SafeZone(0, zoneH, canvasW, canvasH - zoneH)
            }
            Side.RIGHT -> {
                val zoneW = (canvasW * plan.fraction).roundToInt()
                zone = SafeZone(canvasW - zoneW, 0, zoneW, canvasH)
                region = SafeZone(0, 0, canvasW - zoneW, canvasH)
            }
        }
        val innerW = region.width - 2 * MARGIN
        val innerH = region.height - 2 * MARGIN

        // Each rect is (x, y, w, h, shot) in canvas pixels, before centring inside the region.
        class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val ratio: Float, val source: Int?)
        val rects: List<Rect>
        val blockW: Int
        val blockH: Int
        when (plan) {
            is Grid -> {
                val ratio = if (plan.square) 1f else aspect.ratio
                val w = min((innerW - (plan.cols - 1) * GAP) / plan.cols, (((innerH - (plan.rows - 1) * GAP) / plan.rows) * ratio).toInt())
                val h = (w / ratio).roundToInt()
                blockW = plan.cols * w + (plan.cols - 1) * GAP
                blockH = plan.rows * h + (plan.rows - 1) * GAP
                rects = List(plan.rows * plan.cols) { i ->
                    val row = i / plan.cols
                    val col = i % plan.cols
                    Rect(col * (w + GAP), row * (h + GAP), w, h, ratio, if (plan.repeatColumns) row else null)
                }
            }
            is HeroPlusTwo -> {
                val ratio = aspect.ratio
                // Widest big slot whose height plus the half-width row below still fits the region.
                val width = min(innerW.toFloat(), (innerH - GAP + GAP / (2f * ratio)) * ratio / 1.5f)
                val bigW = width.toInt()
                val bigH = (bigW / ratio).roundToInt()
                val smallW = (bigW - GAP) / 2
                val smallH = (smallW / ratio).roundToInt()
                blockW = bigW
                blockH = bigH + GAP + smallH
                rects = listOf(
                    Rect(0, 0, bigW, bigH, ratio, null),
                    Rect(0, bigH + GAP, smallW, smallH, ratio, null),
                    Rect(smallW + GAP, bigH + GAP, smallW, smallH, ratio, null),
                )
            }
        }
        val originX = region.x + (region.width - blockW) / 2
        val originY = region.y + (region.height - blockH) / 2

        val slots = rects.map { r ->
            PhotoSlot(
                x = (originX + r.x).toFloat() / canvasW,
                y = (originY + r.y).toFloat() / canvasH,
                width = r.w.toFloat() / canvasW,
                height = r.h.toFloat() / canvasH,
                rotation = 0f,
                aspectRatio = r.ratio,
                sourceIndex = r.source,
            )
        }
        check(slots.size == type.slotCount) { "$type expects ${type.slotCount} slots, got ${slots.size}" }
        return LayoutPreset(type, orientation, canvasW, canvasH, slots, zone)
    }
}
