package com.photobooth.camera.uvc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.FrameBitmapRing
import com.photobooth.camera.UsbBackedCamera
import com.photobooth.camera.partialFileFor
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * A UVC webcam driven straight over the USB host API, for tablets whose Camera2 stack does not expose USB cameras.
 * Supports MJPEG streams on a bulk video endpoint, which is what a Canon body in "USB streaming" mode presents.
 * Stills are the next full frame of the stream, re-encoded as a standard JPEG.
 */
class UsbUvcEngine(
    @Suppress("unused") private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : CameraController, UsbBackedCamera {

    override val usbDeviceName: String get() = device.deviceName

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveViewFrameFlow: SharedFlow<Bitmap> = _frames.asSharedFlow()
    private val _rotation = MutableStateFlow(0)
    override val liveViewRotationDegrees: StateFlow<Int> = _rotation.asStateFlow()

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var readerThread: Thread? = null
    private var previewThread: Thread? = null
    @Volatile private var running = false

    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val frameLock = Object()
    @Volatile private var captureWaiter: CompletableDeferred<ByteArray>? = null
    private val ring = FrameBitmapRing()

    private class FrameDesc(val formatIndex: Int, val frameIndex: Int, val width: Int, val height: Int, val interval: Int)
    private class Stream(val iface: UsbInterface, val endpoint: UsbEndpoint, val frame: FrameDesc, val maxPayload: Int)

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        _state.value = CameraConnectionState.Connecting("USB webcam: ${device.productName ?: device.deviceName}")
        runCatching {
            val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed (USB permission missing?)")
            connection = conn
            val stream = negotiate(conn)
            iface = stream.iface
            startReaders(conn, stream)
            _state.value = CameraConnectionState.Connected(
                CameraDescriptor("${device.productName ?: "USB webcam"} ${stream.frame.width}x${stream.frame.height}", CameraStrategy.UVC_WEBCAM),
            )
            Log.i(TAG, "UsbUvcEngine: streaming ${stream.frame.width}x${stream.frame.height} MJPEG, maxPayload=${stream.maxPayload}")
            Unit
        }.onFailure {
            Log.w(TAG, "UsbUvcEngine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "USB webcam connect failed")
            release()
        }
    }

    // ---- UVC negotiation ---------------------------------------------------------------------

    private fun negotiate(conn: UsbDeviceConnection): Stream {
        val raw = conn.rawDescriptors ?: throw IOException("Cannot read USB descriptors")
        val frames = mutableListOf<FrameDesc>()
        var streamingInterface = -1
        var mjpegFormat = -1
        var inVideoStreaming = false
        var pos = 0
        while (pos + 2 <= raw.size) {
            val length = raw[pos].toInt() and 0xFF
            if (length < 2 || pos + length > raw.size) break
            val type = raw[pos + 1].toInt() and 0xFF
            if (type == DT_INTERFACE && length >= 9) {
                val cls = raw[pos + 5].toInt() and 0xFF
                val sub = raw[pos + 6].toInt() and 0xFF
                inVideoStreaming = cls == UsbConstants.USB_CLASS_VIDEO && sub == SC_VIDEOSTREAMING
                if (inVideoStreaming && streamingInterface < 0) streamingInterface = raw[pos + 2].toInt() and 0xFF
            } else if (type == DT_CS_INTERFACE && inVideoStreaming && length >= 4) {
                when (raw[pos + 2].toInt() and 0xFF) {
                    VS_FORMAT_MJPEG -> if (mjpegFormat < 0) mjpegFormat = raw[pos + 3].toInt() and 0xFF
                    VS_FRAME_MJPEG -> if (length >= 26 && mjpegFormat >= 0) {
                        frames += FrameDesc(
                            formatIndex = mjpegFormat,
                            frameIndex = raw[pos + 3].toInt() and 0xFF,
                            width = u16(raw, pos + 5), height = u16(raw, pos + 7),
                            interval = u32(raw, pos + 21),
                        )
                    }
                }
            }
            pos += length
        }
        Log.i(TAG, "UsbUvcEngine: VS interface=$streamingInterface mjpeg frames=${frames.joinToString { "${it.width}x${it.height}" }}")
        if (streamingInterface < 0) throw IOException("No UVC video streaming interface")
        if (frames.isEmpty()) throw IOException("Camera offers no MJPEG stream (descriptor bytes: ${raw.size})")

        val vs = (0 until device.interfaceCount).map { device.getInterface(it) }
            .first { it.id == streamingInterface && it.alternateSetting == 0 }
        val endpoint = (0 until vs.endpointCount).map { vs.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            ?: throw IOException("Camera streams over isochronous endpoints, which Android's USB host API cannot read")
        if (!conn.claimInterface(vs, true)) throw IOException("claimInterface failed")

        // Largest picture up to Full HD keeps the preview smooth while the still stays sharp.
        val frame = frames.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: frames.minBy { it.width * it.height }
        val maxPayload = probeAndCommit(conn, streamingInterface, frame)
        return Stream(vs, endpoint, frame, maxPayload)
    }

    private fun probeAndCommit(conn: UsbDeviceConnection, iface: Int, frame: FrameDesc): Int {
        var size = PROBE_LENGTH_11
        var buf = ByteArray(size)
        if (conn.controlTransfer(REQ_GET, UVC_GET_CUR, VS_PROBE, iface, buf, size, CONTROL_TIMEOUT_MS) < 0) {
            size = PROBE_LENGTH_10
            buf = ByteArray(size)
            if (conn.controlTransfer(REQ_GET, UVC_GET_CUR, VS_PROBE, iface, buf, size, CONTROL_TIMEOUT_MS) < 0) buf.fill(0)
        }
        buf[0] = 1 // bmHint: dwFrameInterval is fixed by the host
        buf[1] = 0
        buf[2] = frame.formatIndex.toByte()
        buf[3] = frame.frameIndex.toByte()
        putU32(buf, 4, frame.interval)
        check(conn.controlTransfer(REQ_SET, UVC_SET_CUR, VS_PROBE, iface, buf, size, CONTROL_TIMEOUT_MS) >= 0) { "UVC probe rejected" }
        check(conn.controlTransfer(REQ_GET, UVC_GET_CUR, VS_PROBE, iface, buf, size, CONTROL_TIMEOUT_MS) >= 0) { "UVC probe read failed" }
        check(conn.controlTransfer(REQ_SET, UVC_SET_CUR, VS_COMMIT, iface, buf, size, CONTROL_TIMEOUT_MS) >= 0) { "UVC commit rejected" }
        return u32(buf, 22).takeIf { it > 0 } ?: DEFAULT_READ_BYTES
    }

    // ---- Streaming ---------------------------------------------------------------------------

    private fun startReaders(conn: UsbDeviceConnection, stream: Stream) {
        running = true
        readerThread = Thread({ readLoop(conn, stream) }, "uvc-reader").also { it.start() }
        previewThread = Thread({ previewLoop() }, "uvc-preview").also { it.start() }
    }

    /** Reassembles frames from UVC payloads: each starts with a header whose FID bit toggles per frame and EOF marks its end. */
    private fun readLoop(conn: UsbDeviceConnection, stream: Stream) {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var frame = ByteArray(1 shl 20)
        var frameSize = 0
        var expectHeader = true
        var lastFid = -1
        var endOfFrame = false
        var corrupt = false
        var idleReads = 0

        fun finishFrame() {
            if (!corrupt && frameSize > 1024 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) {
                val copy = frame.copyOf(frameSize)
                latestFrame.set(copy)
                captureWaiter?.complete(copy)
                captureWaiter = null
                synchronized(frameLock) { frameLock.notifyAll() }
            }
            frameSize = 0
            corrupt = false
            endOfFrame = false
        }

        while (running) {
            val n = conn.bulkTransfer(stream.endpoint, chunk, chunk.size, READ_TIMEOUT_MS)
            if (n < 0) {
                if (++idleReads * READ_TIMEOUT_MS > STALL_LIMIT_MS) {
                    Log.w(TAG, "UsbUvcEngine: no data for ${STALL_LIMIT_MS}ms, giving up")
                    _state.value = CameraConnectionState.Failed("USB webcam stopped streaming")
                    return
                }
                continue
            }
            idleReads = 0
            var offset = 0
            if (n > 0 && expectHeader) {
                val headerLength = chunk[0].toInt() and 0xFF
                val info = chunk[1].toInt() and 0xFF
                if (headerLength in 2..12 && headerLength <= n && info and HEADER_EOH != 0) {
                    val fid = info and HEADER_FID
                    if (lastFid >= 0 && fid != lastFid && frameSize > 0) finishFrame()
                    lastFid = fid
                    if (info and HEADER_ERR != 0) corrupt = true
                    if (info and HEADER_EOF != 0) endOfFrame = true
                    offset = headerLength
                }
            }
            if (n > offset) {
                if (frameSize + (n - offset) > frame.size) frame = frame.copyOf(maxOf(frame.size * 2, frameSize + n))
                System.arraycopy(chunk, offset, frame, frameSize, n - offset)
                frameSize += n - offset
            }
            expectHeader = n < chunk.size // a short packet ends the payload
            if (expectHeader && endOfFrame) finishFrame()
        }
    }

    private fun previewLoop() {
        var last: ByteArray? = null
        var count = 0
        while (running) {
            synchronized(frameLock) {
                if (latestFrame.get() === last) frameLock.wait(200)
            }
            val current = latestFrame.get() ?: continue
            if (current === last) continue
            last = current
            // Half-size decode keeps the 1080p stream light on the GPU; every frame is still decoded at full size for stills.
            ring.decode(current, 0, current.size, sampleSize = 2)?.let { _frames.tryEmit(it) }
            if (++count == 1) Log.i(TAG, "UsbUvcEngine: first preview frame decoded")
        }
    }

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (_state.value !is CameraConnectionState.Connected) throw IOException("USB webcam not connected")
            val waiter = CompletableDeferred<ByteArray>()
            captureWaiter = waiter
            val jpeg = try {
                withTimeout(STILL_TIMEOUT_MS) { waiter.await() }
            } finally {
                captureWaiter = null
            }
            targetFile.absoluteFile.parentFile?.mkdirs()
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IOException("Captured frame could not be decoded")
            val partial = partialFileFor(targetFile)
            try {
                FileOutputStream(partial).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, STILL_QUALITY, it)) { "JPEG encode failed" } }
                if (!partial.renameTo(targetFile)) throw IOException("Could not move ${partial.name}")
            } catch (t: Throwable) {
                partial.delete()
                throw t
            } finally {
                bitmap.recycle()
            }
            Log.i(TAG, "UsbUvcEngine: captured ${targetFile.name} ${targetFile.length()} bytes")
            targetFile
        }.onFailure { Log.w(TAG, "UsbUvcEngine: capture failed: ${it.message}") }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        release()
        _state.value = CameraConnectionState.Disconnected
    }

    private fun release() {
        running = false
        synchronized(frameLock) { frameLock.notifyAll() }
        readerThread?.join(1_500)
        previewThread?.join(500)
        readerThread = null
        previewThread = null
        iface?.let { runCatching { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        iface = null
        connection = null
        latestFrame.set(null)
        captureWaiter?.cancel()
        captureWaiter = null
        ring.clear()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    private fun putU32(b: ByteArray, o: Int, v: Int) {
        for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte()
    }

    private companion object {
        const val DT_INTERFACE = 0x04
        const val DT_CS_INTERFACE = 0x24
        const val SC_VIDEOSTREAMING = 2
        const val VS_FORMAT_MJPEG = 0x06
        const val VS_FRAME_MJPEG = 0x07
        const val REQ_SET = 0x21
        const val REQ_GET = 0xA1
        const val UVC_SET_CUR = 0x01
        const val UVC_GET_CUR = 0x81
        const val VS_PROBE = 0x01 shl 8
        const val VS_COMMIT = 0x02 shl 8
        const val PROBE_LENGTH_11 = 34
        const val PROBE_LENGTH_10 = 26
        const val CONTROL_TIMEOUT_MS = 1_000
        const val HEADER_FID = 0x01
        const val HEADER_EOF = 0x02
        const val HEADER_ERR = 0x40
        const val HEADER_EOH = 0x80
        const val READ_CHUNK_BYTES = 16 * 1024
        const val DEFAULT_READ_BYTES = 16 * 1024
        const val READ_TIMEOUT_MS = 500
        const val STALL_LIMIT_MS = 8_000
        const val STILL_TIMEOUT_MS = 5_000L
        const val STILL_QUALITY = 95
    }
}
