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
        /** White border around the photos; the first layouts keep 30 px so their existing frames still fit. */
        val margin: Int get() = MARGIN
    }

    /** Border of the layouts added later: 4 mm, clear of the ~2 mm the printer may trim. */
    private const val WIDE_MARGIN = 48

    /** [rows] x [cols] equal slots; with [repeatColumns] every column shows the same shots (row r = shot r). */
    private class Grid(
        override val side: Side, override val fraction: Float,
        val rows: Int, val cols: Int, val square: Boolean = false, val repeatColumns: Boolean = false,
        override val margin: Int = MARGIN,
    ) : Plan

    /** One big slot on top and a row of [count] equal slots below, together as wide as the big one. */
    private class HeroRow(
        override val side: Side, override val fraction: Float, val count: Int, override val margin: Int = MARGIN,
    ) : Plan

    /** One big slot on the left and two stacked slots on the right, as tall together as the big one. */
    private class HeroSide(override val side: Side, override val fraction: Float) : Plan {
        override val margin = WIDE_MARGIN
    }

    /**
     * Two identical strips, each centred in its half of the sheet so a cut down the middle gives two 2x6 in strips;
     * every strip has its own title area at the bottom (the safe zone spans both).
     */
    private class Strips(override val fraction: Float, val rows: Int) : Plan {
        override val side = Side.FOOTER
        override val margin = WIDE_MARGIN
    }

    private fun plan(type: LayoutType): Plan = when (type) {
        LayoutType.LANDSCAPE_SINGLE -> Grid(Side.RIGHT, 0.25f, 1, 1)
        LayoutType.PORTRAIT_SINGLE -> Grid(Side.FOOTER, 0.45f, 1, 1)
        LayoutType.LANDSCAPE_DOUBLE -> Grid(Side.FOOTER, 0.25f, 1, 2)
        LayoutType.PORTRAIT_TRIPLE -> HeroRow(Side.FOOTER, 0.22f, count = 2)
        LayoutType.GRID_2X2 -> Grid(Side.RIGHT, 0.25f, 2, 2)
        LayoutType.MOSAIC_6_SQUARE -> Grid(Side.FOOTER, 0.20f, 3, 2, square = true, repeatColumns = true)
        LayoutType.LANDSCAPE_BANNER -> Grid(Side.FOOTER, 0.14f, 1, 1, margin = WIDE_MARGIN)
        LayoutType.PORTRAIT_DUO -> Grid(Side.FOOTER, 0.20f, 2, 1, margin = WIDE_MARGIN)
        LayoutType.LANDSCAPE_HERO_SIDE -> HeroSide(Side.FOOTER, 0.20f)
        LayoutType.PORTRAIT_HERO_THREE -> HeroRow(Side.FOOTER, 0.25f, count = 3, margin = WIDE_MARGIN)
        LayoutType.STRIP_DOUBLE_3 -> Strips(0.20f, rows = 3)
        LayoutType.STRIP_DOUBLE_4 -> Strips(0.14f, rows = 4)
    }

    /** Smallest share of the sheet each layout leaves free for artwork; banners keep only a title band. */
    fun minSafeZoneFraction(type: LayoutType): Float = when (type) {
        LayoutType.LANDSCAPE_BANNER, LayoutType.STRIP_DOUBLE_4 -> 0.12f
        else -> 0.2f
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
        val innerW = region.width - 2 * plan.margin
        val innerH = region.height - 2 * plan.margin

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
            is HeroSide -> {
                val ratio = aspect.ratio
                // bigH = 2 smallH + GAP and the row is bigW + GAP + smallW wide: the widest that fits the region.
                fun widthFor(bigH: Float) = bigH * ratio + GAP + ((bigH - GAP) / 2f) * ratio
                var bigH = innerH.toFloat()
                if (widthFor(bigH) > innerW) bigH = (innerW - GAP + GAP * ratio / 2f) / (1.5f * ratio)
                val bigHi = bigH.toInt()
                val bigW = (bigHi * ratio).roundToInt()
                val smallH = (bigHi - GAP) / 2
                val smallW = (smallH * ratio).roundToInt()
                blockW = bigW + GAP + smallW
                blockH = bigHi
                rects = listOf(
                    Rect(0, 0, bigW, bigHi, ratio, null),
                    Rect(bigW + GAP, 0, smallW, smallH, ratio, null),
                    Rect(bigW + GAP, smallH + GAP, smallW, smallH, ratio, null),
                )
            }
            is Strips -> {
                val ratio = aspect.ratio
                val half = region.width / 2
                val w = min(half - 2 * plan.margin, (((innerH - (plan.rows - 1) * GAP) / plan.rows) * ratio).toInt())
                val h = (w / ratio).roundToInt()
                blockW = region.width
                blockH = plan.rows * h + (plan.rows - 1) * GAP
                rects = List(plan.rows * 2) { i ->
                    val row = i / 2
                    val col = i % 2
                    // Each column centred in its own half of the sheet, whatever the gap between the strips.
                    Rect(col * half + (half - w) / 2, row * (h + GAP), w, h, ratio, row)
                }
            }
            is HeroRow -> {
                val ratio = aspect.ratio
                val n = plan.count
                // Widest big slot whose height plus the row below still fits the region.
                val width = min(innerW.toFloat(), (innerH - GAP + (n - 1) * GAP / (n * ratio)) * ratio * n / (n + 1f))
                val bigW = width.toInt()
                val bigH = (bigW / ratio).roundToInt()
                val smallW = (bigW - (n - 1) * GAP) / n
                val smallH = (smallW / ratio).roundToInt()
                blockW = bigW
                blockH = bigH + GAP + smallH
                rects = listOf(Rect(0, 0, bigW, bigH, ratio, null)) +
                    List(n) { i -> Rect(i * (smallW + GAP), bigH + GAP, smallW, smallH, ratio, null) }
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
