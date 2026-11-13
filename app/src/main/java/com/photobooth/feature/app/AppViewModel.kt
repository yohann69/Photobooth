package com.photobooth.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.domain.model.LayoutType
import com.photobooth.share.EventSync
import com.photobooth.share.EventSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** EVENT_PICKER is the start screen: the operator confirms or changes the running event before guests see anything. */
enum class AppRoute { EVENT_PICKER, GUEST, ADMIN, SETTINGS }

data class AppUiState(
    val route: AppRoute = AppRoute.EVENT_PICKER,
    /** Set after an event is created so the admin opens on its settings. */
    val openEventSettings: Boolean = false,
    val pinDialogVisible: Boolean = false,
    val pinError: String? = null,
    /** Bumped after every failed attempt so the keypad clears itself. */
    val pinAttempt: Int = 0,
)

data class GuestInfo(val eventName: String = "", val enabledLayouts: List<LayoutType> = emptyList())

sealed interface AppIntent {
    data object RequestAdmin : AppIntent
    data object DismissPin : AppIntent
    data class SubmitPin(val pin: String) : AppIntent
    data object ExitAdmin : AppIntent
    /** Second admin panel: the PIN is asked unless the admin panel is already open. */
    data object RequestSettings : AppIntent
    data object OpenSettings : AppIntent
    data object CloseSettings : AppIntent
    /** Continue with an event; any event other than the running one needs the PIN. */
    data class PickEvent(val eventId: String) : AppIntent
    data class CreateEvent(val name: String, val dateUtc: Long) : AppIntent
    data object EventSettingsOpened : AppIntent
    /** Fetch the events prepared in the Transat admin panel again. */
    data object SyncEvents : AppIntent
}

private sealed interface PendingAction {
    data object OpenAdmin : PendingAction
    data object OpenSettings : PendingAction
    data class Pick(val eventId: String) : PendingAction
    data class Create(val name: String, val dateUtc: Long) : PendingAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val events: EventRepository,
    layouts: LayoutRepository,
    private val eventSync: EventSync,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val eventList: StateFlow<List<EventEntity>> = events.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val guestInfo: StateFlow<GuestInfo> = events.observeActive()
        .flatMapLatest { event ->
            if (event == null) flowOf(GuestInfo())
            else layouts.observeEnabledForEvent(event.id).map { list -> GuestInfo(event.name, list.map { it.layoutType }) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuestInfo())

    val syncState: StateFlow<EventSyncState> = eventSync.state

    init {
        viewModelScope.launch {
            // Events prepared in the admin panel first, then today's event becomes the running one by default.
            withTimeoutOrNull(STARTUP_SYNC_MS) { eventSync.sync() }
            eventSync.selectToday()
            // A kiosk that starts on its own (after a reboot, on Home) opens straight on the guest screen of the
            // running event; the operator changes event from the admin panel.
            if (settingsRepository.settings.first().kioskModeEnabled && events.getActive() != null) {
                _state.update { if (it.route == AppRoute.EVENT_PICKER) it.copy(route = AppRoute.GUEST) else it }
            }
        }
    }

    private var pending: PendingAction = PendingAction.OpenAdmin
    private var settingsReturnTo = AppRoute.EVENT_PICKER
    private var failedAttempts = 0
    private var lockedUntil = 0L

    fun onIntent(intent: AppIntent) {
        when (intent) {
            AppIntent.RequestAdmin -> askPin(PendingAction.OpenAdmin)
            is AppIntent.PickEvent -> viewModelScope.launch {
                if (events.getActive()?.id == intent.eventId) _state.update { it.copy(route = AppRoute.GUEST) }
                else askPin(PendingAction.Pick(intent.eventId))
            }
            is AppIntent.CreateEvent -> askPin(PendingAction.Create(intent.name.trim(), intent.dateUtc))
            AppIntent.EventSettingsOpened -> _state.update { it.copy(openEventSettings = false) }
            AppIntent.SyncEvents -> viewModelScope.launch {
                eventSync.sync()
                // Only on the start screen: never switch the running event under the guests' feet.
                if (_state.value.route == AppRoute.EVENT_PICKER) eventSync.selectToday()
            }
            AppIntent.DismissPin -> _state.update { it.copy(pinDialogVisible = false, pinError = null) }
            AppIntent.RequestSettings -> askPin(PendingAction.OpenSettings)
            AppIntent.OpenSettings -> openSettings(AppRoute.ADMIN)
            AppIntent.CloseSettings -> _state.update { it.copy(route = settingsReturnTo) }
            AppIntent.ExitAdmin -> _state.update { it.copy(route = AppRoute.GUEST) }
            is AppIntent.SubmitPin -> submit(intent.pin)
        }
    }

    private fun openSettings(from: AppRoute) {
        settingsReturnTo = from
        _state.update { it.copy(route = AppRoute.SETTINGS) }
    }

    private fun askPin(action: PendingAction) {
        pending = action
        _state.update { it.copy(pinDialogVisible = true, pinError = null) }
    }

    private fun submit(pin: String) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            val seconds = (lockedUntil - now + 999) / 1000
            _state.update { it.copy(pinError = "Too many attempts. Try again in ${seconds}s", pinAttempt = it.pinAttempt + 1) }
            return
        }
        viewModelScope.launch {
            if (settingsRepository.verifyAdminPin(pin)) {
                failedAttempts = 0
                _state.update { it.copy(pinDialogVisible = false, pinError = null) }
                perform(pending)
            } else {
                failedAttempts++
                if (failedAttempts >= MAX_ATTEMPTS) {
                    failedAttempts = 0
                    lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                }
                val message = if (lockedUntil > System.currentTimeMillis()) "Too many attempts. Locked for ${LOCKOUT_MS / 1000}s" else "Incorrect PIN"
                _state.update { it.copy(pinError = message, pinAttempt = it.pinAttempt + 1) }
            }
        }
    }

    private suspend fun perform(action: PendingAction) {
        when (action) {
            PendingAction.OpenAdmin -> _state.update { it.copy(route = AppRoute.ADMIN) }
            PendingAction.OpenSettings -> openSettings(AppRoute.EVENT_PICKER)
            is PendingAction.Pick -> {
                events.setActive(action.eventId)
                _state.update { it.copy(route = AppRoute.GUEST) }
            }
            is PendingAction.Create -> {
                if (action.name.isEmpty()) return
                events.createWithStorage(action.name, action.dateUtc)
                _state.update { it.copy(route = AppRoute.ADMIN, openEventSettings = true) }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
        /** A booth without network still starts, on the events it already has. */
        const val STARTUP_SYNC_MS = 15_000L
    }
}
