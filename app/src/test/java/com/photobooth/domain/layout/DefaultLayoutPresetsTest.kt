package com.photobooth.domain.layout

import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.PhotoAspect
import com.photobooth.domain.model.PhotoSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DefaultLayoutPresetsTest {
    private fun pixelAspect(s: PhotoSlot, w: Int, h: Int) = (s.width * w) / (s.height * h)

    @Test
    fun onlyFullSheetLayoutsExist() {
        assertEquals(
            // Stored by name in the database: existing names must never change, new ones are appended.
            listOf(
                "LANDSCAPE_SINGLE", "PORTRAIT_SINGLE", "LANDSCAPE_DOUBLE", "PORTRAIT_TRIPLE", "GRID_2X2", "MOSAIC_6_SQUARE",
                "LANDSCAPE_BANNER", "PORTRAIT_DUO", "LANDSCAPE_HERO_SIDE", "PORTRAIT_HERO_THREE", "STRIP_DOUBLE_3", "STRIP_DOUBLE_4",
            ),
            LayoutType.entries.map { it.name },
        )
        PhotoAspect.entries.forEach { aspect ->
            LayoutType.entries.forEach { type ->
                val p = DefaultLayoutPresets.forType(type, aspect)
                assertEquals(type.slotCount, p.slots.size)
                assertEquals(setOf(1200, 1800), setOf(p.canvasWidthPx, p.canvasHeightPx))
            }
        }
    }

    @Test
    fun slotsFollowThePhotoAspectExceptTheSquareMosaic() {
        PhotoAspect.entries.forEach { aspect ->
            LayoutType.entries.forEach { type ->
                val p = DefaultLayoutPresets.forType(type, aspect)
                val expected = if (type == LayoutType.MOSAIC_6_SQUARE) 1f else aspect.ratio
                p.slots.forEach { s ->
                    val actual = pixelAspect(s, p.canvasWidthPx, p.canvasHeightPx)
                    assertTrue("$type $aspect aspect $actual", abs(actual - expected) / expected < 0.008f)
                }
            }
        }
    }

    @Test
    fun mosaicPrintsTheSameThreeShotsTwice() {
        val p = DefaultLayoutPresets.forType(LayoutType.MOSAIC_6_SQUARE)
        assertEquals(6, p.slots.size)
        assertEquals(3, LayoutType.MOSAIC_6_SQUARE.photoCount)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), p.slots.map { it.sourceIndex })
        // Two identical columns side by side: slot 2k and 2k+1 share a row and size.
        for (row in 0..2) {
            val left = p.slots[row * 2]
            val right = p.slots[row * 2 + 1]
            assertEquals(left.y, right.y, 0.0001f)
            assertEquals(left.width, right.width, 0.0001f)
            assertTrue(right.x > left.x)
        }
    }

    @Test
    fun portraitTripleIsOneBigOnTopAndTwoHalfWidthBelow() {
        val (big, a, b) = DefaultLayoutPresets.forType(LayoutType.PORTRAIT_TRIPLE).slots
        assertTrue(a.y > big.y + big.height)
        assertEquals(a.y, b.y, 0.0001f)
        assertEquals(big.width / 2f, a.width, big.width * 0.03f)
        assertEquals(a.width, b.width, 0.0001f)
    }

    @Test
    fun slotsStayInsideCanvasAndNeverOverlapEachOtherOrTheLogoZone() {
        LayoutType.entries.forEach { type ->
            val p = DefaultLayoutPresets.forType(type)
            val rects = p.slots.map { floatArrayOf(it.x * p.canvasWidthPx, it.y * p.canvasHeightPx, (it.x + it.width) * p.canvasWidthPx, (it.y + it.height) * p.canvasHeightPx) }
            rects.forEach { r ->
                assertTrue("$type bounds", r[0] >= 0f && r[1] >= 0f && r[2] <= p.canvasWidthPx + 0.5f && r[3] <= p.canvasHeightPx + 0.5f)
                val z = p.safeZone
                assertFalse("$type touches logo zone", r[0] < z.x + z.width && r[2] > z.x && r[1] < z.y + z.height && r[3] > z.y)
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                assertFalse("$type slots $i/$j overlap", a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1])
            }
        }
    }

    @Test
    fun logoZoneLeavesRoomForArtwork() {
        LayoutType.entries.forEach { type ->
            val f = DefaultLayoutPresets.forType(type).safeZoneFraction
            val min = DefaultLayoutPresets.minSafeZoneFraction(type)
            assertTrue("$type zone $f", f in (min - 0.001f)..0.5f)
        }
    }

    @Test
    fun doubleStripsCutDownTheMiddleIntoTwoIdenticalStrips() {
        for (type in listOf(LayoutType.STRIP_DOUBLE_3, LayoutType.STRIP_DOUBLE_4)) {
            PhotoAspect.entries.forEach { aspect ->
                val p = DefaultLayoutPresets.forType(type, aspect)
                val w = p.canvasWidthPx
                p.slots.chunked(2).forEachIndexed { row, (left, right) ->
                    assertEquals(row, left.sourceIndex)
                    assertEquals(row, right.sourceIndex)
                    // Each column is centred in its half, so both strips are the same once cut.
                    assertEquals(0.25f, left.x + left.width / 2f, 1f / w)
                    assertEquals(0.75f, right.x + right.width / 2f, 1f / w)
                    assertTrue("$type photo crosses the cut", (left.x + left.width) * w <= w / 2f)
                }
                assertEquals(type.photoCount, DefaultLayoutPresets.forType(type, aspect).slots.map { it.sourceIndex }.distinct().size)
            }
        }
    }

    @Test
    fun heroSideIsOneBigPhotoBesideTwoStackedOnes() {
        PhotoAspect.entries.forEach { aspect ->
            val p = DefaultLayoutPresets.forType(LayoutType.LANDSCAPE_HERO_SIDE, aspect)
            val (big, top, bottom) = p.slots
            assertTrue(top.x > big.x + big.width)
            assertEquals(top.x, bottom.x, 0.0001f)
            assertEquals(big.y, top.y, 0.0001f)
            // The two small ones end where the big one ends.
            assertEquals((big.y + big.height) * p.canvasHeightPx, (bottom.y + bottom.height) * p.canvasHeightPx, 2f)
        }
    }

    @Test
    fun aNewEventStartsWithAShortListOfLayouts() {
        val defaults = LayoutType.entries.filter { it.enabledByDefault }
        assertTrue(defaults.size in 4..8)
    }

    @Test
    fun calibrationKeepsTheSlotShape() {
        val p = DefaultLayoutPresets.forType(LayoutType.GRID_2X2)
        val calibrated = p.slots.withCalibration(p.canvasWidthPx, p.canvasHeightPx, paddingPx = 20, offsetXPx = 10, offsetYPx = -6)
        calibrated.forEachIndexed { i, s ->
            assertEquals(pixelAspect(p.slots[i], p.canvasWidthPx, p.canvasHeightPx), pixelAspect(s, p.canvasWidthPx, p.canvasHeightPx), 0.001f)
            assertTrue(s.width < p.slots[i].width)
        }
    }
}
