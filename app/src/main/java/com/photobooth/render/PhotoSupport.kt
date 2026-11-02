package com.photobooth.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal object PhotoDecoder {
    /** Decodes [file] down-sampled so both sides stay at or above max(reqW, reqH), honouring EXIF rotation. */
    fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = max(reqWidth, reqHeight).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = when (
            runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /**
     * Decodes only the centre region of [file] that fits a [slotW]x[slotH] slot (EXIF-rotated), down-sampled
     * as far as the slot allows. Far cheaper than decoding a 24 MP frame and cropping afterwards.
     */
    fun decodeCrop(file: File, slotW: Int, slotH: Int): Bitmap? {
        val degrees = exifDegrees(file)
        val decoder = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (_: Exception) {
            return null
        } ?: return null
        try {
            val rawW = decoder.width
            val rawH = decoder.height
            val swapped = degrees % 180 != 0
            val rotW = if (swapped) rawH else rawW
            val rotH = if (swapped) rawW else rawH

            var cropW = rotW
            var cropH = (rotW.toLong() * slotH / slotW).toInt()
            if (cropH > rotH) {
                cropH = rotH
                cropW = (rotH.toLong() * slotW / slotH).toInt()
            }
            val x0 = (rotW - cropW) / 2
            val y0 = (rotH - cropH) / 2
            val x1 = x0 + cropW
            val y1 = y0 + cropH
            val raw = when (degrees) {
                90 -> Rect(y0, rawH - x1, y1, rawH - x0)
                180 -> Rect(rawW - x1, rawH - y1, rawW - x0, rawH - y0)
                270 -> Rect(rawW - y1, x0, rawW - y0, x1)
                else -> Rect(x0, y0, x1, y1)
            }

            var sample = 1
            while (cropW / (sample * 2) >= slotW && cropH / (sample * 2) >= slotH) sample *= 2
            val region = decoder.decodeRegion(
                raw, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: return null
            if (degrees == 0) return region
            val rotated = Bitmap.createBitmap(region, 0, 0, region.width, region.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            if (rotated !== region) region.recycle()
            return rotated
        } finally {
            decoder.recycle()
        }
    }

    private fun exifDegrees(file: File): Int = when (
        runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

/** Procedural stand-in photos so layouts can be previewed without a camera. */
internal object DummyPhotos {
    private val HUES = floatArrayOf(205f, 340f, 140f, 35f)

    fun create(index: Int, width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(8)
        val h = height.coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = HUES[index % HUES.size]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.35f, 1f)), Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.75f)),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        val s = min(w, h).toFloat()
        paint.color = Color.argb(230, 255, 244, 190)
        canvas.drawCircle(w * 0.78f, h * 0.22f, s * 0.12f, paint)
        paint.color = Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.45f))
        canvas.drawPath(
            Path().apply {
                moveTo(0f, h.toFloat())
                lineTo(w * 0.3f, h * 0.55f)
                lineTo(w * 0.55f, h * 0.8f)
                lineTo(w * 0.75f, h * 0.5f)
                lineTo(w.toFloat(), h.toFloat())
                close()
            },
            paint,
        )
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = s * 0.28f
        paint.isFakeBoldText = true
        canvas.drawText("${index + 1}", w / 2f, h / 2f + paint.textSize * 0.35f, paint)
        return bitmap
    }
}

internal fun drawCenterCrop(canvas: Canvas, photo: Bitmap, dst: RectF, paint: Paint) {
    val scale = max(dst.width() / photo.width, dst.height() / photo.height)
    val srcW = dst.width() / scale
    val srcH = dst.height() / scale
    val left = (photo.width - srcW) / 2f
    val top = (photo.height - srcH) / 2f
    canvas.drawBitmap(
        photo,
        android.graphics.Rect(left.toInt(), top.toInt(), (left + srcW).toInt(), (top + srcH).toInt()),
        dst,
        paint,
    )
}
