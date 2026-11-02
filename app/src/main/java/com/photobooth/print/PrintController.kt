package com.photobooth.print

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.PrintJobEntity
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.PrinterConnection
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.di.ApplicationScope
import com.photobooth.domain.model.LayoutType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
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
    @ApplicationContext private val context: Context,
    private val queue: PrintQueueRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)
    private val internal = MutableStateFlow(PrintStatus())

    val status: StateFlow<PrintStatus> = combine(queue.observePending(), internal) { pending, i ->
        i.copy(pendingJobs = pending.size, pendingSheets = pending.sumOf { it.copiesRemaining })
    }.stateIn(scope, SharingStarted.Eagerly, PrintStatus())

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { settings.settings.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch { runLoop() }
        Log.i(TAG, "PrintController: started")
    }

    /** Queues [requestedCopies] full 10x15 sheets of [file]. */
    suspend fun submit(eventId: String, sessionId: String?, file: File, requestedCopies: Int, layoutType: LayoutType): Long {
        val sheets = requestedCopies
        val finish = settings.settings.first().printFinish
        val id = queue.enqueue(eventId, sessionId, file.absolutePath, sheets, layoutType, finish)
        Log.i(TAG, "PrintController: job $id queued sheets=$sheets (requested=$requestedCopies $layoutType finish=$finish)")
        wake.trySend(Unit)
        return id
    }

    fun kick() {
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
            val retryAfter = process(job)
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
        if (backend == null) {
            return block(job, "Aucune imprimante configurée")
        }
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

    private suspend fun waitForWork(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private fun backendFor(s: AppSettings): PrintBackend? = when (s.printerConnection) {
        PrinterConnection.NONE -> null
        PrinterConnection.USB -> UsbPrinterBackend(context, usbManager, s.printerUsbVendorId, s.printerUsbProductId)
        PrinterConnection.NETWORK -> IppPrintBackend(context, s.printerIp, s.printerIppPort, s.printerIppPath)
        PrinterConnection.NETWORK_RAW -> RawSocketPrintBackend(context, s.printerIp, s.printerPort)
    }

    companion object {
        private const val IDLE_POLL_MS = 15_000L
        private const val RETRY_MS = 5_000L
        private const val DRY_RUN_MS = 700L
        private const val MAX_ATTEMPTS = 5
    }
}
