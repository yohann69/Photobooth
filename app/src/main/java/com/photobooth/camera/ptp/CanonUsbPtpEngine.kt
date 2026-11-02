package com.photobooth.camera.ptp

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.FrameBitmapRing
import com.photobooth.camera.UsbBackedCamera
import com.photobooth.camera.findJpegStart
import com.photobooth.camera.partialFileFor
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strategy A: raw PTP over USB bulk endpoints, with Canon EOS extensions for remote shooting
 * and MJPEG live view. All PTP traffic is serialized through [ptpLock].
 */
class CanonUsbPtpEngine(
    @Suppress("unused") private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val liveViewEnabled: Boolean = true,
) : CameraController, UsbBackedCamera {

    override val usbDeviceName: String get() = device.deviceName

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ptpLock = Mutex()
    private val captureMutex = Mutex()

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveViewFrameFlow: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _rotation = MutableStateFlow(0)
    override val liveViewRotationDegrees: StateFlow<Int> = _rotation.asStateFlow()

    @Volatile private var liveViewPaused = false
    @Volatile private var liveViewNeedsRestart = false

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var transport: PtpTransport? = null
    private var deviceInfo: PtpDeviceInfo? = null
    private var isEos = false
    private var liveViewJob: Job? = null

    private val ring = FrameBitmapRing()
    private val frameBuffer = GrowableBuffer(1 shl 20)
    private val scratchBuffer = GrowableBuffer(64 * 1024)

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        _state.value = CameraConnectionState.Connecting("USB PTP: ${device.productName ?: device.deviceName}")
        runCatching {
            val descriptor = openAndInitialize()
            _state.value = CameraConnectionState.Connected(descriptor)
            if (liveViewEnabled && isEos) launchLiveViewLoop()
            Log.i(TAG, "CanonUsbPtpEngine: connected ${descriptor.name} eos=$isEos liveView=${liveViewEnabled && isEos}")
            Unit
        }.onFailure {
            Log.w(TAG, "CanonUsbPtpEngine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "USB PTP connect failed")
            releaseUsb()
        }
    }

    private fun openAndInitialize(): CameraDescriptor {
        val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed (USB permission missing?)")
        connection = conn
        val endpoints = findPtpEndpoints() ?: throw IOException("No PTP bulk interface on ${device.deviceName}")
        if (!conn.claimInterface(endpoints.iface, true)) throw IOException("claimInterface failed")
        claimedInterface = endpoints.iface

        val t = PtpTransport(conn, endpoints.bulkIn, endpoints.bulkOut)
        transport = t

        val infoBuffer = GrowableBuffer(4096)
        t.executeOk(Ptp.OC_GET_DEVICE_INFO, sink = infoBuffer)
        val info = PtpDeviceInfo.parse(infoBuffer.array, infoBuffer.size)
        deviceInfo = info

        t.resetTransactionCounter()
        var r = t.execute(Ptp.OC_OPEN_SESSION, intArrayOf(SESSION_ID))
        if (r.code == Ptp.RC_SESSION_ALREADY_OPEN) {
            t.execute(Ptp.OC_CLOSE_SESSION)
            t.resetTransactionCounter()
            r = t.execute(Ptp.OC_OPEN_SESSION, intArrayOf(SESSION_ID))
        }
        if (!r.isOk) throw PtpException(r.code, "OpenSession failed: 0x${r.code.toString(16)}")

        isEos = CanonEos.OC_SET_REMOTE_MODE in info.operations
        if (isEos) {
            t.executeOk(CanonEos.OC_SET_REMOTE_MODE, intArrayOf(1))
            t.executeOk(CanonEos.OC_SET_EVENT_MODE, intArrayOf(1))
            readEosEventsLocked()
            runCatching { setEosProperty(CanonEos.PROP_CAPTURE_DESTINATION, CanonEos.CAPTURE_DEST_HOST) }
                .onFailure { Log.w(TAG, "CanonUsbPtpEngine: capture destination not set: ${it.message}") }
        }
        val name = info.model.ifBlank { device.productName ?: "Canon camera" }
        return CameraDescriptor(name = name, strategy = CameraStrategy.USB_PTP)
    }

    private class PtpEndpoints(val iface: UsbInterface, val bulkIn: UsbEndpoint, val bulkOut: UsbEndpoint)

    private fun findPtpEndpoints(): PtpEndpoints? {
        val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE || it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC }
            .sortedBy { if (it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) 0 else 1 }
        for (iface in candidates) {
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
            if (bulkIn != null && bulkOut != null) return PtpEndpoints(iface, bulkIn, bulkOut)
        }
        return null
    }

    // ---- Live view ---------------------------------------------------------------------------

    private fun startLiveViewLocked() {
        val t = transport ?: return
        setEosProperty(CanonEos.PROP_EVF_MODE, 1)
        setEosProperty(CanonEos.PROP_EVF_OUTPUT_DEVICE, CanonEos.EVF_OUTPUT_PC)
        if (CanonEos.OC_INITIATE_VIEWFINDER in (deviceInfo?.operations ?: emptySet())) {
            runCatching { t.execute(CanonEos.OC_INITIATE_VIEWFINDER) }
        }
    }

    private class FrameSpan(val offset: Int, val length: Int)

    private fun launchLiveViewLoop() {
        liveViewJob = scope.launch {
            var failures = 0
            var lastEventPump = SystemClock.elapsedRealtime()
            ptpLock.withLock {
                runCatching { startLiveViewLocked() }
                    .onFailure { Log.w(TAG, "CanonUsbPtpEngine: live view start: ${it.message}") }
            }
            while (isActive) {
                if (liveViewPaused) {
                    delay(15)
                    continue
                }
                val span: FrameSpan? = try {
                    ptpLock.withLock {
                        if (liveViewPaused) return@withLock null
                        if (liveViewNeedsRestart) {
                            liveViewNeedsRestart = false
                            startLiveViewLocked()
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEventPump > EVENT_PUMP_INTERVAL_MS) {
                            lastEventPump = now
                            readEosEventsLocked()
                        }
                        fetchViewfinderFrameLocked()
                    }
                } catch (e: PtpException) {
                    null
                } catch (e: IOException) {
                    Log.e(TAG, "CanonUsbPtpEngine: USB I/O failure in live view: ${e.message}")
                    _state.value = CameraConnectionState.Failed("USB I/O error: ${e.message}")
                    return@launch
                }

                if (span == null) {
                    failures++
                    if (failures % RESTART_AFTER_FAILURES == 0) liveViewNeedsRestart = true
                    if (failures > MAX_LIVE_VIEW_FAILURES) {
                        _state.value = CameraConnectionState.Failed("Live view stalled")
                        return@launch
                    }
                    delay(30)
                    continue
                }
                failures = 0
                ring.decode(frameBuffer.array, span.offset, span.length)?.let { _frames.tryEmit(it) }
            }
        }
    }

    private fun fetchViewfinderFrameLocked(): FrameSpan? {
        val t = transport ?: return null
        frameBuffer.reset()
        val r = t.execute(CanonEos.OC_GET_VIEWFINDER_DATA, intArrayOf(CanonEos.VIEWFINDER_BUFFER_SIZE), sink = frameBuffer)
        if (!r.isOk) return null // NOT_READY / DEVICE_BUSY while the mirror moves
        val start = findJpegStart(frameBuffer.array, frameBuffer.size)
        return if (start < 0) null else FrameSpan(start, frameBuffer.size - start)
    }

    // ---- Capture -----------------------------------------------------------------------------

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        captureMutex.withLock {
            runCatching {
                check(_state.value is CameraConnectionState.Connected) { "Camera not connected" }
                liveViewPaused = true
                try {
                    ptpLock.withLock {
                        // Let the body settle so DSLRs do not answer DeviceBusy to the shutter command.
                        delay(MIRROR_SETTLE_MS)
                        if (isEos) captureEosLocked(targetFile) else captureStandardLocked(targetFile)
                    }
                } finally {
                    liveViewNeedsRestart = true
                    liveViewPaused = false
                }
            }.onFailure { Log.w(TAG, "CanonUsbPtpEngine: capture failed: ${it.message}") }
        }
    }

    private suspend fun captureEosLocked(target: File): File {
        val started = SystemClock.elapsedRealtime()
        readEosEventsLocked() // drop stale object events
        fireShutterLocked()
        val obj = awaitEosObjectLocked()
        val file = downloadObjectLocked(obj.oid, obj.size, target)
        runCatching { transport?.execute(CanonEos.OC_TRANSFER_COMPLETE, intArrayOf(obj.oid)) }
        Log.i(TAG, "CanonUsbPtpEngine: captured ${obj.name} ${file.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
        return file
    }

    private suspend fun fireShutterLocked() {
        val t = transport ?: throw IOException("Not connected")
        val ops = deviceInfo?.operations.orEmpty()
        if (CanonEos.OC_REMOTE_RELEASE_ON in ops) {
            repeat(SHUTTER_RETRIES) {
                val r = t.execute(CanonEos.OC_REMOTE_RELEASE_ON, intArrayOf(FULL_PRESS_NO_AF, 0))
                when (r.code) {
                    Ptp.RC_OK -> {
                        runCatching { t.execute(CanonEos.OC_REMOTE_RELEASE_OFF, intArrayOf(FULL_PRESS_NO_AF)) }
                        return
                    }
                    Ptp.RC_DEVICE_BUSY, CanonEos.RC_NOT_READY -> delay(MIRROR_SETTLE_MS)
                    else -> throw PtpException(r.code, "RemoteReleaseOn failed: 0x${r.code.toString(16)}")
                }
            }
            throw PtpException(Ptp.RC_DEVICE_BUSY, "Camera stayed busy; shutter not released")
        } else {
            repeat(SHUTTER_RETRIES) {
                val r = t.execute(Ptp.OC_INITIATE_CAPTURE, intArrayOf(0, 0))
                when (r.code) {
                    Ptp.RC_OK -> return
                    Ptp.RC_DEVICE_BUSY -> delay(MIRROR_SETTLE_MS)
                    else -> throw PtpException(r.code, "InitiateCapture failed: 0x${r.code.toString(16)}")
                }
            }
            throw PtpException(Ptp.RC_DEVICE_BUSY, "Camera stayed busy; capture not initiated")
        }
    }

    /** Polls Canon GetEvent until a JPEG object is announced (ObjectAddedEx / RequestObjectTransfer). */
    private suspend fun awaitEosObjectLocked(): EosObject {
        val t = transport ?: throw IOException("Not connected")
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        var firstOther: EosObject? = null
        var otherSeenAt = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            for (obj in readEosEventsLocked()) {
                if (obj.isJpeg) return obj
                runCatching { t.execute(CanonEos.OC_TRANSFER_COMPLETE, intArrayOf(obj.oid)) }
                if (firstOther == null) {
                    firstOther = obj
                    otherSeenAt = SystemClock.elapsedRealtime()
                }
            }
            val other = firstOther
            if (other != null && SystemClock.elapsedRealtime() - otherSeenAt > RAW_ONLY_GRACE_MS) return other
            delay(EVENT_POLL_MS)
        }
        throw IOException("Timed out waiting for the camera to announce the new photo")
    }

    private suspend fun captureStandardLocked(target: File): File {
        val before = objectHandlesLocked()
        fireShutterLocked()
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val added = objectHandlesLocked() - before
            if (added.isNotEmpty()) return downloadObjectLocked(added.last(), 0, target)
            delay(300)
        }
        throw IOException("Timed out waiting for the new object")
    }

    private fun objectHandlesLocked(): Set<Int> {
        val t = transport ?: throw IOException("Not connected")
        scratchBuffer.reset()
        t.executeOk(Ptp.OC_GET_OBJECT_HANDLES, intArrayOf(-1, 0, 0), sink = scratchBuffer)
        if (scratchBuffer.size < 4) return emptySet()
        val count = scratchBuffer.array.u32(0)
        return (0 until count).mapTo(HashSet()) { scratchBuffer.array.u32(4 + it * 4) }
    }

    private fun downloadObjectLocked(oid: Int, sizeHint: Long, target: File): File {
        val t = transport ?: throw IOException("Not connected")
        target.absoluteFile.parentFile?.mkdirs()
        val partial = partialFileFor(target)
        try {
            FileOutputStream(partial).use { out ->
                val channel = out.channel
                // Direct inbound buffer goes straight to the file channel: no heap copy.
                val sink = PtpDataSink { buf -> while (buf.hasRemaining()) channel.write(buf) }
                val r = t.execute(Ptp.OC_GET_OBJECT, intArrayOf(oid), sink = sink)
                if (!r.isOk) {
                    val canChunk = sizeHint > 0 && CanonEos.OC_GET_PARTIAL_OBJECT in deviceInfo?.operations.orEmpty()
                    if (!canChunk) throw PtpException(r.code, "GetObject failed: 0x${r.code.toString(16)}")
                    channel.truncate(0)
                    channel.position(0)
                    var offset = 0L
                    while (offset < sizeHint) {
                        val chunk = minOf(PARTIAL_CHUNK_BYTES.toLong(), sizeHint - offset)
                        t.executeOk(CanonEos.OC_GET_PARTIAL_OBJECT, intArrayOf(oid, offset.toInt(), chunk.toInt()), sink = sink)
                        offset += chunk
                    }
                }
            }
            if (partial.length() < 4 || !startsWithJpegMagic(partial)) throw IOException("Camera returned a non-JPEG object (RAW-only?)")
            if (!partial.renameTo(target)) throw IOException("Could not move ${partial.name} to ${target.name}")
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
        return target
    }

    private fun startsWithJpegMagic(file: File): Boolean = file.inputStream().use {
        it.read() == 0xFF && it.read() == 0xD8
    }

    // ---- EOS helpers -------------------------------------------------------------------------

    private fun setEosProperty(property: Int, value: Int) {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(property).putInt(value).array()
        transport?.executeOk(CanonEos.OC_SET_DEVICE_PROP_VALUE_EX, outPayload = payload)
    }

    private fun readEosEventsLocked(): List<EosObject> {
        val t = transport ?: return emptyList()
        scratchBuffer.reset()
        val r = t.execute(CanonEos.OC_GET_EVENT, sink = scratchBuffer)
        if (!r.isOk) return emptyList()
        return parseEosEvents(scratchBuffer.array, scratchBuffer.size)
    }

    // ---- Teardown ----------------------------------------------------------------------------

    override suspend fun disconnect() {
        withContext(Dispatchers.IO + NonCancellable) {
            liveViewPaused = true
            liveViewJob?.cancelAndJoin()
            withTimeoutOrNull(SHUTDOWN_LOCK_TIMEOUT_MS) {
                ptpLock.withLock {
                    val t = transport
                    if (t != null) runCatching {
                        if (isEos) {
                            setEosProperty(CanonEos.PROP_EVF_OUTPUT_DEVICE, CanonEos.EVF_OUTPUT_OFF)
                            t.execute(CanonEos.OC_SET_EVENT_MODE, intArrayOf(0))
                            t.execute(CanonEos.OC_SET_REMOTE_MODE, intArrayOf(0))
                        }
                        t.execute(Ptp.OC_CLOSE_SESSION)
                    }
                }
            }
            releaseUsb()
            ring.clear()
            scope.cancel()
            _state.value = CameraConnectionState.Disconnected
        }
    }

    private fun releaseUsb() {
        runCatching { transport?.close() }
        transport = null
        val conn = connection
        val iface = claimedInterface
        if (conn != null) {
            if (iface != null) runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
        }
        connection = null
        claimedInterface = null
    }

    private companion object {
        const val SESSION_ID = 1
        const val FULL_PRESS_NO_AF = 3
        const val MIRROR_SETTLE_MS = 60L
        const val SHUTTER_RETRIES = 12
        const val CAPTURE_TIMEOUT_MS = 20_000L
        const val RAW_ONLY_GRACE_MS = 2_000L
        const val EVENT_POLL_MS = 40L
        const val EVENT_PUMP_INTERVAL_MS = 1_000L
        const val RESTART_AFTER_FAILURES = 15
        const val MAX_LIVE_VIEW_FAILURES = 400
        const val PARTIAL_CHUNK_BYTES = 1 shl 20
        const val SHUTDOWN_LOCK_TIMEOUT_MS = 2_000L
    }
}
