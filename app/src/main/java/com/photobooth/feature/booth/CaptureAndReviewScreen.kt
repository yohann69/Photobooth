package com.photobooth.feature.booth

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.feature.camera.LiveViewSurface
import kotlinx.coroutines.flow.SharedFlow

/**
 * Capture and review share one screen. While idle the live feed sits left of the controls; once the countdown
 * starts the feed grows to the largest size the screen allows, with the countdown across its top.
 */
@Composable
fun CaptureAndReviewScreen(
    state: BoothUiState,
    frames: SharedFlow<Bitmap>,
    onTakePhoto: () -> Unit,
    onKeep: () -> Unit,
    onRetake: () -> Unit,
    onAbort: () -> Unit,
) {
    val layout = state.layout ?: return
    val reviewing = state.step == BoothStep.REVIEW
    val zoomed = state.step == BoothStep.COUNTDOWN || state.step == BoothStep.CAPTURING
    // The feed has exactly the aspect of the slot being shot, so the preview is the printed framing.
    val feedAspect = layout.captureSlots().getOrNull(state.slotIndex)?.aspectRatio ?: layoutAspect(layout)
    var confirmAbort by remember { mutableStateOf(false) }
    LaunchedEffect(state.step) { if (state.step != BoothStep.LIVE && state.step != BoothStep.REVIEW) confirmAbort = false }
    val zoom by animateFloatAsState(if (zoomed) 1f else 0f, tween(550, easing = FastOutSlowInEasing), label = "zoom")
    DisposableEffect(Unit) { onDispose { UiAudit.clear("capture") } }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val idleArea = Area(MARGIN, TOP_BAR_HEIGHT, maxWidth - SidePanelWidth - MARGIN * 3, maxHeight - TOP_BAR_HEIGHT - MARGIN)
            val fullArea = Area(0.dp, 0.dp, maxWidth, maxHeight)
            val feed = lerp(fit(idleArea, feedAspect), fit(fullArea, feedAspect), zoom)

            Crossfade(targetState = reviewing, animationSpec = tween(250), label = "stage") { review ->
                if (review) {
                    // Starts at the zoomed size and shrinks back: the zoom-in of the shot played in reverse.
                    val area = lerp(idleArea, fullArea, zoom)
                    Box(Modifier.offset(area.x, area.y).size(area.w, area.h), contentAlignment = Alignment.Center) {
                        val composition = state.reviewPreview
                        if (composition != null) {
                            Image(
                                composition.asImageBitmap(), "Composition avec la nouvelle photo",
                                Modifier.fillMaxSize().auditZone("capture", "preview"), contentScale = ContentScale.Fit,
                            )
                        } else state.reviewFile?.let {
                            AsyncImage(it, "Photo prise", Modifier.fillMaxSize().auditZone("capture", "preview"), contentScale = ContentScale.Fit)
                        }
                    }
                } else {
                    LiveFeed(frames, state, feed, zoom)
                }
            }

            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = MARGIN).width(SidePanelWidth).fillMaxHeight().padding(vertical = TOP_BAR_HEIGHT / 2)
                    .graphicsLayer { alpha = 1f - zoom }.auditZone("capture", "controls"),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.step == BoothStep.LIVE -> ShutterButton(onTakePhoto, size = 180.dp)
                    reviewing -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        CircleIconButton(Icons.Rounded.Check, "Conserver la photo", onKeep, container = BoothColors.Keep, size = 170.dp, iconSize = 100.dp)
                        if (state.event?.allowRetakes != false) {
                            CircleIconButton(Icons.Rounded.Refresh, "Refaire la photo", onRetake, container = RETAKE_GRAY, size = 170.dp, iconSize = 100.dp)
                        }
                    }
                }
            }

            val countdownHeight = maxHeight * 0.24f
            if (zoomed) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 20.dp)) { CountdownOrSmile(state, countdownHeight) }
            } else {
                PromptBar(state, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TOP_BAR_HEIGHT))
            }
        }

        val canCancel = state.step == BoothStep.LIVE || reviewing
        if (canCancel && !confirmAbort) CancelSessionButton(alpha = 1f - zoom) { confirmAbort = true }
        if (confirmAbort) AbortConfirmOverlay(onConfirm = onAbort, onDismiss = { confirmAbort = false })
    }
}

private val MARGIN = 24.dp
private val TOP_BAR_HEIGHT = 110.dp

private class Area(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

/** Largest box of [aspect] that fits [area], centred in it. */
private fun fit(area: Area, aspect: Float): Area {
    val w = minOf(area.w, area.h * aspect)
    val h = w / aspect
    return Area(area.x + (area.w - w) / 2, area.y + (area.h - h) / 2, w, h)
}

private fun lerp(a: Area, b: Area, t: Float) = Area(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))

private fun layoutAspect(layout: EventLayoutConfigEntity) = layout.canvasWidthPx.toFloat() / layout.canvasHeightPx

@Composable
private fun LiveFeed(frames: SharedFlow<Bitmap>, state: BoothUiState, feed: Area, zoom: Float) {
    val frameColor = BoothColors.Accent
    val corner = 20.dp * (1f - zoom)
    Box(
        Modifier.offset(feed.x, feed.y).size(feed.w, feed.h)
            // The frame is painted outside the feed's own bounds, never over it; it fades out as the feed fills the screen.
            .drawBehind {
                val w = 8.dp.toPx() * (1f - zoom)
                if (w > 0.5f) drawRoundRect(frameColor, Offset(-w, -w), Size(size.width + 2 * w, size.height + 2 * w), CornerRadius(corner.toPx() + w))
            }
            .clip(RoundedCornerShape(corner)).auditZone("capture", "preview"),
    ) {
        // Never mirrored: what the guest sees is what gets printed.
        LiveViewSurface(frames, state.rotationDegrees, mirror = false, Modifier.fillMaxSize(), fill = true)
    }
}

/** Prompt line centred at the top while idle; a message from the booth replaces it. */
@Composable
private fun PromptBar(state: BoothUiState, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            state.message != null ->
                Text(state.message, color = BoothColors.Retake, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            state.step == BoothStep.LIVE -> Text("Prêt ?", color = BoothColors.OnDark, fontSize = 54.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CountdownOrSmile(state: BoothUiState, height: Dp) {
    if (state.step == BoothStep.COUNTDOWN && !state.smile && state.countdown > 0) {
        Countdown(state.countdown, height)
    } else {
        Text(
            "SOURIEZ !", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Black,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(4f, 6f), 16f)),
        )
    }
}

private val RETAKE_GRAY = Color(0xFF6E6E78)

@Composable
private fun Countdown(n: Int, height: Dp) {
    val animated = remember(n) { Animatable(1.25f) }
    LaunchedEffect(n) { animated.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Image(rememberBigText("$n", 560f, Color.White.toArgb()), "$n", Modifier.height(height).scale(animated.value), contentScale = ContentScale.Fit)
}

/**
 * Giant glyphs are pre-rasterized once: animating scaled text of this size makes Skia build a new GPU
 * path mask per frame, which filled the GPU cache with hundreds of render targets.
 */
@Composable
private fun rememberBigText(text: String, sizePx: Float, colorArgb: Int): ImageBitmap = remember(text, sizePx, colorArgb) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = colorArgb
        textAlign = Paint.Align.LEFT
    }
    val shadow = Paint(fill).apply { color = android.graphics.Color.argb(140, 0, 0, 0) }
    val bounds = android.graphics.Rect()
    fill.getTextBounds(text, 0, text.length, bounds)
    val pad = 40
    val bitmap = Bitmap.createBitmap(bounds.width() + 2 * pad, bounds.height() + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val x = pad - bounds.left.toFloat()
    val y = pad - bounds.top.toFloat()
    canvas.drawText(text, x + 10f, y + 10f, shadow)
    canvas.drawText(text, x, y, fill)
    bitmap.asImageBitmap()
}
