package com.photobooth.share

import android.util.Log
import com.photobooth.core.EventDates
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.frames.FrameStorage
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.data.storage.EventStorage
import com.photobooth.domain.model.PhotoAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EventSyncState {
    data object Idle : EventSyncState
    /** No server or booth key in the settings: the booth only knows its own events. */
    data object NotConfigured : EventSyncState
    data object Running : EventSyncState
    data class Done(val atMillis: Long, val events: Int, val warning: String? = null) : EventSyncState
    data class Failed(val message: String) : EventSyncState
}

/**
 * Brings the events prepared in the Transat admin panel onto the booth: each one becomes (or updates) a local event
 * linked by its server id, with the server's settings, layouts switched on and frames. Server events are managed
 * from the panel: their settings are overwritten at every sync; a frame set on the tablet stays until the server's
 * frame for that layout changes. The booth also publishes its layout catalog, which the panel draws templates from.
 */
@Singleton
class EventSync @Inject constructor(
    private val api: TransatShareApi,
    private val settings: SettingsRepository,
    private val events: EventRepository,
    private val layouts: LayoutRepository,
    private val frames: FrameStorage,
    private val storage: EventStorage,
) {
    private val _state = MutableStateFlow<EventSyncState>(EventSyncState.Idle)
    val state: StateFlow<EventSyncState> = _state
    private val mutex = Mutex()

    /** One full sync; concurrent calls wait for the running one. */
    suspend fun sync() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val server = server() ?: run {
                _state.value = EventSyncState.NotConfigured
                return@withContext
            }
            _state.value = EventSyncState.Running
            try {
                api.publishLayouts(server, LayoutGeometry.catalog()).onFailure { Log.w(TAG, "EventSync: catalog not published: ${it.message}") }
                val remote = api.events(server).getOrElse {
                    Log.w(TAG, "EventSync: could not fetch events: ${it.message}")
                    _state.value = EventSyncState.Failed("Serveur Transat injoignable : ${it.message}")
                    return@withContext
                }
                val problems = remote.mapNotNull { r ->
                    try {
                        apply(server, r)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "EventSync: event ${r.id} failed: ${e.message}")
                        "${r.name} : ${e.message}"
                    }
                }
                Log.i(TAG, "EventSync: ${remote.size} server events, ${problems.size} problems")
                _state.value = EventSyncState.Done(System.currentTimeMillis(), remote.size, problems.firstOrNull())
            } finally {
                // Cancelled (the start screen stops waiting after a while): the next sync picks up where this one was.
                if (_state.value == EventSyncState.Running) _state.value = EventSyncState.Failed("Synchronisation interrompue")
            }
        }
    }

    /**
     * Makes today's event the running one, unless the running event already takes place today. Among several
     * events of the day, one prepared in the admin panel wins, then the most recently created. Returns the event
     * selected, or null when nothing changed.
     */
    suspend fun selectToday(): EventEntity? {
        val today = EventDates.todayUtc()
        val all = events.getAll()
        if (all.firstOrNull { it.isActive }?.eventDate == today) return null
        val pick = all.filter { it.eventDate == today }
            .sortedWith(compareByDescending<EventEntity> { it.remoteId != null }.thenByDescending { it.createdAt })
            .firstOrNull() ?: return null
        events.setActive(pick.id)
        Log.i(TAG, "EventSync: today's event \"${pick.name}\" selected")
        return pick
    }

    private suspend fun apply(server: TransatServer, r: RemoteEvent) {
        val aspect = PhotoAspect.entries.firstOrNull { it.name == r.photoAspect } ?: PhotoAspect.RATIO_4_3
        val date = LocalDate.parse(r.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val existing = events.getByRemoteId(r.id)
        val local = existing ?: events.createWithStorage(r.name, date, makeActive = false, photoAspect = aspect, remoteId = r.id)
        val updated = local.copy(
            name = r.name,
            eventDate = date,
            photoAspect = aspect,
            countdownSeconds = r.countdownSeconds,
            maxPrintsPerSession = r.maxPrintsPerSession,
            allowRetakes = r.allowRetakes,
            enableDigitalQRShare = r.digitalShare,
            attractSlideshow = r.attractSlideshow,
            mirrorLivePreview = r.mirrorLivePreview,
        )
        if (updated != local) events.update(updated)
        if (local.photoAspect != aspect) layouts.applyAspect(local.id, aspect)
        layouts.ensureDefaults(local.id, aspect)

        val byType = r.layouts.associateBy { it.type }
        for (cfg in layouts.getForEvent(local.id)) {
            val remote = byType[cfg.layoutType.name]
            val on = remote?.enabled == true
            if (cfg.isEnabled != on) layouts.setEnabled(local.id, cfg.layoutType, on)
            val sha = remote?.frameSha256
            when {
                sha != null && !sha.equals(cfg.remoteFrameSha256, ignoreCase = true) -> {
                    val png = api.downloadEventFrame(server, r.id, cfg.layoutType.name, sha).getOrThrow()
                    val file = frames.importBytes(storage.subfolder(updated, EventStorage.FRAMES), cfg, png).getOrThrow()
                    frames.delete(cfg.customFramePngPath)
                    layouts.setRemoteFrame(local.id, cfg.layoutType, file.absolutePath, sha)
                }
                // Removed in the panel: the layout goes back to no frame.
                sha == null && cfg.remoteFrameSha256 != null -> {
                    frames.delete(cfg.customFramePngPath)
                    layouts.setRemoteFrame(local.id, cfg.layoutType, null, null)
                }
            }
        }
    }

    private suspend fun server(): TransatServer? {
        val s = settings.settings.first()
        if (!s.uploadConfigured) return null
        return BoothCredential.parse(s.uploadToken)?.let { TransatServer(s.uploadBaseUrl, it) }
    }
}
