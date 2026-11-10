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
            transport?.trace = false
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
        Log.i(TAG, "CanonUsbPtpEngine: opening ${device.deviceName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
            "product=${device.productName} serial-readable=${usbManager.hasPermission(device)} interfaces=" +
            (0 until device.interfaceCount).joinToString { i ->
                val f = device.getInterface(i)
                "#${f.id}[class=${f.interfaceClass} sub=${f.interfaceSubclass} proto=${f.interfaceProtocol} ep=${f.endpointCount}]"
            })
        val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed (USB permission missing?)")
        connection = conn
        val endpoints = findPtpEndpoints() ?: throw IOException("No PTP bulk interface on ${device.deviceName}")
        if (!conn.claimInterface(endpoints.iface, true)) throw IOException("claimInterface failed")
        claimedInterface = endpoints.iface

        val t = PtpTransport(conn, endpoints.bulkIn, endpoints.bulkOut)
        t.trace = true
        transport = t
        Log.i(TAG, "CanonUsbPtpEngine: using interface #${endpoints.iface.id} bulkIn=0x${endpoints.bulkIn.address.toString(16)} bulkOut=0x${endpoints.bulkOut.address.toString(16)} maxPacket=${endpoints.bulkIn.maxPacketSize}")

        val infoBuffer = GrowableBuffer(4096)
        t.executeOk(Ptp.OC_GET_DEVICE_INFO, sink = infoBuffer)
        val info = PtpDeviceInfo.parse(infoBuffer.array, infoBuffer.size)
        deviceInfo = info
        Log.i(TAG, "CanonUsbPtpEngine: device manufacturer='${info.manufacturer}' model='${info.model}' vendorExt=0x${info.vendorExtensionId.toString(16)} " +
            "ops(${info.operations.size})=${info.operations.sorted().joinToString(" ") { "%04x".format(it) }}")

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
            logCameraState("after connect")
            runCatching { setEosProperty(CanonEos.PROP_CAPTURE_DESTINATION, CanonEos.CAPTURE_DEST_HOST) }
                .onFailure { Log.w(TAG, "CanonUsbPtpEngine: capture destination not set: ${it.message}") }
            // Without this the body does not accept host-side storage and shoots into the void.
            if (CanonEos.OC_PC_HDD_CAPACITY in info.operations) {
                val cap = t.execute(CanonEos.OC_PC_HDD_CAPACITY, intArrayOf(0x0FFFFFFF, 0x1000, 1))
                Log.i(TAG, "CanonUsbPtpEngine: PCHDDCapacity -> 0x${cap.code.toString(16)}")
            }
            readEosEventsLocked()
            logCameraState("after destination")
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
        Log.i(TAG, "CanonUsbPtpEngine: starting live view")
        t.trace = true
        try {
            setEosProperty(CanonEos.PROP_EVF_MODE, 1)
            setEosProperty(CanonEos.PROP_EVF_OUTPUT_DEVICE, CanonEos.EVF_OUTPUT_PC)
            if (CanonEos.OC_INITIATE_VIEWFINDER in (deviceInfo?.operations ?: emptySet())) {
                runCatching { t.execute(CanonEos.OC_INITIATE_VIEWFINDER) }
            }
        } finally {
            t.trace = false
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
            val started = SystemClock.elapsedRealtime()
            Log.i(TAG, "CanonUsbPtpEngine: capture requested -> ${targetFile.name} eos=$isEos state=${_state.value}")
            runCatching {
                check(_state.value is CameraConnectionState.Connected) { "Camera not connected" }
                liveViewPaused = true
                try {
                    ptpLock.withLock {
                        transport?.trace = true
                        try {
                            // Let the body settle so DSLRs do not answer DeviceBusy to the shutter command.
                            delay(MIRROR_SETTLE_MS)
                            if (isEos) captureEosLocked(targetFile) else captureStandardLocked(targetFile)
                        } finally {
                            transport?.trace = false
                        }
                    }
                } finally {
                    liveViewNeedsRestart = true
                    liveViewPaused = false
                }
            }.onFailure {
                Log.w(TAG, "CanonUsbPtpEngine: capture failed after ${SystemClock.elapsedRealtime() - started} ms: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private suspend fun captureEosLocked(target: File): File {
        val started = SystemClock.elapsedRealtime()
        val before = runCatching { objectHandlesLocked() }
            .onFailure { Log.w(TAG, "CanonUsbPtpEngine: could not list objects before the shot: ${it.message}") }
            .getOrNull()
        Log.i(TAG, "CanonUsbPtpEngine: objects on camera before shot: ${before?.size ?: "unknown"}")
        readEosEventsLocked(log = true) // drop stale object events
        val early = fireShutterLocked()
        val obj = awaitEosObjectLocked(before, early)
        Log.i(TAG, "CanonUsbPtpEngine: downloading oid=0x${obj.oid.toString(16)} name='${obj.name}' size=${obj.size} ofc=0x${obj.ofc.toString(16)}")
        val file = downloadObjectLocked(obj.oid, obj.size, target)
        runCatching { transport?.execute(CanonEos.OC_TRANSFER_COMPLETE, intArrayOf(obj.oid)) }
        // Release RAW companions announced after the JPEG, otherwise the body's buffer fills up.
        runCatching {
            delay(RAW_COMPANION_WAIT_MS)
            for (extra in readEosEventsLocked(log = true)) transport?.execute(CanonEos.OC_TRANSFER_COMPLETE, intArrayOf(extra.oid))
        }
        Log.i(TAG, "CanonUsbPtpEngine: captured ${obj.name} ${file.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
        return file
    }

    private fun hex(code: Int?) = code?.let { "0x" + it.toString(16) } ?: "none"

    /** Half press, wait for focus, full press, release both (the sequence the Canon firmware expects). Returns objects announced meanwhile. */
    private suspend fun fireShutterLocked(): List<EosObject> {
        val t = transport ?: throw IOException("Not connected")
        val ops = deviceInfo?.operations.orEmpty()
        Log.i(TAG, "CanonUsbPtpEngine: firing shutter remoteRelease=${CanonEos.OC_REMOTE_RELEASE_ON in ops} initiateCapture=${Ptp.OC_INITIATE_CAPTURE in ops}")
        if (CanonEos.OC_REMOTE_RELEASE_ON !in ops) {
            repeat(SHUTTER_RETRIES) {
                val r = t.execute(Ptp.OC_INITIATE_CAPTURE, intArrayOf(0, 0))
                when (r.code) {
                    Ptp.RC_OK -> return emptyList()
                    Ptp.RC_DEVICE_BUSY -> delay(MIRROR_SETTLE_MS)
                    else -> throw PtpException(r.code, "InitiateCapture failed: 0x${r.code.toString(16)}")
                }
            }
            throw PtpException(Ptp.RC_DEVICE_BUSY, "Camera stayed busy; capture not initiated")
        }
        val seen = ArrayList<EosObject>()
        val half = t.execute(CanonEos.OC_REMOTE_RELEASE_ON, intArrayOf(HALF_PRESS, 0))
        Log.i(TAG, "CanonUsbPtpEngine: half press -> ${hex(half.code)} params=${half.params.joinToString { "0x" + it.toString(16) }}")
        var pressed = false
        try {
            if (!half.isOk) throw PtpException(half.code, "Half press refused: 0x${half.code.toString(16)}")
            val focusUntil = SystemClock.elapsedRealtime() + HALF_PRESS_HOLD_MS
            while (SystemClock.elapsedRealtime() < focusUntil) {
                seen += readEosEventsLocked(log = true)
                delay(EVENT_POLL_MS)
            }
            for (attempt in 1..SHUTTER_RETRIES) {
                val full = t.execute(CanonEos.OC_REMOTE_RELEASE_ON, intArrayOf(FULL_PRESS, 0))
                Log.i(TAG, "CanonUsbPtpEngine: full press attempt $attempt -> ${hex(full.code)} params=${full.params.joinToString { "0x" + it.toString(16) }}")
                if (full.isOk) { pressed = true; break }
                if (full.code != Ptp.RC_DEVICE_BUSY && full.code != CanonEos.RC_NOT_READY) {
                    throw PtpException(full.code, "Full press failed: 0x${full.code.toString(16)}")
                }
                seen += readEosEventsLocked(log = true)
                delay(FULL_PRESS_RETRY_MS)
            }
            if (!pressed) throw PtpException(Ptp.RC_DEVICE_BUSY, "Camera stayed busy; shutter not released")
        } finally {
            if (pressed) Log.i(TAG, "CanonUsbPtpEngine: release full -> ${hex(runCatching { t.execute(CanonEos.OC_REMOTE_RELEASE_OFF, intArrayOf(FULL_PRESS)).code }.getOrNull())}")
            Log.i(TAG, "CanonUsbPtpEngine: release half -> ${hex(runCatching { t.execute(CanonEos.OC_REMOTE_RELEASE_OFF, intArrayOf(HALF_PRESS)).code }.getOrNull())}")
        }
        return seen
    }

    /** Fallback releases, tried one per call when the first sequence produced no photo. */
    private suspend fun fireFallbackLocked(stage: Int) {
        val t = transport ?: return
        val ops = deviceInfo?.operations.orEmpty()
        Log.w(TAG, "CanonUsbPtpEngine: no photo yet, fallback release #$stage")
        runCatching {
            when (stage) {
                1 -> {
                    // Some bodies refuse to shoot while the EVF is streaming to the host.
                    Log.i(TAG, "CanonUsbPtpEngine: EVF off")
                    setEosProperty(CanonEos.PROP_EVF_OUTPUT_DEVICE, CanonEos.EVF_OUTPUT_OFF)
                    delay(500)
                    val on = t.execute(CanonEos.OC_REMOTE_RELEASE_ON, intArrayOf(FULL_PRESS_NO_AF, 0))
                    delay(MIRROR_SETTLE_MS)
                    val off = t.execute(CanonEos.OC_REMOTE_RELEASE_OFF, intArrayOf(FULL_PRESS_NO_AF))
                    Log.i(TAG, "CanonUsbPtpEngine: release(3) on=${hex(on.code)} off=${hex(off.code)}")
                }
                else -> if (CanonEos.OC_REMOTE_RELEASE in ops) {
                    val r = t.execute(CanonEos.OC_REMOTE_RELEASE)
                    Log.i(TAG, "CanonUsbPtpEngine: legacy RemoteRelease -> ${hex(r.code)}")
                }
            }
        }.onFailure { Log.w(TAG, "CanonUsbPtpEngine: fallback #$stage failed: ${it.message}") }
    }

    /**
     * Waits for the new photo: first through the Canon object events, then (for bodies that announce files in a
     * format this parser does not know) by diffing the camera's object list against [before].
     */
    private suspend fun awaitEosObjectLocked(before: Set<Int>?, early: List<EosObject>): EosObject {
        val t = transport ?: throw IOException("Not connected")
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + CAPTURE_TIMEOUT_MS
        var firstOther: EosObject? = null
        var otherSeenAt = 0L
        var lastListing = 0L
        var fallbacks = 0
        var polls = 0
        var pending = early
        while (SystemClock.elapsedRealtime() < deadline) {
            polls++
            val announced = pending + readEosEventsLocked(log = true)
            pending = emptyList()
            for (obj in announced) {
                Log.i(TAG, "CanonUsbPtpEngine: object announced oid=0x${obj.oid.toString(16)} name='${obj.name}' size=${obj.size} ofc=0x${obj.ofc.toString(16)} jpeg=${obj.isJpeg}")
                if (obj.isJpeg) return obj
                runCatching { t.execute(CanonEos.OC_TRANSFER_COMPLETE, intArrayOf(obj.oid)) }
                if (firstOther == null) {
                    firstOther = obj
                    otherSeenAt = SystemClock.elapsedRealtime()
                }
            }
            val other = firstOther
            if (other != null && SystemClock.elapsedRealtime() - otherSeenAt > RAW_ONLY_GRACE_MS) {
                Log.w(TAG, "CanonUsbPtpEngine: only a non-JPEG object was announced (camera set to RAW only?), using it")
                return other
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (before != null && elapsed > LISTING_FALLBACK_AFTER_MS && SystemClock.elapsedRealtime() - lastListing > LISTING_INTERVAL_MS) {
                lastListing = SystemClock.elapsedRealtime()
                val added = runCatching { objectHandlesLocked() - before }
                    .onFailure { Log.w(TAG, "CanonUsbPtpEngine: object listing failed: ${it.message}") }
                    .getOrNull().orEmpty()
                if (added.isNotEmpty()) {
                    val infos = added.mapNotNull { objectInfoLocked(it) }
                    Log.i(TAG, "CanonUsbPtpEngine: new objects found by listing: ${infos.joinToString { "0x${it.oid.toString(16)}:${it.name}:${it.size}" }}")
                    (infos.firstOrNull { it.isJpeg } ?: infos.firstOrNull())?.let { return it }
                }
            }
            if (fallbacks < MAX_FALLBACKS && elapsed > (fallbacks + 1) * FALLBACK_INTERVAL_MS && firstOther == null) {
                fallbacks++
                fireFallbackLocked(fallbacks)
            }
            delay(EVENT_POLL_MS)
        }
        Log.w(TAG, "CanonUsbPtpEngine: no photo announced after ${SystemClock.elapsedRealtime() - startedAt} ms ($polls event polls, listingFallback=${before != null})")
        throw IOException("Timed out waiting for the camera to announce the new photo")
    }

    private fun objectInfoLocked(oid: Int): EosObject? {
        val t = transport ?: return null
        scratchBuffer.reset()
        val r = t.execute(Ptp.OC_GET_OBJECT_INFO, intArrayOf(oid), sink = scratchBuffer)
        if (!r.isOk || scratchBuffer.size < 56) {
            Log.w(TAG, "CanonUsbPtpEngine: GetObjectInfo(0x${oid.toString(16)}) -> 0x${r.code.toString(16)} ${scratchBuffer.size}B")
            return null
        }
        val rd = PtpReader(scratchBuffer.array.copyOf(scratchBuffer.size))
        rd.u32() // storage
        val format = rd.u16()
        rd.u16() // protection
        val size = rd.u32().toLong() and 0xFFFFFFFFL
        rd.u16() // thumb format
        repeat(7) { rd.u32() } // thumb size/w/h, image w/h, bit depth, parent
        rd.u16() // association type
        rd.u32() // association description
        rd.u32() // sequence number
        return EosObject(oid, format, size, rd.string())
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

    /** Logs the properties in the last GetEvent reply (the first one after connecting lists them all: mode dial, AF mode...). */
    private fun logCameraState(label: String) {
        Log.i(TAG, "CanonUsbPtpEngine: camera state ($label) ${scratchBuffer.size}B: ${summarizeEosProps(scratchBuffer.array, scratchBuffer.size)}")
    }

    private fun readEosEventsLocked(log: Boolean = false): List<EosObject> {
        val t = transport ?: return emptyList()
        scratchBuffer.reset()
        val traced = t.trace
        if (!log) t.trace = false // the event pump runs constantly; only capture-time polls are worth tracing
        val r = try { t.execute(CanonEos.OC_GET_EVENT, sink = scratchBuffer) } finally { t.trace = traced }
        if (!r.isOk) {
            if (log) Log.w(TAG, "CanonUsbPtpEngine: GetEvent failed 0x${r.code.toString(16)}")
            return emptyList()
        }
        if (log && scratchBuffer.size > 8) Log.i(TAG, "CanonUsbPtpEngine: events ${scratchBuffer.size}B${describeEosRecords(scratchBuffer.array, scratchBuffer.size)}")
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
        const val HALF_PRESS = 1
        const val FULL_PRESS = 2
        const val HALF_PRESS_HOLD_MS = 1_200L
        const val FULL_PRESS_RETRY_MS = 150L
        const val RAW_COMPANION_WAIT_MS = 300L
        const val MAX_FALLBACKS = 2
        const val FALLBACK_INTERVAL_MS = 5_000L
        const val LISTING_FALLBACK_AFTER_MS = 3_000L
        const val LISTING_INTERVAL_MS = 1_000L
        const val MIRROR_SETTLE_MS = 60L
        const val SHUTTER_RETRIES = 20
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
