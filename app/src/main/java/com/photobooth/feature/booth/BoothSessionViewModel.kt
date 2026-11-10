package com.photobooth.feature.booth

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobooth.audio.BoothAudio
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.UniversalCanonManager
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.db.entity.SessionLogEntity
import com.photobooth.data.db.entity.SessionOutcome
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.repository.SessionLogRepository
import com.photobooth.print.PrintController
import com.photobooth.print.PrintStatus
import com.photobooth.data.storage.EventStorage
import com.photobooth.render.DummyPhotos
import com.photobooth.render.PhotoCompositorEngine
import com.photobooth.render.PhotoDecoder
import com.photobooth.render.PhotoSupplier
import com.photobooth.domain.model.LayoutType
import com.photobooth.share.QrCodes
import com.photobooth.share.UploadContext
import com.photobooth.share.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BoothSessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val camera: UniversalCanonManager,
    private val events: EventRepository,
    private val layoutRepository: LayoutRepository,
    private val printQueue: PrintQueueRepository,
    private val sessionLogs: SessionLogRepository,
    private val storage: EventStorage,
    private val compositor: PhotoCompositorEngine,
    private val uploads: UploadManager,
    private val printController: PrintController,
    private val audio: BoothAudio,
) : ViewModel() {

    val frames = camera.liveViewFrameFlow

    private val _state = MutableStateFlow(BoothUiState())
    val state: StateFlow<BoothUiState> = _state.asStateFlow()

    private var session: SessionLogEntity? = null
    private var sessionFinished = false
    private var retakes = 0
    private var countdownJob: Job? = null
    private var renderJob: Job? = null
    private var thanksJob: Job? = null
    @Volatile private var capturingFile: File? = null
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()

    init {
        audio.prepare()
        viewModelScope.launch {
            camera.connectionState.collect { s -> _state.update { it.copy(cameraReady = s is CameraConnectionState.Connected) } }
        }
        viewModelScope.launch { camera.liveViewRotationDegrees.collect { r -> _state.update { it.copy(rotationDegrees = r) } } }
        viewModelScope.launch {
            printController.status.collect { st: PrintStatus -> _state.update { it.copy(printWaiting = st.needsAttention) } }
        }
        viewModelScope.launch {
            events.observeActive().collect { e ->
                _state.update { it.copy(event = e, slideshowEnabled = e?.attractSlideshow ?: true) }
                if (_state.value.step == BoothStep.ATTRACT) refreshSlideshow()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val step = _state.value.step
                if (step in IDLE_GUARDED && SystemClock.elapsedRealtime() - lastInteraction > IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "BoothSession: idle timeout in $step, returning to attract")
                    resetToAttract(abandon = true)
                }
            }
        }
    }

    fun onIntent(intent: BoothIntent) {
        if (intent != BoothIntent.Abort) lastInteraction = SystemClock.elapsedRealtime()
        when (intent) {
            BoothIntent.StartSession -> startSession()
            is BoothIntent.SelectLayout -> _state.value.layouts.firstOrNull { it.layoutType == intent.type }?.let(::chooseLayout)
            BoothIntent.TakePhoto -> if (_state.value.step == BoothStep.LIVE) beginCountdown()
            BoothIntent.KeepPhoto -> keepPhoto()
            BoothIntent.RetakePhoto -> retakePhoto()
            is BoothIntent.SetCopies -> _state.update { it.copy(copies = intent.copies.coerceIn(0, it.maxCopies)) }
            BoothIntent.ConfirmPrint -> confirmPrint()
            BoothIntent.BackToStart -> resetToAttract(abandon = true)
            BoothIntent.Abort -> if (_state.value.step != BoothStep.ATTRACT) resetToAttract(abandon = true)
        }
    }

    // ---- Session start & layout choice -------------------------------------------------------

    private fun startSession() {
        val s = _state.value
        if (s.step != BoothStep.ATTRACT) return
        val event = s.event ?: return post("Aucun événement actif")
        if (!s.cameraReady) return post("Caméra en cours d'initialisation…")
        viewModelScope.launch {
            val enabled = layoutRepository.getForEvent(event.id).filter { it.isEnabled }
            if (enabled.isEmpty()) return@launch post("Aucune composition activée par l'administrateur")
            retakes = 0
            sessionFinished = false
            session = sessionLogs.start(event.id, null)
            Log.i(TAG, "BoothSession: started ${session?.id} event='${event.name}' layouts=${enabled.size}")
            _state.update {
                it.copy(
                    layouts = enabled,
                    maxCopies = event.maxPrintsPerSession.coerceIn(1, MAX_COPIES),
                    copies = 1,
                    finalPreview = null,
                    share = null,
                    message = null,
                )
            }
            if (enabled.size == 1) {
                Log.i(TAG, "BoothSession: single layout enabled, skipping selection")
                chooseLayout(enabled.first())
            } else {
                go(BoothStep.SELECT_LAYOUT)
                renderLayoutPreviews(enabled)
            }
        }
    }

    private fun renderLayoutPreviews(layouts: List<EventLayoutConfigEntity>) {
        viewModelScope.launch(Dispatchers.Default) {
            val previews = layouts.associate { cfg ->
                cfg.layoutType to compositor.render(
                    cfg,
                    PREVIEW_CARD_WIDTH_PX.toFloat() / cfg.canvasWidthPx,
                    PhotoSupplier { i, w, h -> DummyPhotos.create(i, w, h) },
                )
            }
            _state.update { it.copy(layoutPreviews = previews) }
        }
    }

    private fun chooseLayout(cfg: EventLayoutConfigEntity) {
        session = session?.copy(layoutType = cfg.layoutType)?.also { s -> viewModelScope.launch { sessionLogs.update(s) } }
        _state.update {
            it.copy(layout = cfg, photos = List(cfg.photoCount) { null }, slotIndex = 0, reviewFile = null)
        }
        Log.i(TAG, "BoothSession: layout=${cfg.layoutType} shots=${cfg.photoCount} slots=${cfg.photoSlots.size}")
        go(BoothStep.LIVE)
    }

    // ---- Countdown & capture -----------------------------------------------------------------

    private fun beginCountdown() {
        if (countdownJob?.isActive == true) return
        val s = _state.value
        val event = s.event ?: return
        if (!s.cameraReady) return post("Caméra indisponible")
        countdownJob = viewModelScope.launch {
            val seconds = event.countdownSeconds.coerceAtLeast(1)
            _state.update { it.copy(smile = false, message = null) }
            go(BoothStep.COUNTDOWN)
            var focus: Job? = null
            for (n in seconds downTo 1) {
                _state.update { it.copy(countdown = n) }
                if (n == minOf(seconds, FOCUS_AT_SECOND)) {
                    focus = launch { runCatching { camera.prepareCapture() } }
                }
                audio.tick(n)
                delay(1_000)
            }
            _state.update { it.copy(smile = true, countdown = 0) }
            audio.smile()
            delay(SMILE_MS)
            focus?.join()

            val slot = _state.value.slotIndex
            val file = newPhotoFile(event, slot)
            capturingFile = file
            go(BoothStep.CAPTURING)
            camera.captureHighResPhoto(file)
                .onSuccess {
                    Log.i(TAG, "BoothSession: slot ${slot + 1} captured ${it.name} ${it.length()} bytes")
                    uploadContext()?.let { ctx -> uploads.scheduleShot(ctx, slot + 1, it) }
                    val composition = renderComposition(it, slot)
                    _state.update { cur -> cur.copy(reviewFile = it, reviewPreview = composition, smile = false) }
                    go(BoothStep.REVIEW)
                }
                .onFailure {
                    Log.w(TAG, "BoothSession: capture failed: ${it.message}")
                    post("La photo n'a pas pu être prise, réessayez")
                    _state.update { cur -> cur.copy(smile = false) }
                    go(BoothStep.LIVE)
                }
        }
    }

    /** Raws are captured straight into the session folder, so aborting only has to delete that folder. */
    private fun newPhotoFile(event: EventEntity, slot: Int): File =
        File(storage.sessionOriginals(event, session?.id ?: "unassigned"), "Photo_${slot + 1}.jpg")

    /** Final-layout composition with [newPhoto] in slot [newIndex] (null = validated photos only); null if rendering failed. */
    private suspend fun renderComposition(newPhoto: File?, newIndex: Int): Bitmap? {
        val s = _state.value
        val cfg = s.layout ?: return null
        return runCatching {
            withContext(Dispatchers.Default) {
                compositor.renderProgressivePreview(cfg, FINAL_PREVIEW_SCALE, s.photos, newPhoto, newIndex)
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.e(TAG, "BoothSession: progressive preview failed", it)
        }.getOrNull()
    }

    // ---- Review ------------------------------------------------------------------------------

    private fun keepPhoto() {
        val s = _state.value
        val file = s.reviewFile ?: return
        if (s.step != BoothStep.REVIEW) return
        val photos = s.photos.toMutableList().also { it[s.slotIndex] = file }
        val next = photos.indexOfFirst { it == null }
        Log.i(TAG, "BoothSession: kept ${file.name} for slot ${s.slotIndex + 1}/${photos.size}")
        _state.update { it.copy(photos = photos, reviewFile = null, reviewPreview = null, slotIndex = if (next >= 0) next else it.slotIndex) }
        if (next >= 0) {
            go(BoothStep.LIVE)
        } else {
            go(BoothStep.FINAL)
            renderFinalPreview()
        }
    }

    private fun retakePhoto() {
        val s = _state.value
        val file = s.reviewFile ?: return
        if (s.step != BoothStep.REVIEW || s.event?.allowRetakes == false) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { file.delete() }
            val clearedOnCamera = camera.deleteFromCamera(file)
            Log.i(TAG, "BoothSession: rejected ${file.name} deletedFromTablet=$deleted existsAfter=${file.exists()} cameraCopyRemoved=$clearedOnCamera")
            retakes++
            session?.let { uploads.cancelShot(it.id, s.slotIndex + 1) }
            val without = renderComposition(null, s.slotIndex)
            Log.i(TAG, "BoothSession: progressive composition re-rendered without ${file.name} (ok=${without != null})")
            _state.update { it.copy(reviewFile = null, reviewPreview = without) }
            go(BoothStep.LIVE)
            delay(RETAKE_COUNTDOWN_DELAY_MS)
            beginCountdown()
        }
    }

    // ---- Final composition ------------------------------------------------------------------

    private fun photoSupplier(photos: List<File?>) =
        PhotoSupplier { index, w, h -> photos.getOrNull(index)?.let { PhotoDecoder.decodeCrop(it, w, h) } }

    private fun renderFinalPreview() {
        val s = _state.value
        val cfg = s.layout ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _state.update { it.copy(isRendering = true) }
            val bitmap = runCatching {
                withContext(Dispatchers.Default) {
                    compositor.render(cfg, FINAL_PREVIEW_SCALE, photoSupplier(s.photos))
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.e(TAG, "BoothSession: preview render failed", it)
            }.getOrNull()
            _state.update { it.copy(finalPreview = bitmap ?: it.finalPreview, isRendering = false) }
        }
    }

    private fun confirmPrint() {
        val s = _state.value
        val event = s.event ?: return
        val cfg = s.layout ?: return
        val current = session ?: return
        if (s.step != BoothStep.FINAL) return
        renderJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(step = BoothStep.PRINTING, isRendering = true, thanksSecondsLeft = THANKS_SECONDS) }
            go(BoothStep.PRINTING)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val montage = File(storage.sessionDirectory(event, current.id), EventStorage.MONTAGE_FILE)
            val printFile = File(storage.subfolder(event, EventStorage.COMPOSITED_PRINTS), "PRINT_${stamp}_${cfg.layoutType.name}.jpg")
            val rendered = runCatching {
                withContext(Dispatchers.Default) {
                    compositor.renderPrint(cfg, s.photos, montage)
                    montage.copyTo(printFile, overwrite = true)
                }
            }
            if (rendered.isFailure) {
                // Without a montage the session counts as incomplete, never as validated.
                montage.delete()
                printFile.delete()
                Log.e(TAG, "BoothSession: final render failed", rendered.exceptionOrNull())
                post("La composition a échoué, réessayez")
                _state.update { it.copy(isRendering = false) }
                go(BoothStep.FINAL)
                return@launch
            }

            if (s.copies > 0) {
                val jobId = printController.submit(event.id, current.id, printFile, s.copies, cfg.layoutType)
                Log.i(TAG, "BoothSession: print job $jobId queued copies=${s.copies} file=${printFile.name}")
            } else {
                Log.i(TAG, "BoothSession: no print requested, digital copy only (file=${printFile.name})")
            }

            val photoFiles = s.photos.filterNotNull()
            MediaScannerConnection.scanFile(
                context, (photoFiles + printFile).map { it.absolutePath }.toTypedArray(), null, null,
            )
            Log.i(TAG, "BoothSession: session ${current.id} validated: ${photoFiles.size} originals + ${montage.name} kept in ${montage.parentFile?.name}")
            val sharing = uploads.isEnabledFor(event)
            _state.update { it.copy(isRendering = false, share = null, shareLoading = sharing, shareFailed = false) }
            if (sharing) publishMontage(current.id, cfg.layoutType, montage)

            val finished = current.copy(
                photosCaptured = photoFiles.size,
                retakes = retakes,
                printsRequested = s.copies,
                sharedViaQr = sharing,
                layoutType = cfg.layoutType,
            )
            session = finished
            sessionLogs.finish(finished, SessionOutcome.COMPLETED)
            sessionFinished = true
            uploads.complete(current.id)

            startThanksTimer()
        }
    }

    /** Uploads the montage in the background; the QR code appears as soon as the server returns the link. */
    private fun publishMontage(sessionId: String, layoutType: LayoutType, montage: File) {
        val event = _state.value.event ?: return
        viewModelScope.launch {
            val result = uploads.uploadMontage(UploadContext.of(sessionId, event, layoutType), montage)
            if (session?.id != sessionId) return@launch // already back on the welcome screen
            val url = result.getOrNull()
            if (url == null) {
                Log.w(TAG, "BoothSession: montage upload failed: ${result.exceptionOrNull()?.message}")
                _state.update { it.copy(shareLoading = false, shareFailed = true) }
                return@launch
            }
            val qr = withContext(Dispatchers.Default) { QrCodes.bitmap(url, QR_SIZE_PX) }
            Log.i(TAG, "BoothSession: share url=$url")
            _state.update { it.copy(share = ShareUi(url, qr), shareLoading = false) }
        }
    }

    private fun uploadContext(): UploadContext? {
        val s = _state.value
        val event = s.event ?: return null
        val id = session?.id ?: return null
        val layoutType = s.layout?.layoutType ?: return null
        if (!event.enableDigitalQRShare) return null
        return UploadContext.of(id, event, layoutType)
    }

    private fun startThanksTimer() {
        thanksJob?.cancel()
        thanksJob = viewModelScope.launch {
            for (sec in THANKS_SECONDS downTo 1) {
                _state.update { it.copy(thanksSecondsLeft = sec) }
                delay(1_000)
            }
            Log.i(TAG, "BoothSession: thank-you timer elapsed")
            resetToAttract(abandon = false)
        }
    }

    // ---- Reset & helpers ---------------------------------------------------------------------

    private fun resetToAttract(abandon: Boolean) {
        val before = _state.value
        if (before.step == BoothStep.PRINTING && before.isRendering) return // the montage is being written
        val self = thanksJob
        val inFlightCapture = countdownJob
        countdownJob?.cancel()
        renderJob?.cancel()
        if (abandon) self?.cancel()
        val sess = session
        if (sess != null && !sessionFinished) {
            val kept = _state.value.photos.count { it != null }
            viewModelScope.launch {
                sessionLogs.finish(sess.copy(photosCaptured = kept, retakes = retakes), SessionOutcome.ABANDONED)
            }
            cleanupAbortedSession(sess, before.event, before.photos + before.reviewFile + capturingFile, inFlightCapture)
            uploads.deleteSession(sess.id)
        }
        capturingFile = null
        session = null
        sessionFinished = false
        _state.update {
            BoothUiState(
                event = it.event,
                cameraReady = it.cameraReady,
                rotationDegrees = it.rotationDegrees,
                slideshowEnabled = it.slideshowEnabled,
                printWaiting = it.printWaiting,
            )
        }
        Log.i(TAG, "BoothSession: step=ATTRACT (abandon=$abandon)")
        refreshSlideshow()
    }

    /** Permanently deletes every raw photo and temporary render of a session that was never validated. */
    private fun cleanupAbortedSession(sess: SessionLogEntity, event: EventEntity?, files: List<File?>, capture: Job?) {
        if (event == null) return
        viewModelScope.launch {
            capture?.join() // a capture still in flight must not recreate files after the sweep
            val dir = storage.sessionDirectory(event, sess.id)
            val photos = files.filterNotNull().distinct()
            val onCamera = photos.count { camera.deleteFromCamera(it) }
            val removed = withContext(Dispatchers.IO) { dir.deleteRecursively() }
            Log.i(TAG, "BoothSession: cleanupAbortedSession ${sess.id}: ${photos.size} photo(s), cameraCopiesRemoved=$onCamera, folderRemoved=$removed")
            // A cancelled capture may still write its file shortly afterwards.
            delay(LATE_CAPTURE_SWEEP_MS)
            withContext(Dispatchers.IO) { if (dir.exists()) dir.deleteRecursively() }
            Log.i(TAG, "BoothSession: cleanupAbortedSession ${sess.id}: final check folderExists=${dir.exists()}")
        }
    }

    private fun go(step: BoothStep) {
        _state.update { it.copy(step = step) }
        Log.i(TAG, "BoothSession: step=$step slot=${_state.value.slotIndex + 1}/${_state.value.totalSlots}")
    }

    private fun post(text: String) {
        _state.update { it.copy(message = text) }
        viewModelScope.launch {
            delay(MESSAGE_MS)
            _state.update { if (it.message == text) it.copy(message = null) else it }
        }
    }

    private fun refreshSlideshow() {
        viewModelScope.launch {
            val s = _state.value
            val event = s.event
            val files = if (!s.slideshowEnabled || event == null) emptyList() else withContext(Dispatchers.IO) { recentPhotos(event) }
            _state.update { it.copy(slideshow = files) }
        }
    }

    private fun recentPhotos(event: EventEntity): List<File> {
        val root = storage.directoryOf(event)
        fun jpegs(sub: String) = File(root, sub).listFiles { f ->
            f.isFile && !f.name.startsWith(".") && !f.name.startsWith(EventStorage.TEST_PREFIX) &&
                f.extension.lowercase() in setOf("jpg", "jpeg")
        }.orEmpty().sortedByDescending { it.lastModified() }
        val prints = jpegs(EventStorage.COMPOSITED_PRINTS)
        return (prints.ifEmpty { jpegs(EventStorage.ORIGINALS) }).take(SLIDESHOW_MAX)
    }

    private companion object {
        val IDLE_GUARDED = setOf(BoothStep.SELECT_LAYOUT, BoothStep.LIVE, BoothStep.REVIEW, BoothStep.FINAL)
        const val IDLE_TIMEOUT_MS = 90_000L
        const val THANKS_SECONDS = 15
        const val MAX_COPIES = 5
        const val SMILE_MS = 450L
        const val FOCUS_AT_SECOND = 2
        const val LATE_CAPTURE_SWEEP_MS = 4_000L
        const val RETAKE_COUNTDOWN_DELAY_MS = 500L
        const val MESSAGE_MS = 4_000L
        const val PREVIEW_CARD_WIDTH_PX = 720
        const val FINAL_PREVIEW_SCALE = 0.6f
        const val QR_SIZE_PX = 640
        const val SLIDESHOW_MAX = 12
    }
}
