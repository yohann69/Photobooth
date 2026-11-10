package com.photobooth.feature.admin

import android.net.Uri
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.print.PrintStatus
import com.photobooth.print.dnp.DnpLinkState
import com.photobooth.data.settings.AppSettings
import com.photobooth.diagnostics.DiagnosticsSnapshot
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.PhotoAspect

enum class AdminTab(val title: String) {
    DASHBOARD("Dashboard"),
    EVENTS("Events"),
    EVENT_SETTINGS("Event settings"),
    LAYOUTS("Layouts & frames"),
}

data class EventRow(
    val event: EventEntity,
    val photoCount: Int,
    val printCount: Int,
    val folderPath: String,
)

data class SelfTestResult(
    val shotPath: String?,
    val printPath: String?,
    val printerStatus: String,
    val success: Boolean,
    val message: String,
)

sealed interface SelfTestState {
    data object Idle : SelfTestState
    data class Running(val step: String) : SelfTestState
    data class Done(val result: SelfTestResult) : SelfTestState
}

data class AdminUiState(
    val tab: AdminTab = AdminTab.DASHBOARD,
    val events: List<EventRow> = emptyList(),
    val activeEvent: EventEntity? = null,
    val layouts: List<EventLayoutConfigEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val diagnostics: DiagnosticsSnapshot? = null,
    val selfTest: SelfTestState = SelfTestState.Idle,
    val printStatus: PrintStatus = PrintStatus(),
    val dnp: DnpLinkState = DnpLinkState(),
    val message: String? = null,
)

sealed interface AdminIntent {
    data class SelectTab(val tab: AdminTab) : AdminIntent
    data object RefreshEvents : AdminIntent
    data object DismissMessage : AdminIntent

    data class CreateEvent(val name: String, val dateUtc: Long) : AdminIntent
    data class SwitchEvent(val eventId: String) : AdminIntent
    data class UpdateActiveEvent(val transform: (EventEntity) -> EventEntity) : AdminIntent
    data class SetPhotoAspect(val aspect: PhotoAspect) : AdminIntent

    data class ToggleLayout(val type: LayoutType, val enabled: Boolean) : AdminIntent
    data class ImportFrame(val type: LayoutType, val uri: Uri) : AdminIntent
    /** Shows a QR code where the organisers send the event's frames from their phones. */
    data object OpenFrameLink : AdminIntent
    data object CloseFrameLink : AdminIntent
    data class GenerateTestFrame(val type: LayoutType) : AdminIntent
    data class RemoveFrame(val type: LayoutType) : AdminIntent
    data class SetCalibration(val type: LayoutType, val paddingPx: Int, val offsetXPx: Int, val offsetYPx: Int) : AdminIntent
    data class SetBackgroundColor(val type: LayoutType, val hex: String) : AdminIntent

    data class SetKioskMode(val enabled: Boolean) : AdminIntent
    data class SetKeepScreenOn(val enabled: Boolean) : AdminIntent
    data class SetHideSystemBars(val enabled: Boolean) : AdminIntent

    data class SetDryRun(val enabled: Boolean) : AdminIntent
    data object ReplaceRoll : AdminIntent
    data class SetDnpOrientation(val flipVertical: Boolean, val mirror: Boolean, val portraitClockwise: Boolean) : AdminIntent
    data class PrintTestSheet(val portrait: Boolean) : AdminIntent
    data class SaveUpload(val baseUrl: String, val token: String) : AdminIntent
    data class TestUpload(val baseUrl: String, val token: String) : AdminIntent
    data class CancelPrintJob(val jobId: Long) : AdminIntent
    data object CancelAllPrintJobs : AdminIntent
    data class ChangePin(val currentPin: String, val newPin: String) : AdminIntent

    data object RunSelfTest : AdminIntent
    data object DismissSelfTest : AdminIntent
}
