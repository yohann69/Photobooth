package com.photobooth.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.util.Log
import com.photobooth.camera.partialFileFor
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.domain.model.PhotoFilterType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Supplies the bitmap for slot [index]; the engine takes ownership and recycles it after drawing. */
fun interface PhotoSupplier {
    fun photo(index: Int, widthPx: Int, heightPx: Int): Bitmap?
}

class PrintRender(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val decodeMs: Long,
    val composeMs: Long,
    val encodeMs: Long,
    val totalMs: Long,
)

/**
 * Renders a layout exactly as it prints: background, photos centre-cropped into the (calibrated) slots with
 * an optional colour filter, then the transparent PNG frame.
 *
 * Slot photos are decoded one at a time, each with a timeout: this tablet's hardware JPEG decoder service hung
 * for good once under four concurrent decodes, which would freeze the booth on the "preparing" screen.
 */
@Singleton
class PhotoCompositorEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val decodePool = Executors.newCachedThreadPool { r -> Thread(r, "compositor-decode").apply { isDaemon = true } }

    private var cachedImage: CachedBitmap? = null

    private class CachedBitmap(val key: String, val bitmap: Bitmap)

    /** Bitmap-backed render at [scale] (1.0 = full 300 DPI canvas); used for previews and the print path. */
    @Synchronized
    fun render(
        config: EventLayoutConfigEntity,
        scale: Float,
        photos: PhotoSupplier,
        showGuides: Boolean = false,
        filter: PhotoFilterType = PhotoFilterType.ORIGINAL,
    ): Bitmap = renderTimed(config, scale, photos, showGuides, filter, null)

    /**
     * The real final composition (background, frame, event artwork) with the [validated] photos in their slots,
     * [newPhoto] in slot [newIndex] and a subtle numbered placeholder in every slot still empty.
     */
    @Synchronized
    fun renderProgressivePreview(
        config: EventLayoutConfigEntity,
        scale: Float,
        validated: List<File?>,
        newPhoto: File?,
        newIndex: Int,
        filter: PhotoFilterType = PhotoFilterType.ORIGINAL,
    ): Bitmap {
        val files = validated.toMutableList()
        if (newPhoto != null && newIndex in files.indices) files[newIndex] = newPhoto
        val supplier = PhotoSupplier { i, w, h -> files.getOrNull(i)?.let { PhotoDecoder.decodeCrop(it, w, h) } }
        return renderTimed(config, scale, supplier, false, filter, null, placeholders = true)
    }

    /** Full-resolution print render written as a 98% JPEG tagged 300 DPI; blocks, so call it off the main thread. */
    @Synchronized
    fun renderPrint(
        config: EventLayoutConfigEntity,
        photoFiles: List<File?>,
        filter: PhotoFilterType,
        target: File,
    ): PrintRender {
        val started = System.nanoTime()
        val timing = LongArray(2)
        val supplier = PhotoSupplier { i, w, h -> photoFiles.getOrNull(i)?.let { PhotoDecoder.decodeCrop(it, w, h) } }
        val bitmap = renderTimed(config, 1f, supplier, false, filter, timing)

        val encodeStart = System.nanoTime()
        val bytes = ByteArrayOutputStream(512 * 1024).also {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encode failed" }
        }.toByteArray()
        tagJfifDensity(bytes, PRINT_DPI)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        target.absoluteFile.parentFile?.mkdirs()
        val partial = partialFileFor(target)
        try {
            FileOutputStream(partial).use { it.write(bytes) }
            if (!partial.renameTo(target)) throw IOException("Could not move ${partial.name} to ${target.name}")
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
        val done = System.nanoTime()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)

        return PrintRender(
            file = target,
            widthPx = width,
            heightPx = height,
            decodeMs = timing[0] / 1_000_000,
            composeMs = timing[1] / 1_000_000,
            encodeMs = (done - encodeStart) / 1_000_000,
            totalMs = (done - started) / 1_000_000,
        ).also {
            Log.i(TAG, "PhotoCompositor: ${config.layoutType} ${it.widthPx}x${it.heightPx} total=${it.totalMs}ms " +
                "(decode=${it.decodeMs} compose=${it.composeMs} encode=${it.encodeMs}) ${target.name}")
        }
    }

    private fun renderTimed(
        config: EventLayoutConfigEntity,
        scale: Float,
        photos: PhotoSupplier,
        showGuides: Boolean,
        filter: PhotoFilterType,
        timing: LongArray?,
        placeholders: Boolean = false,
    ): Bitmap {
        val outW = (config.canvasWidthPx * scale).roundToInt().coerceAtLeast(1)
        val outH = (config.canvasHeightPx * scale).roundToInt().coerceAtLeast(1)
        val slots = config.effectiveSlots()

        val decodeStart = System.nanoTime()
        // One decode per shot: slots that show the same shot (the mosaic columns) share the bitmap.
        val decoded = LinkedHashMap<Int, Bitmap?>()
        try {
            slots.forEachIndexed { i, s ->
                val source = s.sourceIndex ?: i
                if (source in decoded) return@forEachIndexed
                val task = decodePool.submit(Callable { photos.photo(source, (s.width * outW).roundToInt(), (s.height * outH).roundToInt()) })
                decoded[source] = try {
                    task.get(DECODE_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    task.cancel(true)
                    throw IOException("Photo decoder not responding (shot ${source + 1})")
                }
            }
        } catch (t: Throwable) {
            decoded.values.forEach { it?.recycle() }
            throw t
        }
        val composeStart = System.nanoTime()

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawColor(parseColor(config.backgroundColorHex))
            config.backgroundImagePath?.let { path ->
                loadScaled(path, outW)?.let { drawCenterCrop(canvas, it, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint) }
            }

            slots.forEachIndexed { index, s ->
                val rect = RectF(s.x * outW, s.y * outH, (s.x + s.width) * outW, (s.y + s.height) * outH)
                canvas.save()
                canvas.rotate(s.rotation, rect.centerX(), rect.centerY())
                canvas.clipRect(rect)
                val source = s.sourceIndex ?: index
                val photo = decoded[source]
                when {
                    photo != null -> filter.draw(canvas, photo, rect, paint)
                    placeholders -> drawPlaceholder(canvas, rect, source + 1)
                    else -> canvas.drawColor(Color.DKGRAY)
                }
                canvas.restore()
            }

            config.customFramePngPath?.let { path ->
                loadScaled(path, outW)?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint) }
            }
            if (showGuides) drawGuides(canvas, config, outW, outH)
        } finally {
            decoded.values.forEach { it?.recycle() }
        }
        if (timing != null) {
            timing[0] = composeStart - decodeStart
            timing[1] = System.nanoTime() - composeStart
        }
        return result
    }

    private fun drawPlaceholder(canvas: Canvas, rect: RectF, number: Int) {
        val unit = min(rect.width(), rect.height())
        canvas.drawRect(rect, Paint().apply { color = Color.argb(40, 128, 128, 128) })
        val stroke = max(2f, unit * 0.012f)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(150, 128, 128, 128)
            pathEffect = DashPathEffect(floatArrayOf(unit * 0.05f, unit * 0.04f), 0f)
        }
        canvas.drawRect(rect.left + stroke, rect.top + stroke, rect.right - stroke, rect.bottom - stroke, outline)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 128, 128, 128)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = unit * 0.35f
        }
        canvas.drawText("$number", rect.centerX(), rect.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawGuides(canvas: Canvas, config: EventLayoutConfigEntity, outW: Int, outH: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(255, 64, 129)
            strokeWidth = (outW / 300f).coerceAtLeast(1.5f)
        }
        config.effectiveSlots().forEach { s ->
            canvas.drawRect(s.x * outW, s.y * outH, (s.x + s.width) * outW, (s.y + s.height) * outH, stroke)
        }
        // Logo safe zone of the preset, so artwork can be placed against it.
        val zone = DefaultLayoutPresets.forType(config.layoutType).safeZone
        val sx = outW.toFloat() / config.canvasWidthPx
        val sy = outH.toFloat() / config.canvasHeightPx
        val dashed = Paint(stroke).apply {
            color = Color.rgb(0, 229, 255)
            pathEffect = DashPathEffect(floatArrayOf(stroke.strokeWidth * 6, stroke.strokeWidth * 4), 0f)
        }
        canvas.drawRect(zone.x * sx, zone.y * sy, (zone.x + zone.width) * sx, (zone.y + zone.height) * sy, dashed)
    }

    /** Frame/background decoded no larger than needed for [targetWidth]; the most recent one is cached. */
    private fun loadScaled(path: String, targetWidth: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val key = "$path:${file.lastModified()}:$sample"
        cachedImage?.takeIf { it.key == key && !it.bitmap.isRecycled }?.let { return it.bitmap }
        val bitmap = BitmapFactory.decodeFile(
            path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return null
        cachedImage = CachedBitmap(key, bitmap)
        return bitmap
    }

    private fun parseColor(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)

    /** Android's encoder writes a JFIF header with no density; patch it so printers see 300 DPI. */
    private fun tagJfifDensity(jpeg: ByteArray, dpi: Int) {
        val isJfif = jpeg.size > 18 && jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte() &&
            jpeg[6] == 'J'.code.toByte() && jpeg[7] == 'F'.code.toByte() && jpeg[8] == 'I'.code.toByte() && jpeg[9] == 'F'.code.toByte()
        if (!isJfif) return
        jpeg[13] = 1
        jpeg[14] = (dpi shr 8).toByte()
        jpeg[15] = dpi.toByte()
        jpeg[16] = (dpi shr 8).toByte()
        jpeg[17] = dpi.toByte()
    }

    private companion object {
        const val JPEG_QUALITY = 98
        const val DECODE_TIMEOUT_S = 10L
        const val PRINT_DPI = 300
    }
}
