package com.photobooth.camera.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import com.photobooth.camera.FrameBitmapRing
import com.photobooth.camera.partialFileFor
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

internal enum class CameraSelector { TABLET_FRONT_PREFERRED, EXTERNAL }

/**
 * Camera2 pipeline: YUV preview stream converted to reusable bitmaps on a dedicated thread,
 * plus a max-resolution JPEG still stream written straight to disk.
 */
internal class Camera2Source(
    private val context: Context,
    private val selector: CameraSelector,
    private val explicitCameraId: String? = null,
) {
    val frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val rotationDegrees = MutableStateFlow(0)

    /** Invoked once when the camera disappears or errors after a successful [open]. */
    @Volatile var onLost: ((String) -> Unit)? = null

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val ring = FrameBitmapRing()
    private val stillMutex = Mutex()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewReader: ImageReader? = null
    private var stillReader: ImageReader? = null

    @Volatile private var closed = false
    @Volatile private var pendingStill: PendingStill? = null
    @Volatile private var focusWaiter: CompletableDeferred<Unit>? = null
    private var autoFlash = false
    private var hasAutofocus = false
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT
    private var frameCounter = 0

    private var nv21 = ByteArray(0)
    private val jpegOut = ExposedByteArrayOutputStream(256 * 1024)

    private class PendingStill(val target: File, val result: CompletableDeferred<File>)

    class OpenResult(val label: String, val cameraId: String, val previewSize: Size, val stillSize: Size)

    @SuppressLint("MissingPermission")
    suspend fun open(): OpenResult {
        val id = explicitCameraId ?: pickCameraId(cameraManager, selector) ?: throw IOException("No ${selector.name} camera available")
        val characteristics = cameraManager.getCameraCharacteristics(id)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_FRONT
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("Camera $id has no stream configuration")

        val stillSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }
            ?: throw IOException("Camera $id has no JPEG output")
        val previewSize = choosePreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888), stillSize)
        autoFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH in (characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf())
        hasAutofocus = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())

        val t = HandlerThread("PhotoboothCamera2").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        try {
            val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3)
            preview.setOnImageAvailableListener({ onPreviewImage(it) }, h)
            previewReader = preview
            val still = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
            still.setOnImageAvailableListener({ onStillImage(it) }, h)
            stillReader = still

            val cam = openCamera(id, h)
            device = cam
            val s = createSession(cam, listOf(preview.surface, still.surface), h)
            session = s

            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                applyAutoModes(this, characteristics)
            }
            s.setRepeatingRequest(request.build(), previewCallback, h)
        } catch (e: Throwable) {
            close()
            throw when (e) {
                is IOException, is CancellationException -> e
                else -> IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }

        val facingName = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            else -> "external"
        }
        Log.i(TAG, "Camera2Source: opened id=$id facing=$facingName sensor=$sensorOrientation preview=$previewSize still=$stillSize")
        return OpenResult("$facingName camera #$id", id, previewSize, stillSize)
    }

    private fun applyAutoModes(builder: CaptureRequest.Builder, c: CameraCharacteristics) {
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    /** Same aspect as the still so the 19:9 preview viewport covers exactly the area the capture is cropped to. */
    private fun choosePreviewSize(sizes: Array<Size>, still: Size): Size {
        val target = 1280 * 960
        val limit = 1920 * 1440
        val stillAspect = still.width.toFloat() / still.height
        val matching = sizes.filter { it.width * it.height <= limit && abs(it.width.toFloat() / it.height - stillAspect) < 0.02f }
        return matching.minByOrNull { abs(it.width * it.height - target) }
            ?: sizes.filter { it.width * it.height <= 1920 * 1080 }.minByOrNull { abs(it.width * it.height - 1280 * 720) }
            ?: sizes.minBy { it.width * it.height }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String, h: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IOException("Camera $id disconnected")) else notifyLost("camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        val message = "Camera $id error $error"
                        if (cont.isActive) cont.resumeWithException(IOException(message)) else notifyLost(message)
                    }
                }, h)
            } catch (e: CameraAccessException) {
                cont.resumeWithException(IOException("Camera access: ${e.message}", e))
            } catch (e: SecurityException) {
                cont.resumeWithException(IOException("Camera permission missing", e))
            }
        }

    private suspend fun createSession(cam: CameraDevice, surfaces: List<Surface>, h: Handler): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val executor = Executor { h.post(it) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IOException("Capture session configuration failed"))
                    }
                },
            )
            try {
                cam.createCaptureSession(config)
            } catch (e: CameraAccessException) {
                cont.resumeWithException(IOException("Camera access: ${e.message}", e))
            }
        }

    private fun notifyLost(reason: String) {
        if (closed) return
        Log.w(TAG, "Camera2Source: lost: $reason")
        onLost?.invoke(reason)
    }

    // ---- Preview -----------------------------------------------------------------------------

    private fun onPreviewImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (closed) return
            val w = image.width
            val h = image.height
            val needed = w * h * 3 / 2
            if (nv21.size != needed) nv21 = ByteArray(needed)
            toNv21(image, nv21)
            jpegOut.reset()
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), PREVIEW_JPEG_QUALITY, jpegOut)
            val bitmap = ring.decode(jpegOut.buffer(), 0, jpegOut.size()) ?: return
            if (frameCounter++ % 30 == 0) rotationDegrees.value = computeRotation()
            frames.tryEmit(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2Source: preview frame dropped: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun toNv21(image: Image, out: ByteArray) {
        val w = image.width
        val h = image.height
        val (yPlane, uPlane, vPlane) = image.planes.toList()
        val yBuf = yPlane.buffer
        var o = 0
        if (yPlane.rowStride == w) {
            yBuf.position(0)
            yBuf.get(out, 0, w * h)
            o = w * h
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(out, o, w)
                o += w
            }
        }
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until h / 2) {
            val vRow = row * vPlane.rowStride
            val uRow = row * uPlane.rowStride
            for (col in 0 until w / 2) {
                out[o++] = vBuf.get(vRow + col * vPlane.pixelStride)
                out[o++] = uBuf.get(uRow + col * uPlane.pixelStride)
            }
        }
    }

    // ---- Still capture -----------------------------------------------------------------------

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val waiter = focusWaiter ?: return
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            val focused = !hasAutofocus || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            val metered = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            if (focused && metered) waiter.complete(Unit)
        }
    }

    /** Locks focus and exposure (and decides on flash) ahead of the shot; gives up quietly after a short timeout. */
    suspend fun prepareStill() {
        val cam = device ?: return
        val s = session ?: return
        val preview = previewReader ?: return
        val h = handler ?: return
        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) return
        val waiter = CompletableDeferred<Unit>()
        focusWaiter = waiter
        try {
            val trigger = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                if (hasAutofocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
                set(CaptureRequest.CONTROL_AE_MODE, if (autoFlash) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH else CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            s.capture(trigger.build(), previewCallback, h)
            val done = kotlinx.coroutines.withTimeoutOrNull(PREPARE_TIMEOUT_MS) { waiter.await() } != null
            Log.i(TAG, "Camera2Source: autofocus/exposure ready=$done flash=$autoFlash")
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Camera2Source: prepareStill failed: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Camera2Source: prepareStill failed: ${e.message}")
        } finally {
            focusWaiter = null
        }
    }

    /** Releases the focus lock so the preview goes back to continuous autofocus. */
    private fun resumeContinuousFocus() {
        val cam = device ?: return
        val s = session ?: return
        val preview = previewReader ?: return
        if (!hasAutofocus || lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) return
        runCatching {
            val cancel = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
            s.capture(cancel.build(), null, handler)
        }
    }

    suspend fun captureJpeg(target: File, timeoutMs: Long = STILL_TIMEOUT_MS): File = stillMutex.withLock {
        val cam = device ?: throw IOException("Camera not open")
        val s = session ?: throw IOException("Camera session not ready")
        val reader = stillReader ?: throw IOException("Still reader missing")
        val h = handler ?: throw IOException("Camera thread missing")

        val result = CompletableDeferred<File>()
        pendingStill = PendingStill(target, result)
        try {
            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, computeRotation())
                set(CaptureRequest.JPEG_QUALITY, STILL_JPEG_QUALITY.toByte())
                if (lensFacing != CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, if (autoFlash) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH else CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    result.completeExceptionally(IOException("Still capture failed (reason ${failure.reason})"))
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) = Unit
            }, h)
            withTimeout(timeoutMs) { result.await() }
        } finally {
            pendingStill = null
            resumeContinuousFocus()
        }
    }

    private fun onStillImage(reader: ImageReader) {
        val image = reader.acquireNextImage()
        val pending = pendingStill
        try {
            if (pending == null) return
            pending.target.absoluteFile.parentFile?.mkdirs()
            val partial = partialFileFor(pending.target)
            try {
                FileOutputStream(partial).use { out ->
                    val buffer = image.planes[0].buffer
                    val channel = out.channel
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                if (!partial.renameTo(pending.target)) throw IOException("Could not move ${partial.name}")
                pending.result.complete(pending.target)
            } catch (e: Exception) {
                partial.delete()
                pending.result.completeExceptionally(e)
            }
        } finally {
            image.close()
        }
    }

    private fun computeRotation(): Int {
        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) return 0
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val displayDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
        return (sensorOrientation + sign * displayDegrees + 360) % 360
    }

    // ---- Teardown ----------------------------------------------------------------------------

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        pendingStill?.result?.completeExceptionally(IOException("Camera closed"))
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { previewReader?.close() }
        runCatching { stillReader?.close() }
        session = null
        device = null
        previewReader = null
        stillReader = null
        thread?.quitSafely()
        thread = null
        handler = null
        ring.clear()
    }

    private class ExposedByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun buffer(): ByteArray = buf
    }

    companion object {
        private const val PREVIEW_JPEG_QUALITY = 80
        private const val STILL_JPEG_QUALITY = 95
        private const val STILL_TIMEOUT_MS = 8_000L
        private const val PREPARE_TIMEOUT_MS = 2_500L

        fun hasExternalCamera(context: Context): Boolean {
            val cm = context.getSystemService(CameraManager::class.java)
            return pickCameraId(cm, CameraSelector.EXTERNAL) != null
        }

        fun externalCameraIds(context: Context): List<String> {
            val cm = context.getSystemService(CameraManager::class.java)
            return cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL }
        }

        fun pickCameraId(cm: CameraManager, selector: CameraSelector): String? {
            val facings = cm.cameraIdList.associateWith {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
            }
            return when (selector) {
                CameraSelector.EXTERNAL ->
                    facings.entries.firstOrNull { it.value == CameraCharacteristics.LENS_FACING_EXTERNAL }?.key
                CameraSelector.TABLET_FRONT_PREFERRED ->
                    facings.entries.firstOrNull { it.value == CameraCharacteristics.LENS_FACING_FRONT }?.key
                        ?: facings.entries.firstOrNull { it.value == CameraCharacteristics.LENS_FACING_BACK }?.key
                        ?: facings.keys.firstOrNull()
            }
        }
    }
}
