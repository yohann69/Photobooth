package com.photobooth.feature.booth

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.model.PhotoSlot
import com.photobooth.feature.camera.LiveViewSurface
import kotlinx.coroutines.flow.SharedFlow

/**
 * Capture and review in one three-zone screen so nothing ever sits in front of the stage:
 * left = photos kept so far, centre = live feed (or the progressive composition once a shot is taken),
 * right = countdown and every control.
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
    val slots = layout.captureSlots()
    val reviewing = state.step == BoothStep.REVIEW
    // The feed has exactly the aspect of the slot being shot, so the preview is the printed framing.
    val feedAspect = slots.getOrNull(state.slotIndex)?.aspectRatio ?: layoutAspect(layout)
    var confirmAbort by remember { mutableStateOf(false) }
    LaunchedEffect(state.step) { if (state.step != BoothStep.LIVE && state.step != BoothStep.REVIEW) confirmAbort = false }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.flashToken) {
        if (state.flashToken > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(500))
        }
    }
    DisposableEffect(Unit) { onDispose { UiAudit.clear("capture") } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        TopBar(state, reviewing, Modifier.fillMaxWidth().height(84.dp).auditZone("capture", "topbar"))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            CapturedStrip(state, slots, Modifier.weight(0.22f).fillMaxHeight().auditZone("capture", "strip"))
            Stage(state, frames, feedAspect, flash.value, reviewing, Modifier.weight(0.58f).fillMaxHeight().auditZone("capture", "stage"))
            ControlPanel(
                state, reviewing, confirmAbort, { confirmAbort = it }, onTakePhoto, onKeep, onRetake, onAbort,
                Modifier.weight(0.20f).fillMaxHeight().auditZone("capture", "controls"),
            )
        }
    }
}

private fun layoutAspect(layout: EventLayoutConfigEntity) = layout.canvasWidthPx.toFloat() / layout.canvasHeightPx

@Composable
private fun CapturedStrip(state: BoothUiState, slots: List<PhotoSlot>, modifier: Modifier) {
    val done = state.photos.count { it != null }
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "$done/${slots.size}",
            color = BoothColors.OnAccent, fontSize = 34.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.background(BoothColors.Accent, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slots.forEachIndexed { i, slot ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val current = i == state.slotIndex && state.photos.getOrNull(i) == null
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.aspectRatio(slot.aspectRatio).clip(shape).background(BoothColors.Panel)
                            .border(if (current) 4.dp else 1.dp, if (current) BoothColors.Accent else BoothColors.OnDark.copy(alpha = 0.25f), shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val file = state.photos.getOrNull(i)
                        if (file != null) AsyncImage(file, "Photo ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("${i + 1}", color = BoothColors.OnDark.copy(alpha = if (current) 0.9f else 0.35f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stage(
    state: BoothUiState,
    frames: SharedFlow<Bitmap>,
    feedAspect: Float,
    flash: Float,
    reviewing: Boolean,
    modifier: Modifier,
) {
    val mirror = state.event?.mirrorLivePreview ?: true
    val accent = BoothColors.Accent
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Crossfade(targetState = reviewing, animationSpec = tween(250), label = "stage") { review ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (review) {
                    val composition = state.reviewPreview
                    if (composition != null) {
                        Image(
                            composition.asImageBitmap(), "Composition avec la nouvelle photo", Modifier.fillMaxSize().auditZone("capture", "preview"),
                            contentScale = ContentScale.Fit,
                        )
                    } else state.reviewFile?.let {
                        AsyncImage(it, "Photo prise", Modifier.fillMaxSize().auditZone("capture", "preview"), contentScale = ContentScale.Fit)
                    }
                } else {
                    val shape = RoundedCornerShape(20.dp)
                    val frameColor = lerp(accent, Color.White, flash)
                    Box(
                        Modifier.aspectRatio(feedAspect)
                            // The frame is painted outside the feed's own bounds, never over it.
                            .drawBehind {
                                val w = 8.dp.toPx()
                                drawRoundRect(frameColor, Offset(-w, -w), Size(size.width + 2 * w, size.height + 2 * w), CornerRadius(20.dp.toPx() + w))
                            }
                            .clip(shape).auditZone("capture", "preview"),
                    ) {
                        LiveViewSurface(frames, state.rotationDegrees, mirror, Modifier.fillMaxSize(), fill = true)
                    }
                }
            }
        }
    }
}

/** Prompt line centred at the top of the screen, outside every other zone. */
@Composable
private fun TopBar(state: BoothUiState, reviewing: Boolean, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            state.message != null ->
                Text(state.message, color = BoothColors.Retake, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            state.smile || state.step == BoothStep.CAPTURING ->
                Text("SOURIEZ !", color = BoothColors.Accent, fontSize = 54.sp, fontWeight = FontWeight.Black)
            state.step == BoothStep.COUNTDOWN ->
                Text("Regardez la caméra", color = BoothColors.OnDark, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            reviewing -> Text(
                "Photo ${state.slotIndex + 1} sur ${state.totalSlots}",
                color = BoothColors.OnAccent, fontSize = 44.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(BoothColors.Accent, RoundedCornerShape(50)).padding(horizontal = 40.dp, vertical = 6.dp),
            )
            else -> Text("Prêt ?", color = BoothColors.OnDark, fontSize = 54.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ControlPanel(
    state: BoothUiState,
    reviewing: Boolean,
    confirmAbort: Boolean,
    setConfirmAbort: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onKeep: () -> Unit,
    onRetake: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        // The main control sits on the panel's vertical centre; the cancel button is parked at the bottom.
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            when {
                confirmAbort -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Supprimer toutes les photos de cette session ?", color = BoothColors.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    BoothButton("SUPPRIMER", onAbort, Modifier.fillMaxWidth(), container = BoothColors.Retake, fontSize = 20, minHeight = 64.dp, horizontalPadding = 8.dp)
                    BoothButton("CONTINUER", { setConfirmAbort(false) }, Modifier.fillMaxWidth(), container = BoothColors.Panel, fontSize = 20, minHeight = 64.dp, horizontalPadding = 8.dp)
                }
                state.step == BoothStep.LIVE -> ShutterButton(onTakePhoto, size = 160.dp)
                reviewing -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    CircleIconButton(Icons.Rounded.Check, "Conserver la photo", onKeep, container = BoothColors.Keep, size = 160.dp, iconSize = 96.dp)
                    if (state.event?.allowRetakes != false) {
                        CircleIconButton(Icons.Rounded.Refresh, "Refaire la photo", onRetake, container = RETAKE_GRAY, size = 160.dp, iconSize = 96.dp)
                    }
                }
                state.step == BoothStep.COUNTDOWN && !state.smile -> Countdown(state.countdown)
            }
        }
        if (!confirmAbort && (state.step == BoothStep.LIVE || reviewing)) {
            CircleIconButton(
                Icons.Rounded.Close, "Annuler la session", { setConfirmAbort(true) },
                Modifier.align(Alignment.BottomCenter), container = BoothColors.Panel,
            )
        }
    }
}

private val RETAKE_GRAY = Color(0xFF6E6E78)

@Composable
private fun Countdown(n: Int) {
    val animated = remember(n) { Animatable(1.25f) }
    LaunchedEffect(n) { animated.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Image(rememberBigText("$n", 560f, BoothColors.Accent.toArgb()), "$n", Modifier.fillMaxWidth(0.7f).scale(animated.value))
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
    val shadow = Paint(fill).apply { color = android.graphics.Color.argb(90, 0, 0, 0) }
    val bounds = android.graphics.Rect()
    fill.getTextBounds(text, 0, text.length, bounds)
    val pad = 40
    val bitmap = Bitmap.createBitmap(bounds.width() + 2 * pad, bounds.height() + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val x = pad - bounds.left.toFloat()
    val y = pad - bounds.top.toFloat()
    canvas.drawText(text, x + 8f, y + 8f, shadow)
    canvas.drawText(text, x, y, fill)
    bitmap.asImageBitmap()
}
