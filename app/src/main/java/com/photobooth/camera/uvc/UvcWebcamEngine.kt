package com.photobooth.camera.uvc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.camera2.Camera2Source
import com.photobooth.camera.camera2.CameraSelector
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A camera that shows up on USB as a plain UVC webcam (Canon bodies in "USB streaming" mode expose only video and
 * audio interfaces, no PTP): Android lists it as an external Camera2 device, used for both live view and stills.
 */
class UvcWebcamEngine(private val context: Context) : CameraController {
    private var source: Camera2Source? = null

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveViewFrameFlow: SharedFlow<Bitmap> = frames.asSharedFlow()
    private val rotation = MutableStateFlow(0)
    override val liveViewRotationDegrees: StateFlow<Int> = rotation.asStateFlow()

    private var forwarders: List<Job> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.Default) {
        _state.value = CameraConnectionState.Connecting("USB webcam")
        runCatching {
            // One physical camera can appear as several external ids; take the first one that actually opens.
            val failures = mutableListOf<String>()
            for (id in Camera2Source.externalCameraIds(context)) {
                val candidate = Camera2Source(context, CameraSelector.EXTERNAL, id)
                try {
                    val opened = candidate.open()
                    Log.i(TAG, "UvcWebcamEngine: opened external camera $id preview=${opened.previewSize} still=${opened.stillSize}")
                    source = candidate
                    forwarders = listOf(
                        scope.launch { candidate.frames.collect { frames.tryEmit(it) } },
                        scope.launch { candidate.rotationDegrees.collect { rotation.value = it } },
                    )
                    candidate.onLost = { _state.value = CameraConnectionState.Failed(it) }
                    _state.value = CameraConnectionState.Connected(CameraDescriptor("USB webcam ${opened.label}", CameraStrategy.UVC_WEBCAM))
                    return@runCatching
                } catch (e: Exception) {
                    failures += "$id: ${e.message}"
                    candidate.close()
                }
            }
            throw IOException("No usable external camera (${failures.joinToString("; ").ifEmpty { "none listed" }})")
        }.onFailure {
            Log.w(TAG, "UvcWebcamEngine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "USB webcam unavailable")
        }
    }

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        val current = source ?: return@withContext Result.failure(IllegalStateException("USB webcam not connected"))
        runCatching { current.captureJpeg(targetFile) }
            .onSuccess { Log.i(TAG, "UvcWebcamEngine: captured ${it.name} ${it.length()} bytes") }
            .onFailure { Log.w(TAG, "UvcWebcamEngine: capture failed: ${it.message}") }
    }

    override suspend fun disconnect() {
        forwarders.forEach { it.cancel() }
        forwarders = emptyList()
        source?.close()
        source = null
        _state.value = CameraConnectionState.Disconnected
    }
}
