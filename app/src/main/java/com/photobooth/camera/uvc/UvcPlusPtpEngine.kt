package com.photobooth.camera.uvc

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraController
import com.photobooth.camera.CameraDescriptor
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.UsbBackedCamera
import com.photobooth.camera.camera2.Camera2Source
import com.photobooth.camera.camera2.CameraSelector
import com.photobooth.camera.ptp.CanonUsbPtpEngine
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Strategy C: HDMI capture card (UVC, exposed by Android as an external Camera2 device) for
 * live view, with the Canon body triggered and downloaded over USB PTP. PTP live view stays off
 * because the HDMI feed replaces it.
 */
class UvcPlusPtpEngine(
    context: Context,
    usbManager: UsbManager,
    canonDevice: UsbDevice,
) : CameraController, UsbBackedCamera {

    override val usbDeviceName: String = canonDevice.deviceName

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = Camera2Source(context, CameraSelector.EXTERNAL)
    private val ptp = CanonUsbPtpEngine(context, usbManager, canonDevice, liveViewEnabled = false)

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()
    override val liveViewFrameFlow: SharedFlow<Bitmap> = source.frames.asSharedFlow()
    override val liveViewRotationDegrees: StateFlow<Int> = source.rotationDegrees.asStateFlow()

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.Default) {
        _state.value = CameraConnectionState.Connecting("UVC capture card + Canon PTP")
        runCatching {
            val opened = source.open()
            ptp.connect().getOrElse { throw IOException("PTP trigger unavailable: ${it.message}") }
            val ptpName = (ptp.connectionState.value as? CameraConnectionState.Connected)?.camera?.name ?: "Canon"
            source.onLost = { _state.value = CameraConnectionState.Failed(it) }
            scope.launch {
                ptp.connectionState.collect { s ->
                    if (s is CameraConnectionState.Failed) _state.value = s
                }
            }
            _state.value = CameraConnectionState.Connected(
                CameraDescriptor("HDMI ${opened.label} + $ptpName", CameraStrategy.UVC_PLUS_PTP),
            )
            Unit
        }.onFailure {
            Log.w(TAG, "UvcPlusPtpEngine: connect failed: ${it.message}")
            _state.value = CameraConnectionState.Failed(it.message ?: "UVC+PTP connect failed")
            source.close()
            ptp.disconnect()
        }
    }

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> = ptp.captureHighResPhoto(targetFile)

    override suspend fun prepareCapture() = ptp.prepareCapture()

    override suspend fun deleteFromCamera(file: File): Boolean = ptp.deleteFromCamera(file)

    override suspend fun disconnect() {
        source.close()
        ptp.disconnect()
        scope.cancel()
        _state.value = CameraConnectionState.Disconnected
    }
}
