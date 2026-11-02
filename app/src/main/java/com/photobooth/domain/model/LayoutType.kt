package com.photobooth.domain.model

/** 10x15 cm (4x6 in) dye-sub print at 300 DPI. */
object PrintSpec {
    const val DPI = 300
    const val LONG_SIDE_PX = 1800
    const val SHORT_SIDE_PX = 1200
}

enum class Orientation(val canvasWidthPx: Int, val canvasHeightPx: Int) {
    PORTRAIT(PrintSpec.SHORT_SIDE_PX, PrintSpec.LONG_SIDE_PX),
    LANDSCAPE(PrintSpec.LONG_SIDE_PX, PrintSpec.SHORT_SIDE_PX),
}

/**
 * Every layout fills one full sheet; the orientation is intrinsic to the layout.
 * [slotCount] is the number of photo slots on the sheet, [photoCount] the number of shots the guest takes
 * (the mosaic prints the same three shots twice).
 */
enum class LayoutType(
    val defaultOrientation: Orientation,
    val slotCount: Int,
    val photoCount: Int = slotCount,
) {
    LANDSCAPE_SINGLE(Orientation.LANDSCAPE, 1),
    PORTRAIT_SINGLE(Orientation.PORTRAIT, 1),
    LANDSCAPE_DOUBLE(Orientation.LANDSCAPE, 2),
    PORTRAIT_TRIPLE(Orientation.PORTRAIT, 3),
    GRID_2X2(Orientation.LANDSCAPE, 4),
    MOSAIC_6_SQUARE(Orientation.PORTRAIT, 6, photoCount = 3),
}
