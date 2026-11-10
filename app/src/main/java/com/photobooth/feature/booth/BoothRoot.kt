package com.photobooth.feature.booth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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

    BoothTheme { Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(Modifier.fillMaxSize())
        AnimatedContent(
            targetState = state.step.screen(),
            transitionSpec = {
                // The new screen settles in from slightly small while the old one drifts towards the viewer.
                (fadeIn(tween(420, delayMillis = 90)) + scaleIn(tween(520, easing = FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(220)) + scaleOut(tween(320), targetScale = 1.04f))
            },
            label = "booth-screen",
        ) { screen ->
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
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(20.dp).size(12.dp)
                    .background(BoothColors.Retake.copy(alpha = 0.7f), CircleShape),
            )
        }
    } }
}
