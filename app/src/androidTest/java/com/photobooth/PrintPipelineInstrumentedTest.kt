package com.photobooth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.frames.FrameStorage
import com.photobooth.data.storage.EventStorage
import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.Orientation
import com.photobooth.render.PhotoCompositorEngine
import com.photobooth.render.PhotoDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Runs inside the app process on the tablet; every measurement is logged under the "Photobooth" tag with an "E2E" prefix. */
@RunWith(AndroidJUnit4::class)
class PrintPipelineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PhotoCompositorEngine(context)
    private lateinit var eventDir: File
    private lateinit var normalPhoto: File
    private lateinit var rotatedPhoto: File

    @Before
    fun setUp() {
        eventDir = EventStorage(context).createEventDirectory("e2e-instrumentation", "E2E Instrumentation")
        normalPhoto = File(context.cacheDir, "e2e_24mp_normal.jpg").also { createPhoto(it, 6000, 4000, exifOrientation = null) }
        rotatedPhoto = File(context.cacheDir, "e2e_24mp_rot6.jpg").also { createPhoto(it, 6000, 4000, exifOrientation = ExifInterface.ORIENTATION_ROTATE_90) }
        Log.i(TAG, "E2E: eventDir=${eventDir.absolutePath} sources=${normalPhoto.length() / 1024}KB")
    }

    @Test
    fun rendersEveryLayoutAt300Dpi() = runBlocking {
        val frames = FrameStorage(context, Dispatchers.IO)
        val prints = File(eventDir, EventStorage.COMPOSITED_PRINTS)
        val framesDir = File(eventDir, EventStorage.FRAMES)
        val cases = LayoutType.entries.map { it to it.defaultOrientation }

        val slowest = cases.map { (type, orientation) ->
            val preset = DefaultLayoutPresets.forType(type)
            var config = EventLayoutConfigEntity(
                eventId = "e2e", layoutType = type, isEnabled = true, orientation = orientation,
                canvasWidthPx = preset.canvasWidthPx, canvasHeightPx = preset.canvasHeightPx,
                photoSlots = preset.slots,
            )
            val frame = frames.generateTestFrame(framesDir, config, "E2E").getOrThrow()
            config = config.copy(customFramePngPath = frame.absolutePath)
            val photos = List(config.photoCount) { i -> if (i % 2 == 0) normalPhoto else rotatedPhoto }

            val target = File(prints, "E2E_${type.name}_${orientation.name}.jpg")
            val cold = engine.renderPrint(config, photos, target)
            val warm = (1..3).map { engine.renderPrint(config, photos, target) }.minBy { it.totalMs }

            val expectedW = if (orientation == Orientation.LANDSCAPE) 1800 else 1200
            val expectedH = if (orientation == Orientation.LANDSCAPE) 1200 else 1800
            assertEquals("$type width", expectedW, warm.widthPx)
            assertEquals("$type height", expectedH, warm.heightPx)
            assertTrue("$type file missing", target.length() > 50_000)
            assertEquals("$type dpi", 300, jfifDpi(target))
            Log.i(
                TAG,
                "E2E RESULT layout=$type orient=$orientation size=${warm.widthPx}x${warm.heightPx} " +
                    "cold=${cold.totalMs}ms warm=${warm.totalMs}ms (decode=${warm.decodeMs} compose=${warm.composeMs} encode=${warm.encodeMs}) " +
                    "bytes=${target.length()} dpi=${jfifDpi(target)} file=${target.absolutePath}",
            )
            warm.totalMs
        }.max()
        Log.i(TAG, "E2E SUMMARY slowestWarm=${slowest}ms target<400ms=${slowest < 400}")
        assertTrue("slowest warm render ${slowest}ms exceeds 400ms", slowest < 400)
    }

    @Test
    fun exifRotationMapsRegionCorrectly() {
        // Raw 1200x800 image: red square at raw top-left, blue square at raw bottom-right.
        val cases = mapOf(
            ExifInterface.ORIENTATION_ROTATE_90 to Triple(800, 1200, "TR"),
            ExifInterface.ORIENTATION_ROTATE_180 to Triple(1200, 800, "BR"),
            ExifInterface.ORIENTATION_ROTATE_270 to Triple(800, 1200, "BL"),
        )
        cases.forEach { (orientation, expected) ->
            val file = File(context.cacheDir, "orient_$orientation.jpg")
            val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(Color.WHITE)
                c.drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.RED })
                c.drawRect(1000f, 600f, 1200f, 800f, Paint().apply { color = Color.BLUE })
            }
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val out = PhotoDecoder.decodeCrop(file, expected.first, expected.second)!!
            assertEquals("orientation $orientation width", expected.first, out.width)
            assertEquals("orientation $orientation height", expected.second, out.height)
            val corner = when (expected.third) {
                "TR" -> out.getPixel(out.width - 40, 40)
                "BR" -> out.getPixel(out.width - 40, out.height - 40)
                else -> out.getPixel(40, out.height - 40)
            }
            assertTrue("orientation $orientation: red marker expected at ${expected.third}, got ${Integer.toHexString(corner)}",
                Color.red(corner) > 200 && Color.green(corner) < 80 && Color.blue(corner) < 80)
        }
        Log.i(TAG, "E2E EXIF rotation mapping OK")
    }

    private fun jfifDpi(file: File): Int {
        val head = file.inputStream().use { it.readNBytes(20) }
        val units = head[13].toInt()
        val x = ((head[14].toInt() and 0xFF) shl 8) or (head[15].toInt() and 0xFF)
        return if (units == 1) x else -1
    }

    /** Camera-like 24 MP JPEG; RGB_565 keeps the 6000x4000 canvas to 48 MB. */
    private fun createPhoto(file: File, w: Int, h: Int, exifOrientation: Int?) {
        if (file.exists() && file.length() > 0 && exifOrientation == null) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 12) {
            p.color = Color.HSVToColor(floatArrayOf(i * 30f, 0.6f, 0.9f))
            c.drawRect(i * w / 12f, 0f, (i + 1) * w / 12f, h.toFloat(), p)
        }
        p.color = Color.WHITE
        c.drawCircle(w / 2f, h / 2f, h / 3f, p)
        p.color = Color.BLACK
        p.textSize = h / 6f
        p.textAlign = Paint.Align.CENTER
        c.drawText("24MP", w / 2f, h / 2f + p.textSize / 3, p)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        if (exifOrientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                saveAttributes()
            }
        }
    }

    private companion object {
        const val TAG = "Photobooth"
    }
}
