package com.photobooth.data.seed

import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.repository.SessionLogRepository
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.data.storage.EventStorage
import com.photobooth.di.IoDispatcher
import com.photobooth.domain.model.LayoutType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** First-run and every-launch DB bootstrap: default event, layout presets, crash recovery. */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val events: EventRepository,
    private val layouts: LayoutRepository,
    private val printQueue: PrintQueueRepository,
    private val sessions: SessionLogRepository,
    private val settings: SettingsRepository,
    private val storage: EventStorage,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun seedIfNeeded() = withContext(io) {
        Log.i(TAG, "DbSeeder: starting")
        settings.ensureDefaultPin()

        if (events.count() == 0) {
            val event = events.createWithStorage("Default Event")
            Log.i(TAG, "DbSeeder: created default event id=${event.id} storage=${event.storageDirectoryUri}")
        }
        if (events.getActive() == null) events.getAll().firstOrNull()?.let { events.setActive(it.id) }

        events.getAll().forEach { e ->
            if (!storage.ensureStructure(storage.directoryOf(e))) Log.w(TAG, "DbSeeder: event folder not writable: ${e.storageDirectoryUri}")
            val purged = storage.purgeIncompleteSessions(e)
            if (purged > 0) Log.w(TAG, "DbSeeder: removed $purged incomplete session folder(s) of event ${e.id}")
            val added = layouts.ensureDefaults(e.id, e.photoAspect)
            if (added > 0) Log.i(TAG, "DbSeeder: added $added layout presets to event ${e.id}")
        }

        val recoveredJobs = printQueue.recoverInterrupted()
        val closedSessions = sessions.closeDanglingSessions()
        if (recoveredJobs + closedSessions > 0) {
            Log.w(TAG, "DbSeeder: recovered printJobs=$recoveredJobs, closed dangling sessions=$closedSessions")
        }

        verify()
    }

    private suspend fun verify() {
        val active = events.getActive() ?: error("No active event after seeding")
        val configs = layouts.getForEvent(active.id)
        val problems = buildList {
            if (configs.size != LayoutType.entries.size) add("expected ${LayoutType.entries.size} layouts, found ${configs.size}")
            configs.forEach { c ->
                if (c.photoSlots.size != c.layoutType.slotCount) add("${c.layoutType}: ${c.photoSlots.size} slots")
            }
        }
        Log.i(TAG, "DbSeeder: events=${events.count()} activeEvent='${active.name}' layouts=${configs.size}")
        configs.forEach {
            Log.i(
                TAG,
                "DbSeeder: layout ${it.layoutType} ${it.orientation} ${it.canvasWidthPx}x${it.canvasHeightPx} " +
                    "slots=${it.photoSlots.size} enabled=${it.isEnabled}",
            )
        }
        if (problems.isEmpty()) Log.i(TAG, "DbSeeder: VERIFIED OK") else Log.e(TAG, "DbSeeder: VERIFY FAILED $problems")
    }
}
