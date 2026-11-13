package com.photobooth.share

import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.frames.FrameStorage
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.data.storage.EventStorage
import com.photobooth.domain.model.LayoutType
import com.photobooth.feature.booth.frenchTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FrameLinkState {
    data object Closed : FrameLinkState
    data object Opening : FrameLinkState
    data class Open(
        val url: String,
        val expiresAtMillis: Long,
        val layouts: List<LayoutType>,
        /** Layouts whose frame arrived through this link, in order of arrival. */
        val received: List<LayoutType> = emptyList(),
        /** Last transient problem (network, bad file); polling goes on. */
        val warning: String? = null,
    ) : FrameLinkState
    data class Failed(val message: String) : FrameLinkState
}

/**
 * Receives an event's frames from the organisers' phones instead of a file picked on the tablet: opens a temporary
 * link on the Transat server (shown as a QR code), then polls it and applies every frame sent there to its layout
 * as soon as it arrives. The link carries each layout's exact geometry (calibration included) so the upload page
 * draws templates that match what the booth prints.
 */
@Singleton
class FrameReceiver @Inject constructor(
    private val api: TransatShareApi,
    private val settings: SettingsRepository,
    private val layouts: LayoutRepository,
    private val frames: FrameStorage,
    private val storage: EventStorage,
) {
    private val _state = MutableStateFlow<FrameLinkState>(FrameLinkState.Closed)
    val state: StateFlow<FrameLinkState> = _state

    /**
     * Opens a link for [event] and keeps applying what is sent to it until the link expires or the caller's
     * coroutine is cancelled (the admin closed the QR code), then closes the link. [onApplied] is told about each
     * frame applied.
     */
    suspend fun run(event: EventEntity, onApplied: (LayoutType) -> Unit) = withContext(Dispatchers.IO) {
        _state.value = FrameLinkState.Opening
        val server = server() ?: run {
            _state.value = FrameLinkState.Failed("Set up the upload server and booth key in Settings first.")
            return@withContext
        }
        val configs = layouts.observeForEvent(event.id).first()
        val offered = configs.filter { it.isEnabled }.ifEmpty { configs }
        val link = api.openFrameLink(server, event.name, event.remoteId, JSONArray(offered.map { geometry(it, event) })).getOrElse {
            Log.w(TAG, "FrameReceiver: could not open a link: ${it.message}")
            _state.value = FrameLinkState.Failed("Could not reach the Transat server: ${it.message}")
            return@withContext
        }
        Log.i(TAG, "FrameReceiver: link ${link.id} open for ${offered.size} layouts")
        _state.value = FrameLinkState.Open(link.url, link.expiresAtMillis, offered.map { it.layoutType })

        val applied = HashMap<String, String>() // layout type -> sha256 of the frame in use
        try {
            while (true) {
                val expired = poll(server, link, event, applied, onApplied)
                if (expired || System.currentTimeMillis() > link.expiresAtMillis) break
                delay(POLL_MS)
            }
        } finally {
            withContext(NonCancellable) {
                // A frame sent just before the QR code was closed is still applied.
                runCatching { poll(server, link, event, applied, onApplied) }
                api.closeFrameLink(server, link.id)
                _state.value = FrameLinkState.Closed
            }
        }
    }

    /** Clears a failure once the admin has read it (a running link is closed by cancelling [run]). */
    fun dismissFailure() {
        _state.update { if (it is FrameLinkState.Failed) FrameLinkState.Closed else it }
    }

    /** One look at the link; returns true once it no longer accepts uploads. */
    private suspend fun poll(
        server: TransatServer,
        link: FrameLink,
        event: EventEntity,
        applied: MutableMap<String, String>,
        onApplied: (LayoutType) -> Unit,
    ): Boolean {
        val status = api.frameUploads(server, link.id).getOrElse {
            warn("Connection problem, retrying… (${it.message})")
            return false
        }
        for (frame in status.uploads) {
            if (applied[frame.layoutType] == frame.sha256) continue
            val type = LayoutType.entries.firstOrNull { it.name == frame.layoutType } ?: continue
            apply(server, link, event, type, frame)
                .onSuccess {
                    applied[frame.layoutType] = frame.sha256
                    _state.update { s -> if (s is FrameLinkState.Open) s.copy(received = s.received - type + type, warning = null) else s }
                    onApplied(type)
                }
                .onFailure { warn("${type.frenchTitle()}: ${it.message}") }
        }
        return status.expired
    }

    private suspend fun apply(server: TransatServer, link: FrameLink, event: EventEntity, type: LayoutType, frame: RemoteFrame): Result<Unit> =
        runCatching {
            val config = layouts.get(event.id, type) ?: throw IOException("layout no longer exists")
            val png = api.downloadFrame(server, link.id, frame).getOrThrow()
            val file = frames.importBytes(storage.subfolder(event, EventStorage.FRAMES), config, png).getOrThrow()
            frames.delete(config.customFramePngPath)
            // A link opened for a server event also files the frame there: the next sync must not fetch it again.
            if (event.remoteId != null) layouts.setRemoteFrame(event.id, type, file.absolutePath, frame.sha256)
            else layouts.setFramePath(event.id, type, file.absolutePath)
            Log.i(TAG, "FrameReceiver: applied ${frame.width}x${frame.height} frame to $type")
        }

    private fun warn(message: String) {
        Log.w(TAG, "FrameReceiver: $message")
        _state.update { if (it is FrameLinkState.Open) it.copy(warning = message) else it }
    }

    private suspend fun server(): TransatServer? {
        val s = settings.settings.first()
        if (!s.uploadConfigured) return null
        return BoothCredential.parse(s.uploadToken)?.let { TransatServer(s.uploadBaseUrl, it) }
    }

    private fun geometry(cfg: EventLayoutConfigEntity, event: EventEntity): JSONObject =
        LayoutGeometry.json(cfg.layoutType, cfg.canvasWidthPx, cfg.canvasHeightPx, cfg.effectiveSlots(), event.photoAspect)
            .put("has_frame", cfg.customFramePngPath != null)

    private companion object {
        const val POLL_MS = 3_000L
    }
}
