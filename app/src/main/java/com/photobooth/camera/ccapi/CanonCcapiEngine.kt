package com.photobooth.camera.ccapi

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.FrameBitmapRing
import com.photobooth.camera.findJpegStart
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Strategy B: Canon Camera Control API over HTTP (Wi-Fi or USB-Ethernet). Capable of both
 * ver100 (EOS 90D/850D) and ver110/ver120 (EOS R-series) firmware: endpoints are resolved from
 * the GET /ccapi discovery document.
 */
class CanonCcapiEngine(
    context: Context,
    private val host: String,
    private val port: Int = 8080,
) : CameraController {

    private val client = CcapiClient(context, host, port)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val ring = FrameBitmapRing()

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveViewFrameFlow: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _rotation = MutableStateFlow(0)
    override val liveViewRotationDegrees: StateFlow<Int> = _rotation.asStateFlow()

    @Volatile private var liveViewPaused = false
    private val remoteContent = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var liveViewNeedsRestart = false
    private var liveViewJob: Job? = null

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        _state.value = CameraConnectionState.Connecting("CCAPI $host:$port")
        runCatching {
            client.loadApiMap()
            val name = readCameraName()
            Log.i(TAG, "CanonCcapiEngine: $name at $host:$port api=${client.supportedVersions()} liveview=${client.resolve(LIVEVIEW_FLIP)}")
            startLiveViewRequest()
            _state.value = CameraConnectionState.Connected(CameraDescriptor(name, CameraStrategy.CCAPI))
            launchLiveViewLoop()
            Unit
        }.onFailure {
            Log.w(TAG, "CanonCcapiEngine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "CCAPI connect failed")
        }
    }

    private fun readCameraName(): String {
        val r = runCatching { client.request("GET", client.resolve("deviceinformation")) }.getOrNull()
        if (r != null && r.isSuccess) {
            val json = runCatching { JSONObject(r.text()) }.getOrNull()
            val product = json?.optString("productname").orEmpty()
            if (product.isNotBlank()) return if (product.startsWith("Canon", ignoreCase = true)) product else "Canon $product"
        }
        return "Canon camera (CCAPI)"
    }

    private fun startLiveViewRequest() {
        val r = client.request(
            "POST",
            client.resolve("shooting/liveview"),
            """{"liveviewsize":"small","cameradisplay":"off"}""",
        )
        if (!r.isSuccess) Log.w(TAG, "CanonCcapiEngine: liveview start HTTP ${r.code}: ${r.text().take(120)}")
    }

    private fun launchLiveViewLoop() {
        liveViewJob = scope.launch {
            var failures = 0
            val flipPath = client.resolve(LIVEVIEW_FLIP)
            while (isActive) {
                if (liveViewPaused) {
                    delay(15)
                    continue
                }
                try {
                    if (liveViewNeedsRestart) {
                        liveViewNeedsRestart = false
                        startLiveViewRequest()
                    }
                    val r = client.request("GET", flipPath, readTimeoutMs = 2500)
                    val start = if (r.isSuccess) findJpegStart(r.body, r.body.size) else -1
                    if (start >= 0) {
                        failures = 0
                        ring.decode(r.body, start, r.body.size - start)?.let { _frames.tryEmit(it) }
                        continue
                    }
                    failures++
                } catch (e: IOException) {
                    failures++
                    if (failures % 10 == 0) Log.w(TAG, "CanonCcapiEngine: live view error: ${e.message}")
                }
                if (failures % 20 == 0 && failures > 0) liveViewNeedsRestart = true
                if (failures > MAX_LIVE_VIEW_FAILURES) {
                    _state.value = CameraConnectionState.Failed("CCAPI connection lost")
                    return@launch
                }
                delay(60)
            }
        }
    }

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        captureMutex.withLock {
            runCatching {
                check(_state.value is CameraConnectionState.Connected) { "Camera not connected" }
                val started = SystemClock.elapsedRealtime()
                liveViewPaused = true
                try {
                    delay(SETTLE_MS)
                    drainEvents()
                    pressShutter()
                    val contentPath = awaitAddedJpeg()
                    client.download(contentPath, targetFile).also {
                        remoteContent[it.absolutePath] = contentPath
                        Log.i(TAG, "CanonCcapiEngine: captured ${it.name} ${it.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
                    }
                } finally {
                    liveViewNeedsRestart = true
                    liveViewPaused = false
                }
            }.onFailure { Log.w(TAG, "CanonCcapiEngine: capture failed: ${it.message}") }
        }
    }

    private suspend fun pressShutter() {
        val path = client.resolve("shooting/control/shutterbutton")
        repeat(SHUTTER_RETRIES) {
            val r = client.request("POST", path, """{"af":false}""")
            when {
                r.isSuccess -> return
                r.code == 503 -> delay(SETTLE_MS * 2) // camera busy
                else -> throw IOException("Shutter rejected: HTTP ${r.code} ${r.text().take(120)}")
            }
        }
        throw IOException("Camera stayed busy; shutter not released")
    }

    private fun pollEvents(readTimeoutMs: Int): JSONObject? = try {
        val r = client.request("GET", client.resolve("event/polling"), readTimeoutMs = readTimeoutMs)
        if (r.isSuccess && r.body.isNotEmpty()) JSONObject(r.text()) else null
    } catch (_: SocketTimeoutException) {
        null
    }

    private fun drainEvents() {
        runCatching { pollEvents(readTimeoutMs = 500) }
    }

    private suspend fun awaitAddedJpeg(): String {
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        var firstOther: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val added = pollEvents(readTimeoutMs = 2000)?.optJSONArray("addedcontents")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val path = added.optString(i)
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (ext == "jpg" || ext == "jpeg") return path
                    if (firstOther == null && path.isNotEmpty()) firstOther = path
                }
                if (firstOther != null) throw IOException("Camera produced no JPEG (RAW only): $firstOther")
            }
            delay(EVENT_POLL_MS)
        }
        throw IOException("Timed out waiting for the camera to report the new photo")
    }

    override suspend fun deleteFromCamera(file: File): Boolean = withContext(Dispatchers.IO) {
        val path = remoteContent.remove(file.absolutePath) ?: return@withContext true
        runCatching { client.request("DELETE", path, readTimeoutMs = 3000).isSuccess }.getOrDefault(false)
            .also { Log.i(TAG, "CanonCcapiEngine: delete $path from camera -> $it") }
    }

    override suspend fun readBatteryLevel(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = client.request("GET", client.resolve("devicestatus/battery"), readTimeoutMs = 1200)
            if (!r.isSuccess) return@runCatching null
            val json = JSONObject(r.text())
            json.optString("level").takeIf { it.isNotBlank() }?.let { level ->
                val quality = json.optString("quality")
                if (quality.isBlank()) level else "$level ($quality)"
            }
        }.getOrNull()
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO + NonCancellable) {
            liveViewPaused = true
            liveViewJob?.cancelAndJoin()
            runCatching {
                client.request("POST", client.resolve("shooting/liveview"), """{"liveviewsize":"off"}""", readTimeoutMs = 1000)
            }
            ring.clear()
            scope.cancel()
            _state.value = CameraConnectionState.Disconnected
        }
    }

    private companion object {
        const val LIVEVIEW_FLIP = "shooting/liveview/flip"
        const val SETTLE_MS = 60L
        const val SHUTTER_RETRIES = 12
        const val CAPTURE_TIMEOUT_MS = 20_000L
        const val EVENT_POLL_MS = 100L
        const val MAX_LIVE_VIEW_FAILURES = 300
    }
}
