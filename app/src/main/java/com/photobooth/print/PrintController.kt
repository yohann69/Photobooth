package com.photobooth.print

import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.PrintFinish
import com.photobooth.data.db.entity.PrintJobEntity
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.di.ApplicationScope
import com.photobooth.domain.model.LayoutType
import com.photobooth.print.dnp.DnpOrientation
import com.photobooth.print.dnp.DnpPrinterLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class PrintState { IDLE, PRINTING, BLOCKED, DRY_RUN }

data class PrintStatus(
    val state: PrintState = PrintState.IDLE,
    val pendingJobs: Int = 0,
    val pendingSheets: Int = 0,
    val message: String? = null,
    val jobs: List<PrintJobEntity> = emptyList(),
) {
    /** True when jobs are waiting on a problem an operator should look at. */
    val needsAttention: Boolean get() = state == PrintState.BLOCKED && pendingJobs > 0
}

/**
 * Silent print worker. Jobs live in Room; the worker picks the oldest, checks the printer, sends only the
 * sheets still missing, and keeps retrying by itself after paper-out / disconnection until they are printed.
 */
@Singleton
class PrintController @Inject constructor(
    private val queue: PrintQueueRepository,
    private val settings: SettingsRepository,
    private val dnp: DnpPrinterLink,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val internal = MutableStateFlow(PrintStatus())

    val status: StateFlow<PrintStatus> = combine(queue.observePending(), internal) { pending, i ->
        i.copy(pendingJobs = pending.size, pendingSheets = pending.sumOf { it.copiesRemaining }, jobs = pending)
    }.stateIn(scope, SharingStarted.Eagerly, PrintStatus())

    // Held so a cancel can stop the job in flight; the id tells which job it is.
    @Volatile private var active: Pair<Long, Deferred<Long>>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { settings.settings.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch { runLoop() }
        scope.launch { monitorPaper() }
        Log.i(TAG, "PrintController: started")
    }

    /** Queues [requestedCopies] full 10x15 sheets of [file]. */
    suspend fun submit(eventId: String, sessionId: String?, file: File, requestedCopies: Int, layoutType: LayoutType): Long {
        val sheets = requestedCopies
        val id = queue.enqueue(eventId, sessionId, file.absolutePath, sheets, layoutType, PrintFinish.GLOSSY)
        Log.i(TAG, "PrintController: job $id queued sheets=$sheets (requested=$requestedCopies $layoutType)")
        wake.trySend(Unit)
        return id
    }

    fun kick() {
        wake.trySend(Unit)
    }

    /** Cancels one queued job; if it is the one being printed, the printer is told to stop first. */
    suspend fun cancel(jobId: Long) {
        active?.takeIf { it.first == jobId }?.second?.cancelAndJoin()
        queue.cancel(jobId)
        Log.i(TAG, "PrintController: job $jobId cancelled")
        wake.trySend(Unit)
    }

    suspend fun cancelAll() {
        active?.second?.cancelAndJoin()
        val n = queue.cancelAll()
        Log.i(TAG, "PrintController: $n job(s) cancelled")
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        queue.recoverInterrupted()
        while (true) {
            val job = queue.nextRunnable()
            if (job == null) {
                internal.value = PrintStatus()
                waitForWork(IDLE_POLL_MS)
                continue
            }
            val work = scope.async(start = CoroutineStart.LAZY) { process(job) }
            active = job.id to work
            work.start()
            val retryAfter = try {
                work.await()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                internal.value = PrintStatus()
                0L
            } finally {
                active = null
            }
            Log.d(TAG, "PrintController: job ${job.id} processed, next look in ${retryAfter}ms")
            if (retryAfter > 0) waitForWork(retryAfter)
        }
    }

    /** Handles one job; returns how long to wait before looking at the queue again (0 = immediately). */
    private suspend fun process(job: PrintJobEntity): Long {
        val s = settings.settings.first()
        val file = File(job.imagePath)
        if (!file.isFile) {
            queue.markFailed(job, "Fichier introuvable")
            return 0
        }

        if (s.printDryRun) {
            internal.value = PrintStatus(PrintState.DRY_RUN, message = "Mode simulation: aucune impression réelle")
            delay(DRY_RUN_MS)
            queue.markSimulated(job)
            Log.i(TAG, "PrintController: job ${job.id} simulated (dry-run), ${job.copiesRemaining} sheet(s)")
            return 0
        }

        val backend = backendFor(s)
        when (val readiness = backend.check()) {
            is PrinterReadiness.NotReady -> return block(job, readiness.reason)
            PrinterReadiness.Ready -> Log.d(TAG, "PrintController: ${backend.label} ready")
        }

        queue.markPrinting(job)
        var latest = job.copy(attempts = job.attempts + 1)
        var printed = job.copiesPrinted
        internal.value = PrintStatus(PrintState.PRINTING, message = "Impression en cours (${backend.label})")
        val request = PrintRequest(file, job.copiesRemaining, job.finish, job.layoutType, "photobooth-${job.id}")
        Log.i(TAG, "PrintController: job ${job.id} -> ${backend.label}, sending ${request.copies} sheet(s)")

        val result = backend.print(request) { delta ->
            printed += delta
            latest = queue.recordProgress(latest, printed)
        }
        return when (result) {
            PrintResult.Success -> {
                queue.markDone(latest)
                Log.i(TAG, "PrintController: job ${job.id} done (${job.copies} sheet(s))")
                0
            }
            is PrintResult.Partial -> block(latest, result.reason)
            is PrintResult.Blocked -> block(latest, result.reason)
            is PrintResult.Failed -> {
                Log.w(TAG, "PrintController: job ${job.id} failed: ${result.message}")
                if (latest.attempts >= MAX_ATTEMPTS) {
                    queue.markFailed(latest, result.message)
                    0
                } else {
                    block(latest, result.message)
                }
            }
        }
    }

    private suspend fun block(job: PrintJobEntity, reason: String): Long {
        if (job.lastError != reason || job.status != com.photobooth.data.db.entity.PrintJobStatus.QUEUED) queue.markWaiting(job, reason)
        if (internal.value.message != reason) Log.w(TAG, "PrintController: waiting - $reason")
        internal.value = PrintStatus(PrintState.BLOCKED, message = reason)
        return RETRY_MS
    }

    /** Keeps the printer's paper count and status fresh for the admin panel while the USB printer is selected. */
    private suspend fun monitorPaper() {
        while (true) {
            dnp.refresh(askPermission = false)
            delay(PAPER_POLL_MS)
        }
    }

    private suspend fun waitForWork(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private fun backendFor(s: AppSettings): PrintBackend =
        DnpUsbBackend(dnp, DnpOrientation(s.dnpFlipVertical, s.dnpMirror, s.dnpPortraitClockwise))

    companion object {
        private const val IDLE_POLL_MS = 15_000L
        private const val PAPER_POLL_MS = 10_000L
        private const val RETRY_MS = 5_000L
        private const val DRY_RUN_MS = 700L
        private const val MAX_ATTEMPTS = 5
    }
}
