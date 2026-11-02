package com.photobooth.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Fixed set of bitmaps reused for every live view frame so steady-state streaming allocates nothing. */
internal class FrameBitmapRing(private val slots: Int = 4) {
    private val ring = arrayOfNulls<Bitmap>(slots)
    private var next = 0

    fun decode(data: ByteArray, offset: Int, length: Int, sampleSize: Int = 1): Bitmap? {
        val reusable = ring[next]?.takeIf { !it.isRecycled && it.isMutable }
        val bitmap = decodeInto(data, offset, length, reusable, sampleSize)
            ?: if (reusable != null) decodeInto(data, offset, length, null, sampleSize) else null
        if (bitmap != null) {
            ring[next] = bitmap
            next = (next + 1) % slots
        }
        return bitmap
    }

    private fun decodeInto(data: ByteArray, offset: Int, length: Int, reuse: Bitmap?, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Drops references; bitmaps may still be held by a collector, so they are left to the GC. */
    fun clear() {
        ring.fill(null)
        next = 0
    }
}

internal fun findJpegStart(data: ByteArray, length: Int): Int {
    for (i in 0 until length - 3) {
        if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) return i
    }
    return -1
}
