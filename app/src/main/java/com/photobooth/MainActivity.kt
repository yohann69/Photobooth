package com.photobooth

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
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
import com.photobooth.feature.admin.AdminScreen
import com.photobooth.feature.admin.AdminViewModel
import com.photobooth.feature.app.AppIntent
import com.photobooth.feature.app.AppRoute
import com.photobooth.feature.app.AppViewModel
import com.photobooth.feature.app.PinPadDialog
import com.photobooth.feature.booth.BoothIntent
import com.photobooth.feature.booth.BoothRoot
import com.photobooth.feature.booth.BoothSessionViewModel
import com.photobooth.feature.camera.CameraIntent
import com.photobooth.feature.camera.CameraViewModel
import com.photobooth.print.PrintController
import com.photobooth.share.LocalGalleryHttpServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val adminViewModel: AdminViewModel by viewModels()
    private val cameraViewModel: CameraViewModel by viewModels()
    private val boothViewModel: BoothSessionViewModel by viewModels()

    @Inject lateinit var gallery: LocalGalleryHttpServer
    @Inject lateinit var printController: PrintController

    private var appliedSettings = AppSettings()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraViewModel.onIntent(CameraIntent.Resume)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val app by appViewModel.state.collectAsStateWithLifecycle()
                    when (app.route) {
                        AppRoute.GUEST -> BoothRoot(boothViewModel, onAdminRequested = { appViewModel.onIntent(AppIntent.RequestAdmin) })
                        AppRoute.ADMIN -> {
                            LaunchedEffect(Unit) { boothViewModel.onIntent(BoothIntent.Abort) }
                            BackHandler { appViewModel.onIntent(AppIntent.ExitAdmin) }
                            AdminScreen(adminViewModel, onExit = { appViewModel.onIntent(AppIntent.ExitAdmin) })
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

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) { gallery.start() }
        printController.start()
        printController.kick()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraViewModel.onIntent(CameraIntent.Resume)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
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

        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        Log.i(TAG, "MainActivity: display keepAwake=${settings.keepScreenOn} hideBars=${settings.hideSystemBars} kiosk=${settings.kioskModeEnabled}")
    }
}
