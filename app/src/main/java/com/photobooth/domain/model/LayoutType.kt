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
 * (the mosaic and the double strips print the same shots twice). [enabledByDefault] is the starting set of a new
 * event; layouts added by an update start switched off in existing events.
 */
enum class LayoutType(
    val defaultOrientation: Orientation,
    val slotCount: Int,
    val photoCount: Int = slotCount,
    val enabledByDefault: Boolean = true,
) {
    LANDSCAPE_SINGLE(Orientation.LANDSCAPE, 1),
    PORTRAIT_SINGLE(Orientation.PORTRAIT, 1, enabledByDefault = false),
    LANDSCAPE_DOUBLE(Orientation.LANDSCAPE, 2, enabledByDefault = false),
    PORTRAIT_TRIPLE(Orientation.PORTRAIT, 3),
    GRID_2X2(Orientation.LANDSCAPE, 4),
    MOSAIC_6_SQUARE(Orientation.PORTRAIT, 6, photoCount = 3, enabledByDefault = false),

    /** One photo over the whole sheet but a slim title band (party banner). */
    LANDSCAPE_BANNER(Orientation.LANDSCAPE, 1),
    /** Two landscape photos stacked, title at the bottom. */
    PORTRAIT_DUO(Orientation.PORTRAIT, 2),
    /** A big photo on the left, two stacked on the right, title band below. */
    LANDSCAPE_HERO_SIDE(Orientation.LANDSCAPE, 3),
    /** A big photo on top and three small ones below, title at the bottom ("1+3"). */
    PORTRAIT_HERO_THREE(Orientation.PORTRAIT, 4, enabledByDefault = false),
    /** The classic photobooth strip (2x6 in) printed twice side by side: three shots each. */
    STRIP_DOUBLE_3(Orientation.PORTRAIT, 6, photoCount = 3),
    /** The classic strip with four shots, printed twice side by side. */
    STRIP_DOUBLE_4(Orientation.PORTRAIT, 8, photoCount = 4, enabledByDefault = false),
}
