package com.photobooth.camera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Every strategy takes a real still with the camera itself; the live view is only a preview. */
enum class CameraStrategy { USB_PTP, CCAPI, UVC_PLUS_PTP, TABLET_CAMERA }

data class CameraDescriptor(
    val name: String,
    val strategy: CameraStrategy,
    val isFallback: Boolean = false,
)

sealed interface CameraConnectionState {
    data object Disconnected : CameraConnectionState
    data class Connecting(val detail: String) : CameraConnectionState
    data class Connected(val camera: CameraDescriptor) : CameraConnectionState
    data class Failed(val message: String) : CameraConnectionState
}

interface CameraController {
    val connectionState: StateFlow<CameraConnectionState>

    /**
     * Live view frames, decoded off the main thread. Bitmaps come from a small reuse ring:
     * draw them immediately and never keep a reference.
     */
    val liveViewFrameFlow: SharedFlow<Bitmap>

    /**
     * Emits when the shutter really fires during [captureHighResPhoto]. DSLRs can take seconds to focus first, so the
     * booth waits for this before flashing. Engines that cannot tell stay silent and the booth flashes on the photo.
     */
    val shutterEvents: SharedFlow<Unit> get() = NoShutterEvents

    /** Clockwise rotation to apply to frames so they appear upright. */
    val liveViewRotationDegrees: StateFlow<Int>

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    /** Captures a full-resolution JPEG into [targetFile]; live view resumes automatically. */
    suspend fun captureHighResPhoto(targetFile: File): Result<File>

    /** Runs autofocus (and metering) ahead of the shutter so the shot itself is instant; failures are harmless. */
    suspend fun prepareCapture() {}

    /** Human-readable battery level of the camera body (or tablet for the fallback); null when unknown. */
    suspend fun readBatteryLevel(): String? = null

    /** Removes the camera-side copy of a photo captured into [file]; true when nothing is left on the camera. */
    suspend fun deleteFromCamera(file: File): Boolean = true
}

private val NoShutterEvents: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()

/** Implemented by engines bound to a physical USB device so the manager can react to detach events. */
interface UsbBackedCamera {
    val usbDeviceName: String
}
