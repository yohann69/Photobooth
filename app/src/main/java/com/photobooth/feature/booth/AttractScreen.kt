package com.photobooth.feature.booth

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AttractScreen(state: BoothUiState, onStart: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )
    val glow by pulse.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )

    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStart),
    ) {
        val palette = LocalBoothPalette.current
        if (state.slideshowEnabled && state.slideshow.isNotEmpty()) KenBurnsSlideshow(state.slideshow)
        else if (palette.backgroundImage == null) AnimatedBackdrop()
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(palette.background.copy(alpha = 0.7f), palette.background.copy(alpha = 0.2f), palette.background.copy(alpha = 0.85f)),
                ),
            ),
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(56.dp)) {
        val titleSp = (maxHeight.value * 0.13f).coerceIn(40f, 110f)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.event?.name ?: "Photobooth",
                    color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    lineHeight = (titleSp * 1.08f).sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                state.message?.let {
                    Text(it, color = BoothColors.Retake, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                }
                Box(
                    Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(BoothColors.Accent.copy(alpha = glow), RoundedCornerShape(50))
                        .padding(horizontal = 80.dp, vertical = 32.dp),
                ) {
                    Text("PRENDRE DES PHOTOS", color = BoothColors.OnAccent, fontSize = (titleSp * 0.42f).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                }
            }
        }
        }
    }
}

@Composable
private fun AnimatedBackdrop() {
    val accent = BoothColors.Accent
    val accentAlt = BoothColors.AccentAlt
    val base = BoothColors.Background
    val t = rememberInfiniteTransition(label = "backdrop")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "phase")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * (0.2f + 0.6f * phase), size.height * 0.3f), radius = size.minDimension * 0.9f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(accentAlt.copy(alpha = 0.40f), Color.Transparent),
                center = Offset(size.width * (0.8f - 0.6f * phase), size.height * 0.75f), radius = size.minDimension * 0.9f,
            ),
        )
    }
}

/** Slow zoom-and-pan on each recent photo, cross-faded to the next one. */
@Composable
private fun KenBurnsSlideshow(files: List<File>) {
    var index by remember(files) { mutableIntStateOf(0) }
    LaunchedEffect(files) {
        while (files.size > 1) {
            delay(SLIDE_MS)
            index = (index + 1) % files.size
        }
    }
    Crossfade(targetState = index, animationSpec = tween(1_400), label = "slides") { i ->
        KenBurnsImage(files[i % files.size], seed = i)
    }
}

@Composable
private fun KenBurnsImage(file: File, seed: Int) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween((SLIDE_MS + 1_800).toInt(), easing = LinearEasing)) }
    val direction = if (seed % 2 == 0) 1f else -1f
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(file).size(1800).crossfade(false).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val s = 1.04f + 0.16f * progress.value
            scaleX = s
            scaleY = s
            translationX = direction * size.width * 0.04f * (progress.value - 0.5f)
            translationY = -size.height * 0.03f * (progress.value - 0.5f)
        },
    )
}

private const val SLIDE_MS = 6_500L
