package com.photobooth.feature.admin

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobooth.camera.UniversalCanonManager
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.frames.FrameStorage
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.data.storage.EventStorage
import com.photobooth.diagnostics.DiagnosticsSnapshot
import com.photobooth.diagnostics.HardwareDiagnostics
import com.photobooth.domain.model.LayoutType
import com.photobooth.print.PrintController
import com.photobooth.print.dnp.DnpPrinterLink
import com.photobooth.print.dnp.DnpTestSheet
import com.photobooth.render.DummyPhotos
import com.photobooth.render.PhotoCompositorEngine
import com.photobooth.render.PhotoDecoder
import com.photobooth.render.PhotoSupplier
import com.photobooth.share.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val events: EventRepository,
    private val layouts: LayoutRepository,
    private val settingsRepository: SettingsRepository,
    private val printQueue: PrintQueueRepository,
    private val storage: EventStorage,
    private val frames: FrameStorage,
    private val compositor: PhotoCompositorEngine,
    private val diagnostics: HardwareDiagnostics,
    private val camera: UniversalCanonManager,
    private val printController: PrintController,
    private val dnp: DnpPrinterLink,
    private val uploads: UploadManager,
) : ViewModel() {

    private data class Local(val tab: AdminTab = AdminTab.DASHBOARD, val message: String? = null, val selfTest: SelfTestState = SelfTestState.Idle)
    private data class Data(val rows: List<EventRow>, val active: EventEntity?, val layouts: List<EventLayoutConfigEntity>, val settings: AppSettings)

    private val local = MutableStateFlow(Local())
    private val refreshTick = MutableStateFlow(0)

    private val eventRows: Flow<List<EventRow>> =
        combine(events.observeAll(), printQueue.observePrintTotals(), refreshTick) { all, totals, _ ->
            val printed = totals.associate { it.eventId to it.total }
            withContext(Dispatchers.IO) {
                all.map { e ->
                    EventRow(
                        event = e,
                        photoCount = storage.countPhotos(e),
                        printCount = printed[e.id] ?: 0,
                        folderPath = storage.directoryOf(e).absolutePath,
                    )
                }
            }
        }

    private val activeLayouts: Flow<List<EventLayoutConfigEntity>> = events.observeActive().flatMapLatest { e ->
        if (e == null) flowOf(emptyList()) else layouts.observeForEvent(e.id)
    }

    private val data: Flow<Data> =
        combine(eventRows, events.observeActive(), activeLayouts, settingsRepository.settings) { rows, active, lay, s ->
            Data(rows, active, lay, s)
        }

    private val diagnosticsFlow: Flow<DiagnosticsSnapshot?> = flow {
        emit(null)
        emitAll(diagnostics.observe())
    }

    val state: StateFlow<AdminUiState> = combine(data, diagnosticsFlow, local, printController.status, dnp.state) { d, diag, l, printStatus, dnpState ->
        AdminUiState(
            tab = l.tab,
            events = d.rows,
            activeEvent = d.active,
            layouts = d.layouts,
            settings = d.settings,
            diagnostics = diag,
            selfTest = l.selfTest,
            printStatus = printStatus,
            dnp = dnpState,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminUiState())

    fun onIntent(intent: AdminIntent) {
        when (intent) {
            is AdminIntent.SelectTab -> {
                local.update { it.copy(tab = intent.tab) }
                if (intent.tab == AdminTab.EVENTS) refreshTick.update { it + 1 }
            }
            AdminIntent.RefreshEvents -> refreshTick.update { it + 1 }
            AdminIntent.DismissMessage -> local.update { it.copy(message = null) }

            is AdminIntent.CreateEvent -> createEvent(intent.name, intent.dateUtc)
            is AdminIntent.SwitchEvent -> launch { events.setActive(intent.eventId) }
            is AdminIntent.UpdateActiveEvent -> launch { events.getActive()?.let { events.update(intent.transform(it)) } }

            is AdminIntent.SetPhotoAspect -> withActiveEvent { e ->
                if (e.photoAspect != intent.aspect) {
                    events.update(e.copy(photoAspect = intent.aspect))
                    layouts.applyAspect(e.id, intent.aspect)
                    post("Photo ratio ${intent.aspect.label}: layouts rebuilt, re-check your frames")
                }
            }
            is AdminIntent.SetEventBackgroundImage -> setBackgroundImage(intent.uri)
            AdminIntent.RemoveEventBackgroundImage -> withActiveEvent { e ->
                frames.delete(e.customBackgroundImagePath)
                events.update(e.copy(customBackgroundImagePath = null))
            }

            is AdminIntent.ToggleLayout -> withActiveEvent { layouts.setEnabled(it.id, intent.type, intent.enabled) }
            is AdminIntent.ImportFrame -> importFrame(intent)
            is AdminIntent.GenerateTestFrame -> generateTestFrame(intent.type)
            is AdminIntent.RemoveFrame -> withActiveEvent { e ->
                layouts.get(e.id, intent.type)?.let { frames.delete(it.customFramePngPath) }
                layouts.setFramePath(e.id, intent.type, null)
            }
            is AdminIntent.SetCalibration ->
                withActiveEvent { layouts.setCalibration(it.id, intent.type, intent.paddingPx, intent.offsetXPx, intent.offsetYPx) }
            is AdminIntent.SetBackgroundColor -> withActiveEvent { layouts.setBackgroundColor(it.id, intent.type, intent.hex) }

            is AdminIntent.SetKioskMode -> launch { settingsRepository.setKioskMode(intent.enabled) }
            is AdminIntent.SetKeepScreenOn -> launch { settingsRepository.setKeepScreenOn(intent.enabled) }
            is AdminIntent.SetHideSystemBars -> launch { settingsRepository.setHideSystemBars(intent.enabled) }

            is AdminIntent.SetDryRun -> launch {
                settingsRepository.setDryRun(intent.enabled)
                printController.kick()
            }
            is AdminIntent.SetDnpOrientation -> launch {
                settingsRepository.setDnpOrientation(intent.flipVertical, intent.mirror, intent.portraitClockwise)
            }
            is AdminIntent.PrintTestSheet -> printTestSheet(intent.portrait)
            is AdminIntent.SaveUpload -> launch {
                settingsRepository.setUpload(intent.baseUrl, intent.token)
                post("Upload server saved")
            }
            is AdminIntent.TestUpload -> launch {
                post("Testing connection…")
                uploads.testConnection(intent.baseUrl, intent.token)
                    .onSuccess { post("Connection OK: the server accepts this booth key") }
                    .onFailure { post("Connection failed: ${it.message}") }
            }
            is AdminIntent.CancelPrintJob -> launch {
                printController.cancel(intent.jobId)
                post("Print job cancelled")
            }
            AdminIntent.CancelAllPrintJobs -> launch {
                printController.cancelAll()
                post("All waiting prints cancelled")
            }
            AdminIntent.ReplaceRoll -> launch {
                settingsRepository.setRollBaseline(printQueue.totalPrinted())
                post("Paper counter reset for the new roll")
            }
            is AdminIntent.ChangePin -> changePin(intent)

            AdminIntent.RunSelfTest -> runSelfTest()
            AdminIntent.DismissSelfTest -> local.update { it.copy(selfTest = SelfTestState.Idle) }
        }
    }

    /** Renders the layout with dummy photos and its frame; used by the grid thumbnails and the calibrator. */
    suspend fun renderPreview(config: EventLayoutConfigEntity, widthPx: Int, showGuides: Boolean): Bitmap =
        withContext(Dispatchers.Default) {
            compositor.render(config, widthPx.toFloat() / config.canvasWidthPx, { i, w, h -> DummyPhotos.create(i, w, h) }, showGuides)
        }

    suspend fun frameSize(path: String): Pair<Int, Int>? = frames.sizeOf(path)

    // ---- Implementations ---------------------------------------------------------------------

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun withActiveEvent(block: suspend (EventEntity) -> Unit) = launch { events.getActive()?.let { block(it) } }

    private fun post(message: String) = local.update { it.copy(message = message) }

    private fun createEvent(name: String, dateUtc: Long) = launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch post("Event name cannot be empty")
        runCatching { events.createWithStorage(trimmed, dateUtc) }
            .onSuccess {
                refreshTick.update { n -> n + 1 }
                local.update { l -> l.copy(tab = AdminTab.EVENT_SETTINGS) }
                post("Event \"${it.name}\" created and activated")
            }
            .onFailure { post("Could not create event: ${it.message}") }
    }

    private fun importFrame(intent: AdminIntent.ImportFrame) = withActiveEvent { event ->
        val config = layouts.get(event.id, intent.type) ?: return@withActiveEvent
        frames.import(storage.subfolder(event, EventStorage.FRAMES), config, intent.uri)
            .onSuccess { file ->
                frames.delete(config.customFramePngPath)
                layouts.setFramePath(event.id, intent.type, file.absolutePath)
                post("Frame applied to ${intent.type.name}")
            }
            .onFailure { post("Frame import failed: ${it.message}") }
    }

    private fun setBackgroundImage(uri: android.net.Uri) = withActiveEvent { event ->
        frames.importBackground(storage.subfolder(event, EventStorage.THEME), uri)
            .onSuccess { file ->
                frames.delete(event.customBackgroundImagePath)
                events.update(event.copy(customBackgroundImagePath = file.absolutePath))
                post("Background image applied to this event")
            }
            .onFailure { post("Background import failed: ${it.message}") }
    }

    private fun generateTestFrame(type: com.photobooth.domain.model.LayoutType) = withActiveEvent { event ->
        val config = layouts.get(event.id, type) ?: return@withActiveEvent
        frames.generateTestFrame(storage.subfolder(event, EventStorage.FRAMES), config, event.name)
            .onSuccess { file ->
                frames.delete(config.customFramePngPath)
                layouts.setFramePath(event.id, type, file.absolutePath)
                post("Test frame generated for ${type.name}")
            }
            .onFailure { post("Test frame failed: ${it.message}") }
    }

    private fun printTestSheet(portrait: Boolean) = withActiveEvent { event ->
        val file = File(storage.subfolder(event, EventStorage.COMPOSITED_PRINTS), "${EventStorage.TEST_PREFIX}SHEET_${if (portrait) "PORTRAIT" else "LANDSCAPE"}.jpg")
        withContext(Dispatchers.IO) {
            val bitmap = DnpTestSheet.render(portrait)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        printController.submit(event.id, null, file, 1, if (portrait) LayoutType.PORTRAIT_SINGLE else LayoutType.LANDSCAPE_SINGLE)
        post("Test sheet queued (${if (portrait) "portrait" else "landscape"})")
    }

    private fun changePin(intent: AdminIntent.ChangePin) = launch {
        when {
            !settingsRepository.verifyAdminPin(intent.currentPin) -> post("Current PIN is incorrect")
            else -> runCatching { settingsRepository.setAdminPin(intent.newPin) }
                .onSuccess { post("PIN updated") }
                .onFailure { post(it.message ?: "Invalid PIN") }
        }
    }

    private fun runSelfTest() {
        if (local.value.selfTest is SelfTestState.Running) return
        launch {
            val event = events.getActive() ?: return@launch post("No active event")
            fun step(text: String) = local.update { it.copy(selfTest = SelfTestState.Running(text)) }
            fun finish(result: SelfTestResult) = local.update { it.copy(selfTest = SelfTestState.Done(result)) }

            step("Capturing test shot…")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val shotFile = File(storage.subfolder(event, EventStorage.ORIGINALS), "${EventStorage.TEST_PREFIX}SHOT_$stamp.jpg")
            val shot = camera.captureHighResPhoto(shotFile)
            if (shot.isFailure) {
                return@launch finish(SelfTestResult(null, null, "-", false, "Test shot failed: ${shot.exceptionOrNull()?.message}"))
            }

            step("Composing test print…")
            val config = layouts.getForEvent(event.id).let { all -> all.firstOrNull { it.isEnabled } ?: all.firstOrNull() }
                ?: return@launch finish(SelfTestResult(shotFile.absolutePath, null, "-", false, "No layout configured"))
            val printFile = File(
                storage.subfolder(event, EventStorage.COMPOSITED_PRINTS),
                "${EventStorage.TEST_PREFIX}PRINT_${stamp}_${config.layoutType.name}.jpg",
            )
            val composed = runCatching {
                withContext(Dispatchers.Default) {
                    val bitmap = compositor.render(config, 1f, PhotoSupplier { _, w, h -> PhotoDecoder.decodeCrop(shotFile, w, h) })
                    FileOutputStream(printFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bitmap.recycle()
                }
            }
            val printer = diagnostics.printerBadge()
            val ok = composed.isSuccess
            finish(
                SelfTestResult(
                    shotPath = shotFile.absolutePath,
                    printPath = printFile.takeIf { ok }?.absolutePath,
                    printerStatus = "${printer.status}: ${printer.detail}",
                    success = ok,
                    message = if (ok) "Shot saved and ${config.layoutType.name} print composed (${config.canvasWidthPx}x${config.canvasHeightPx}). Printer output is not wired yet."
                    else "Print composition failed: ${composed.exceptionOrNull()?.message}",
                ),
            )
        }
    }
}
