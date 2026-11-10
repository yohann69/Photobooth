package com.photobooth.data.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.storage.EventStorage
import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores an event's custom frame overlays (PNG with transparency). Callers pass the owning
 * event's `Frames` folder, so nothing written here can be seen by another event.
 */
@Singleton
class FrameStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Copies a picked image into [framesDir] after checking that it really is a PNG. */
    suspend fun import(framesDir: File, config: EventLayoutConfigEntity, uri: Uri): Result<File> = withContext(io) {
        runCatching {
            framesDir.mkdirs()
            val target = File(framesDir, "${config.layoutType.name}_${System.currentTimeMillis()}.png")
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
            input.use { src -> FileOutputStream(target).use { out -> src.copyTo(out) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outMimeType != "image/png" || bounds.outWidth <= 0) {
                target.delete()
                throw IOException("Selected file is not a PNG image")
            }
            target
        }
    }

    /**
     * Writes a semi-opaque frame whose transparent holes sit exactly on the layout's uncalibrated slots,
     * with the logo safe zone marked so artwork can be positioned against it.
     */
    suspend fun generateTestFrame(framesDir: File, config: EventLayoutConfigEntity, label: String): Result<File> = withContext(io) {
        runCatching {
            framesDir.mkdirs()
            val w = config.canvasWidthPx
            val h = config.canvasHeightPx
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 30, 41, 110) }
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = 8f
            }
            val dashed = Paint(outline).apply {
                strokeWidth = 6f
                pathEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }

            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
            config.photoSlots.forEachIndexed { index, s ->
                val r = RectF(s.x * w, s.y * h, (s.x + s.width) * w, (s.y + s.height) * h)
                canvas.drawRect(r, clear)
                canvas.drawRect(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4, outline)
                // Inner edge marks stay visible on layouts whose slot reaches the canvas edge.
                canvas.drawRect(r.left + 6, r.top + 6, r.right - 6, r.bottom - 6, outline)
                canvas.drawCircle(r.left + 56f, r.top + 56f, 32f, fill)
                text.textSize = 36f
                canvas.drawText("${(s.sourceIndex ?: index) + 1}", r.left + 56f, r.top + 56f + 13f, text)
            }

            val zone = DefaultLayoutPresets.forType(config.layoutType).safeZone
            val zoneRect = RectF(zone.x + 12f, zone.y + 12f, zone.x + zone.width - 12f, zone.y + zone.height - 12f)
            canvas.drawRect(zoneRect, dashed)
            text.textSize = minOf(54f, zoneRect.height() * 0.2f, zoneRect.width() * 0.12f)
            canvas.drawText(label, zoneRect.centerX(), zoneRect.centerY() - text.textSize * 0.2f, text)
            text.textSize *= 0.55f
            canvas.drawText("ZONE LOGO / DESIGN", zoneRect.centerX(), zoneRect.centerY() + text.textSize * 1.6f, text)
            text.textSize = 20f
            canvas.drawText("TEST FRAME ${config.layoutType.name}", w / 2f, 22f, text)

            val target = File(framesDir, "${config.layoutType.name}_test_${System.currentTimeMillis()}.png")
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Log.i(TAG, "FrameStorage: generated test frame ${target.absolutePath} (${w}x$h)")
            target
        }
    }

    /** Removes a stored file, but only from an event's Frames folder. */
    fun delete(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.parentFile?.name == EventStorage.FRAMES) file.delete()
    }

    /** Pixel size of a stored frame, or null if unreadable. */
    suspend fun sizeOf(path: String): Pair<Int, Int>? = withContext(io) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth > 0) bounds.outWidth to bounds.outHeight else null
    }
}
