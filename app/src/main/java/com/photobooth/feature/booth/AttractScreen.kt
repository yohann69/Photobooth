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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.io.File

/** Welcome screen: the event's name over its recent photos (or the ambient glow), and one thing to do. */
@Composable
fun AttractScreen(state: BoothUiState, onStart: () -> Unit) {
    val breathe = rememberInfiniteTransition(label = "cta")
    val scale by breathe.animateFloat(
        1f, 1.04f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )

    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStart),
    ) {
        if (state.slideshowEnabled && state.slideshow.isNotEmpty()) {
            KenBurnsSlideshow(state.slideshow)
            // Photos stay a backdrop: darkened overall, and more where the text sits.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            BoothColors.Background.copy(alpha = 0.9f), BoothColors.Background.copy(alpha = 0.68f),
                            BoothColors.Background.copy(alpha = 0.92f),
                        ),
                    ),
                ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(56.dp)) {
            val titleSp = (minOf(maxHeight.value * 0.13f, maxWidth.value * 0.085f)).coerceIn(40f, 120f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "PHOTOBOOTH", Modifier.enter(0).glass(RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp),
                    color = BoothColors.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp,
                )
                Text(
                    state.event?.name ?: "Photobooth", Modifier.enter(120),
                    color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    lineHeight = (titleSp * 1.05f).sp, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Column(Modifier.enter(260), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    state.message?.let {
                        Text(
                            it, Modifier.glass(RoundedCornerShape(50), BoothColors.Retake.copy(alpha = 0.16f)).padding(horizontal = 28.dp, vertical = 12.dp),
                            color = BoothColors.Retake, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        )
                    }
                    Box(Modifier.padding(40.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.pulseOutline().graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(CircleShape).background(BoothColors.AccentBrush).padding(horizontal = 72.dp, vertical = 30.dp),
                        ) {
                            Text("Touchez pour commencer", color = BoothColors.OnAccent, fontSize = (titleSp * 0.34f).coerceAtLeast(26f).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
