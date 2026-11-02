package com.photobooth.feature.camera

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobooth.camera.UniversalCanonManager
import com.photobooth.data.repository.EventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val manager: UniversalCanonManager,
    events: EventRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val frames = manager.liveViewFrameFlow

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { manager.connectionState.collect { s -> _state.update { it.copy(connection = s) } } }
        viewModelScope.launch { manager.liveViewRotationDegrees.collect { r -> _state.update { it.copy(rotationDegrees = r) } } }
        viewModelScope.launch { events.observeActive().collect { e -> if (e != null) _state.update { it.copy(mirror = e.mirrorLivePreview) } } }
        viewModelScope.launch {
            var count = 0
            val counter = launch { frames.collect { count++ } }
            try {
                while (true) {
                    val t0 = SystemClock.elapsedRealtime()
                    delay(1000)
                    val fps = (count * 1000f / (SystemClock.elapsedRealtime() - t0)).toInt()
                    count = 0
                    _state.update { it.copy(fps = fps) }
                }
            } finally {
                counter.cancel()
            }
        }
    }

    fun onIntent(intent: CameraIntent) {
        when (intent) {
            CameraIntent.Resume -> manager.start()
            CameraIntent.Pause -> manager.stop()
            CameraIntent.Capture -> capture()
            is CameraIntent.ForceStrategy -> {
                manager.setForcedStrategy(intent.strategy)
                _state.update { it.copy(forcedStrategy = intent.strategy) }
            }
        }
    }

    private fun capture() {
        if (_state.value.isCapturing) return
        viewModelScope.launch {
            _state.update { it.copy(isCapturing = true) }
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "captures")
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val started = SystemClock.elapsedRealtime()
            val result = manager.captureHighResPhoto(File(dir, name))
            val ui = result.fold(
                onSuccess = { CaptureUi.Success(it.absolutePath, it.length(), SystemClock.elapsedRealtime() - started) },
                onFailure = { CaptureUi.Failure(it.message ?: it.javaClass.simpleName) },
            )
            _state.update { it.copy(isCapturing = false, lastCapture = ui) }
        }
    }
}
