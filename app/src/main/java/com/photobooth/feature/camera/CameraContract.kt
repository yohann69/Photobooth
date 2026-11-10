package com.photobooth.feature.camera

import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraStrategy

sealed interface CaptureUi {
    data class Success(val path: String, val bytes: Long, val millis: Long) : CaptureUi
    data class Failure(val message: String) : CaptureUi
}

data class CameraUiState(
    val connection: CameraConnectionState = CameraConnectionState.Disconnected,
    val rotationDegrees: Int = 0,
    val mirror: Boolean = false,
    val fps: Int = 0,
    val isCapturing: Boolean = false,
    val lastCapture: CaptureUi? = null,
    val forcedStrategy: CameraStrategy? = null,
)

sealed interface CameraIntent {
    data object Resume : CameraIntent
    data object Pause : CameraIntent
    data object Capture : CameraIntent
    data class ForceStrategy(val strategy: CameraStrategy?) : CameraIntent
}
