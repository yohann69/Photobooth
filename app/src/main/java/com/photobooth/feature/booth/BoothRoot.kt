package com.photobooth.feature.booth

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photobooth.feature.app.AdminHotspot

private enum class BoothScreen { ATTRACT, SELECT, CAPTURE, FINAL, THANKS }

private fun BoothStep.screen() = when (this) {
    BoothStep.ATTRACT -> BoothScreen.ATTRACT
    BoothStep.SELECT_LAYOUT -> BoothScreen.SELECT
    BoothStep.LIVE, BoothStep.COUNTDOWN, BoothStep.CAPTURING, BoothStep.REVIEW -> BoothScreen.CAPTURE
    BoothStep.FINAL -> BoothScreen.FINAL
    BoothStep.PRINTING -> BoothScreen.THANKS
}

/** Guest-mode root: renders the step of the booth state machine; the hidden admin hotspot stays on top of every step. */
@Composable
fun BoothRoot(viewModel: BoothSessionViewModel, onAdminRequested: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent

    EventTheme(state.event) { Box(Modifier.fillMaxSize()) {
        EventBackdrop(Modifier.fillMaxSize())
        Crossfade(targetState = state.step.screen(), animationSpec = tween(300), label = "booth-screen") { screen ->
            when (screen) {
                BoothScreen.ATTRACT -> AttractScreen(state) { send(BoothIntent.StartSession) }
                BoothScreen.SELECT -> LayoutSelectionScreen(state, { send(BoothIntent.SelectLayout(it)) }, { send(BoothIntent.BackToStart) })
                BoothScreen.CAPTURE -> CaptureAndReviewScreen(
                    state, viewModel.frames,
                    onTakePhoto = { send(BoothIntent.TakePhoto) },
                    onKeep = { send(BoothIntent.KeepPhoto) },
                    onRetake = { send(BoothIntent.RetakePhoto) },
                    onAbort = { send(BoothIntent.BackToStart) },
                )
                BoothScreen.FINAL -> FinalSummaryScreen(
                    state,
                    onCopies = { send(BoothIntent.SetCopies(it)) },
                    onConfirm = { send(BoothIntent.ConfirmPrint) },
                    onCancel = { send(BoothIntent.BackToStart) },
                )
                BoothScreen.THANKS -> ThanksScreen(state) { send(BoothIntent.BackToStart) }
            }
        }
        AdminHotspot(onTriggered = onAdminRequested, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding())
        if (state.printWaiting) {
            Box(
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(20.dp).size(14.dp)
                    .background(BoothColors.Retake.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape),
            )
        }
    } }
}
