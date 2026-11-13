package com.photobooth

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.settings.AppSettings
import com.photobooth.feature.admin.AdminIntent
import com.photobooth.feature.admin.AdminScreen
import com.photobooth.feature.admin.AdminTab
import com.photobooth.feature.admin.AppSettingsScreen
import com.photobooth.feature.admin.AdminViewModel
import com.photobooth.feature.app.AppIntent
import com.photobooth.feature.app.AppRoute
import com.photobooth.feature.app.AppViewModel
import com.photobooth.feature.app.EventPickerScreen
import com.photobooth.feature.app.PinPadDialog
import com.photobooth.feature.booth.BoothIntent
import com.photobooth.feature.booth.BoothRoot
import com.photobooth.feature.booth.BoothTheme
import com.photobooth.feature.booth.BoothSessionViewModel
import com.photobooth.feature.camera.CameraIntent
import com.photobooth.feature.camera.CameraViewModel
import com.photobooth.print.PrintController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val adminViewModel: AdminViewModel by viewModels()
    private val cameraViewModel: CameraViewModel by viewModels()
    private val boothViewModel: BoothSessionViewModel by viewModels()

    @Inject lateinit var printController: PrintController

    private var appliedSettings = AppSettings()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraViewModel.onIntent(CameraIntent.Resume)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BoothTheme {
                Surface {
                    val app by appViewModel.state.collectAsStateWithLifecycle()
                    val events by appViewModel.eventList.collectAsStateWithLifecycle()
                    val sync by appViewModel.syncState.collectAsStateWithLifecycle()
                    when (app.route) {
                        AppRoute.EVENT_PICKER -> EventPickerScreen(
                            events,
                            onPick = { appViewModel.onIntent(AppIntent.PickEvent(it)) },
                            onCreate = { name, date -> appViewModel.onIntent(AppIntent.CreateEvent(name, date)) },
                            onSettings = { appViewModel.onIntent(AppIntent.RequestSettings) },
                            sync = sync,
                            onSync = { appViewModel.onIntent(AppIntent.SyncEvents) },
                        )
                        AppRoute.GUEST -> BoothRoot(boothViewModel, onAdminRequested = { appViewModel.onIntent(AppIntent.RequestAdmin) })
                        AppRoute.ADMIN -> {
                            LaunchedEffect(Unit) { boothViewModel.onIntent(BoothIntent.Abort) }
                            LaunchedEffect(app.openEventSettings) {
                                if (app.openEventSettings) {
                                    adminViewModel.onIntent(AdminIntent.SelectTab(AdminTab.EVENT_SETTINGS))
                                    appViewModel.onIntent(AppIntent.EventSettingsOpened)
                                }
                            }
                            BackHandler { appViewModel.onIntent(AppIntent.ExitAdmin) }
                            AdminScreen(
                                adminViewModel,
                                onExit = { appViewModel.onIntent(AppIntent.ExitAdmin) },
                                onOpenSettings = { appViewModel.onIntent(AppIntent.OpenSettings) },
                            )
                        }
                        AppRoute.SETTINGS -> {
                            BackHandler { appViewModel.onIntent(AppIntent.CloseSettings) }
                            val admin by adminViewModel.state.collectAsStateWithLifecycle()
                            AppSettingsScreen(admin, adminViewModel::onIntent, onBack = { appViewModel.onIntent(AppIntent.CloseSettings) })
                        }
                    }
                    if (app.pinDialogVisible) {
                        PinPadDialog(
                            error = app.pinError,
                            attempt = app.pinAttempt,
                            onSubmit = { appViewModel.onIntent(AppIntent.SubmitPin(it)) },
                            onDismiss = { appViewModel.onIntent(AppIntent.DismissPin) },
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.settings.collect { applyDisplaySettings(it) }
            }
        }
    }

    /** Follows the charger: the screen is kept on only while the tablet is plugged in. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged != charging) {
                charging = plugged
                applyDisplaySettings(appliedSettings)
            }
        }
    }
    private var charging = false

    override fun onStart() {
        super.onStart()
        // ACTION_BATTERY_CHANGED is sticky: registering delivers the current state at once.
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        printController.start()
        printController.kick()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraViewModel.onIntent(CameraIntent.Resume)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        unregisterReceiver(powerReceiver)
        cameraViewModel.onIntent(CameraIntent.Pause)
        super.onStop()
    }

    // Bars reappear after dialogs or the keyboard; re-assert the immersive state.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyDisplaySettings(appliedSettings)
    }

    private fun applyDisplaySettings(settings: AppSettings) {
        appliedSettings = settings

        val keepAwake = settings.keepScreenOn && charging
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // In kiosk mode the booth shows over the lock screen when the display wakes; Android itself stays locked.
        setShowWhenLocked(settings.kioskModeEnabled)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (settings.hideSystemBars) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        val locked = getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        runCatching {
            if (settings.kioskModeEnabled && !locked) startLockTask()
            else if (!settings.kioskModeEnabled && locked) stopLockTask()
        }.onFailure { Log.w(TAG, "MainActivity: lock task change failed: ${it.message}") }
        Log.i(TAG, "MainActivity: display keepAwake=$keepAwake (charging=$charging) hideBars=${settings.hideSystemBars} kiosk=${settings.kioskModeEnabled}")
    }
}
