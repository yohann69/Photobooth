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
import com.photobooth.domain.model.PhotoFilterType
import com.photobooth.share.GalleryTheme
import com.photobooth.share.LocalGalleryHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
            val cold = engine.renderPrint(config, photos, PhotoFilterType.ORIGINAL, target)
            val warm = (1..3).map { engine.renderPrint(config, photos, PhotoFilterType.ORIGINAL, target) }.minBy { it.totalMs }

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
    fun filtersRenderOnGridLayout() {
        val preset = DefaultLayoutPresets.forType(LayoutType.GRID_2X2)
        val config = EventLayoutConfigEntity(
            eventId = "e2e", layoutType = preset.type, isEnabled = true, orientation = preset.orientation,
            canvasWidthPx = preset.canvasWidthPx, canvasHeightPx = preset.canvasHeightPx,
            photoSlots = preset.slots,
        )
        PhotoFilterType.entries.forEach { filter ->
            val target = File(eventDir, "${EventStorage.COMPOSITED_PRINTS}/E2E_FILTER_${filter.name}.jpg")
            val r = engine.renderPrint(config, List(4) { normalPhoto }, filter, target)
            Log.i(TAG, "E2E FILTER ${filter.name} total=${r.totalMs}ms size=${r.widthPx}x${r.heightPx}")
        }
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

    @Test
    fun galleryServerServesSessionPageDownloadsAndZip() {
        // The app process already runs its own server on :8080 from Application.onCreate.
        assertEquals(200, get("http://127.0.0.1:8080/health").first)
        val server = LocalGalleryHttpServer(18080)
        assertTrue("server failed to bind :18080", server.start())
        try {
            assertEquals(200, get("http://127.0.0.1:18080/health").first)
            val dir = File(eventDir, "${EventStorage.SESSIONS}/e2e-session").also { File(it, EventStorage.ORIGINALS).mkdirs() }
            val montage = File(dir, EventStorage.MONTAGE_FILE).also { normalPhoto.copyTo(it, overwrite = true) }
            val raws = (1..3).map { n -> File(dir, "${EventStorage.ORIGINALS}/Photo_$n.jpg").also { normalPhoto.copyTo(it, overwrite = true) } }
            val theme = GalleryTheme("E2E <Gala>", "#112233", "#445566", "#FAFAFA", "#101010")
            val handle = server.register("e2e-session", montage, raws, theme)
            assertTrue("no LAN address", handle != null)

            val page = String(get("http://127.0.0.1:18080/session/e2e-session").second)
            assertTrue(page.contains("Télécharger le Montage") && page.contains("Tout télécharger (.ZIP)"))
            assertTrue(page.contains("Télécharger Photo #3") && page.contains("--p:#112233") && page.contains("--bg:#FAFAFA"))
            assertTrue("event name must be escaped", page.contains("E2E &lt;Gala&gt;") && !page.contains("E2E <Gala>"))
            assertEquals(montage.length().toInt(), get("http://127.0.0.1:18080/download/e2e-session/montage").second.size)
            assertEquals(raws[1].length().toInt(), get("http://127.0.0.1:18080/download/e2e-session/raw/2").second.size)
            assertEquals(200, get("http://127.0.0.1:18080/session/e2e-session/thumb/1.jpg").first)

            val zip = get("http://127.0.0.1:18080/download/e2e-session/all.zip")
            assertEquals(200, zip.first)
            val names = java.util.zip.ZipInputStream(zip.second.inputStream()).use { zin ->
                generateSequence { zin.nextEntry }.map { it.name }.toList()
            }
            assertEquals(listOf("Montage_Final.jpg", "Photo_1.jpg", "Photo_2.jpg", "Photo_3.jpg"), names)

            assertEquals(404, get("http://127.0.0.1:18080/download/e2e-session/raw/4").first)
            assertEquals(404, get("http://127.0.0.1:18080/download/e2e-session/raw/0").first)
            assertEquals(404, get("http://127.0.0.1:18080/download/e2e-session/raw/abc").first)
            assertEquals(404, get("http://127.0.0.1:18080/session/unknown").first)
            Log.i(TAG, "E2E GALLERY page=ok montage=ok raw=ok zipEntries=$names zipBytes=${zip.second.size} url=${handle?.url}")
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0))
        } finally {
            conn.disconnect()
        }
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
