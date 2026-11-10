package com.photobooth.camera.camera2

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Strategy C fallback: the tablet's own camera (front preferred) through Camera2. */
class TabletCamera2Engine(context: Context) : CameraController {
    private val appContext = context.applicationContext
    private val source = Camera2Source(context, CameraSelector.TABLET_FRONT_PREFERRED)

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()
    override val liveViewFrameFlow: SharedFlow<Bitmap> = source.frames.asSharedFlow()
    override val liveViewRotationDegrees: StateFlow<Int> = source.rotationDegrees.asStateFlow()

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.Default) {
        _state.value = CameraConnectionState.Connecting("Tablet camera")
        runCatching {
            val opened = source.open()
            source.onLost = { _state.value = CameraConnectionState.Failed(it) }
            _state.value = CameraConnectionState.Connected(
                CameraDescriptor("Tablet ${opened.label}", CameraStrategy.TABLET_CAMERA, isFallback = true),
            )
            Unit
        }.onFailure {
            Log.w(TAG, "TabletCamera2Engine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "Tablet camera unavailable")
        }
    }

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching { source.captureJpeg(targetFile) }
            .onSuccess { Log.i(TAG, "TabletCamera2Engine: captured ${it.name} ${it.length()} bytes") }
            .onFailure { Log.w(TAG, "TabletCamera2Engine: capture failed: ${it.message}") }
    }

    override suspend fun prepareCapture() = source.prepareStill()

    override suspend fun readBatteryLevel(): String? {
        val percent = appContext.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (percent in 0..100) "tablet $percent%" else null
    }

    override suspend fun disconnect() {
        source.close()
        _state.value = CameraConnectionState.Disconnected
    }
}
