package com.photobooth.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobooth.data.repository.EventRepository
import com.photobooth.data.repository.LayoutRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.domain.model.LayoutType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppRoute { GUEST, ADMIN }

data class AppUiState(
    val route: AppRoute = AppRoute.GUEST,
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
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    events: EventRepository,
    layouts: LayoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val guestInfo: StateFlow<GuestInfo> = events.observeActive()
        .flatMapLatest { event ->
            if (event == null) flowOf(GuestInfo())
            else layouts.observeEnabledForEvent(event.id).map { list -> GuestInfo(event.name, list.map { it.layoutType }) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuestInfo())

    private var failedAttempts = 0
    private var lockedUntil = 0L

    fun onIntent(intent: AppIntent) {
        when (intent) {
            AppIntent.RequestAdmin -> _state.update { it.copy(pinDialogVisible = true, pinError = null) }
            AppIntent.DismissPin -> _state.update { it.copy(pinDialogVisible = false, pinError = null) }
            AppIntent.ExitAdmin -> _state.update { it.copy(route = AppRoute.GUEST) }
            is AppIntent.SubmitPin -> submit(intent.pin)
        }
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
                _state.update { it.copy(route = AppRoute.ADMIN, pinDialogVisible = false, pinError = null) }
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

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
