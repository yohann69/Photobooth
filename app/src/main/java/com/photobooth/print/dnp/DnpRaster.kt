package com.photobooth.print.dnp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.photobooth.print.dnp.DnpProtocol.BMP_BLOCK_BYTES
import com.photobooth.print.dnp.DnpProtocol.NATIVE_WIDTH
import com.photobooth.print.dnp.DnpProtocol.ROWS_6X4

/** Yellow, magenta and cyan planes, each a complete BMP block (255 = no dye). */
internal class DnpPlanes(val yellow: ByteArray, val magenta: ByteArray, val cyan: ByteArray)

/** How the montage is laid into the printer raster; the right values depend on the printer's feed direction. */
data class DnpOrientation(val flipVertical: Boolean = true, val mirror: Boolean = false, val portraitClockwise: Boolean = true)

internal object DnpRaster {
    private const val SHEET_W = 1800
    private const val SHEET_H = 1200

    /** Lays a 6x4 in montage (rotated first if it is portrait) onto the 1920x1240 raster and splits it into dye planes. */
    fun build(montage: Bitmap, orientation: DnpOrientation): DnpPlanes {
        val landscape = if (montage.height > montage.width) {
            val m = Matrix().apply { postRotate(if (orientation.portraitClockwise) 90f else -90f) }
            Bitmap.createBitmap(montage, 0, 0, montage.width, montage.height, m, true)
        } else montage
        val sheet = if (landscape.width == SHEET_W && landscape.height == SHEET_H) landscape
        else Bitmap.createScaledBitmap(landscape, SHEET_W, SHEET_H, true)

        val raster = Bitmap.createBitmap(NATIVE_WIDTH, ROWS_6X4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raster)
        val left = (NATIVE_WIDTH - SHEET_W) / 2
        val top = (ROWS_6X4 - SHEET_H) / 2
        canvas.drawBitmap(sheet, left.toFloat(), top.toFloat(), null)
        // The overscan around the 6x4 in sheet repeats the outermost pixels so a slight misregistration shows no white edge.
        fun edge(src: Rect, dst: Rect) = canvas.drawBitmap(sheet, src, dst, null)
        val r = left + SHEET_W
        val b = top + SHEET_H
        edge(Rect(0, 0, 1, SHEET_H), Rect(0, top, left, b))
        edge(Rect(SHEET_W - 1, 0, SHEET_W, SHEET_H), Rect(r, top, NATIVE_WIDTH, b))
        edge(Rect(0, 0, SHEET_W, 1), Rect(left, 0, r, top))
        edge(Rect(0, SHEET_H - 1, SHEET_W, SHEET_H), Rect(left, b, r, ROWS_6X4))
        edge(Rect(0, 0, 1, 1), Rect(0, 0, left, top))
        edge(Rect(SHEET_W - 1, 0, SHEET_W, 1), Rect(r, 0, NATIVE_WIDTH, top))
        edge(Rect(0, SHEET_H - 1, 1, SHEET_H), Rect(0, b, left, ROWS_6X4))
        edge(Rect(SHEET_W - 1, SHEET_H - 1, SHEET_W, SHEET_H), Rect(r, b, NATIVE_WIDTH, ROWS_6X4))

        val pixels = IntArray(NATIVE_WIDTH * ROWS_6X4)
        raster.getPixels(pixels, 0, NATIVE_WIDTH, 0, 0, NATIVE_WIDTH, ROWS_6X4)
        raster.recycle()
        if (sheet !== montage) sheet.recycle()
        if (landscape !== montage && landscape !== sheet) landscape.recycle()

        val y = plane()
        val m = plane()
        val c = plane()
        for (row in 0 until ROWS_6X4) {
            val src = (if (orientation.flipVertical) ROWS_6X4 - 1 - row else row) * NATIVE_WIDTH
            val dst = BMP_BLOCK_BYTES + row * NATIVE_WIDTH
            for (col in 0 until NATIVE_WIDTH) {
                val px = pixels[src + (if (orientation.mirror) NATIVE_WIDTH - 1 - col else col)]
                // A plane byte is the colour channel itself: 255 means no dye of that colour.
                y[dst + col] = px.toByte()
                m[dst + col] = (px shr 8).toByte()
                c[dst + col] = (px shr 16).toByte()
            }
        }
        return DnpPlanes(y, m, c)
    }

    private fun plane(): ByteArray {
        val size = BMP_BLOCK_BYTES + NATIVE_WIDTH * ROWS_6X4
        val b = ByteArray(size)
        b[0] = 'B'.code.toByte()
        b[1] = 'M'.code.toByte()
        putInt(b, 2, size)
        putInt(b, 10, BMP_BLOCK_BYTES)
        putInt(b, 14, 40)
        putInt(b, 18, NATIVE_WIDTH)
        putInt(b, 22, ROWS_6X4)
        b[26] = 1
        b[28] = 8
        putInt(b, 38, DnpProtocol.PIXELS_PER_METER_300_DPI)
        putInt(b, 42, DnpProtocol.PIXELS_PER_METER_300_DPI)
        putInt(b, 46, 256)
        for (i in 0 until 256) {
            b[54 + 4 * i] = i.toByte()
            b[55 + 4 * i] = i.toByte()
            b[56 + 4 * i] = i.toByte()
        }
        return b
    }

    private fun putInt(b: ByteArray, offset: Int, v: Int) {
        for (i in 0..3) b[offset + i] = (v shr (8 * i)).toByte()
    }
}
