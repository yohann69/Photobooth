package com.photobooth.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import com.photobooth.domain.model.PhotoFilterType
import kotlin.math.max

private fun PhotoFilterType.colorMatrix(): ColorMatrix? = when (this) {
    PhotoFilterType.ORIGINAL -> null
    PhotoFilterType.BLACK_AND_WHITE -> ColorMatrix().apply {
        setSaturation(0f)
        val c = 1.35f
        val t = 128f * (1f - c)
        postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }
    PhotoFilterType.SEPIA -> ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    // Faded warm film: muted saturation, lifted blacks, red/yellow cast.
    PhotoFilterType.VINTAGE -> ColorMatrix().apply {
        setSaturation(0.7f)
        postConcat(ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, 30f, 0f, 0.88f, 0f, 0f, 22f, 0f, 0f, 0.74f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
    }
    PhotoFilterType.GLAM -> ColorMatrix().apply {
        setSaturation(1.1f)
        postConcat(ColorMatrix(floatArrayOf(1.05f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 8f, 0f, 0f, 0.98f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)))
    }
}

/** Draws [photo] centre-cropped into [dst] with this look; the frame overlay is never filtered. */
internal fun PhotoFilterType.draw(canvas: Canvas, photo: Bitmap, dst: RectF, base: Paint) {
    val paint = Paint(base).apply { colorFilter = colorMatrix()?.let(::ColorMatrixColorFilter) }
    drawCenterCrop(canvas, photo, dst, paint)
    if (this == PhotoFilterType.GLAM) {
        // Soft-focus pass: a heavily down-scaled copy blended over the sharp photo smooths skin texture.
        val soft = Bitmap.createScaledBitmap(photo, max(1, photo.width / 12), max(1, photo.height / 12), true)
        drawCenterCrop(canvas, soft, dst, Paint(paint).apply { alpha = 105 })
        soft.recycle()
    }
}
