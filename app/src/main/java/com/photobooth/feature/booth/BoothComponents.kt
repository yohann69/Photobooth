package com.photobooth.feature.booth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Headline in the accent gradient. */
@Composable
fun GradientText(text: String, fontSize: TextUnit, modifier: Modifier = Modifier, fontWeight: FontWeight = FontWeight.Black) {
    Text(
        text, modifier, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1,
        style = TextStyle(brush = BoothColors.AccentBrush, letterSpacing = (-1).sp),
    )
}

/** Two rings that expand and fade around a call to action. */
@Composable
fun PulseRings(modifier: Modifier = Modifier, color: Color = BoothColors.Accent, periodMs: Int = 2_400) {
    val t = rememberInfiniteTransition(label = "rings")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val base = size.minDimension / 2f
        for (i in 0..1) {
            val p = (phase + i * 0.5f) % 1f
            drawCircle(color.copy(alpha = 0.45f * (1f - p)), radius = base * (0.62f + 0.38f * p), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/** Outlines that grow out of a pill-shaped control and fade, inviting the tap. */
fun Modifier.pulseOutline(color: Color = BoothColors.AccentAlt, reach: Dp = 34.dp, periodMs: Int = 2_600): Modifier = composed {
    val t = rememberInfiniteTransition(label = "outline")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    drawBehind {
        for (i in 0..1) {
            val p = (phase + i * 0.5f) % 1f
            val d = reach.toPx() * p
            drawRoundRect(
                color.copy(alpha = 0.5f * (1f - p)), Offset(-d, -d), Size(size.width + 2 * d, size.height + 2 * d),
                CornerRadius(size.height / 2f + d), style = Stroke(2.5.dp.toPx()),
            )
        }
    }
}

/** Camera shutter: a pale ring around the accent disc, with rings pulsing outwards to invite the tap. */
@Composable
fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 144.dp) {
    val breathe = rememberInfiniteTransition(label = "shutter")
    val scale by breathe.animateFloat(
        1f, 1.05f, infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )
    Box(modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        PulseRings(Modifier.fillMaxSize())
        Box(
            Modifier.size(size).semantics { contentDescription = "Prendre la photo" }.pressable(pressedScale = 0.88f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val ring = this.size.minDimension * 0.045f
                drawCircle(BoothColors.OnDark, radius = this.size.minDimension / 2f - ring / 2f, style = Stroke(ring))
                drawCircle(BoothColors.AccentBrush, radius = (this.size.minDimension / 2f - ring * 2.4f) * scale / 1.05f)
            }
        }
    }
}

/** One dot per photo of the layout: done, current (wide) or still to come. */
@Composable
fun StepDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val width by animateFloatAsState(if (i == current) 44f else 14f, spring(stiffness = Spring.StiffnessLow), label = "dot")
            Box(
                Modifier.height(14.dp).width(width.dp).clip(CircleShape).then(
                    when {
                        i == current -> Modifier.background(BoothColors.AccentBrush)
                        i < current -> Modifier.background(BoothColors.OnDark.copy(alpha = 0.85f))
                        else -> Modifier.background(BoothColors.OnDark.copy(alpha = 0.18f))
                    },
                ),
            )
        }
    }
}

private const val PRINT_PATH =
    "M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z"

/** Printer glyph; material-icons-extended is not a dependency, so it is drawn from path data. */
val PrintIcon: ImageVector by lazy {
    ImageVector.Builder("Print", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(PRINT_PATH).toNodes(), fill = SolidColor(Color.Black))
        .build()
}

/** Width of the right-hand control column on the capture and summary screens, so the cancel button sits under both. */
val SidePanelWidth = 420.dp

/** The one cancel control of the capture and summary screens: bottom right, centred under the side column. */
@Composable
fun BoxScope.CancelSessionButton(alpha: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp).width(SidePanelWidth).graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        BoothButton("Annuler", onClick, style = BoothButtonStyle.GLASS, fontSize = 24, minHeight = 68.dp, horizontalPadding = 44.dp)
    }
}

/** Full-screen scrim with the delete-everything question; taps outside the card are swallowed. */
@Composable
fun AbortConfirmOverlay(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f * appear.value.coerceIn(0f, 1f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(560.dp)
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * appear.value
                    scaleY = 0.9f + 0.1f * appear.value
                }
                .glass(RoundedCornerShape(36.dp), BoothColors.Surface).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Tout supprimer ?", color = BoothColors.OnDark, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "Les photos de cette session seront effacées.",
                color = BoothColors.Muted, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp),
            )
            BoothButton("Continuer la session", onDismiss, Modifier.fillMaxWidth(), fontSize = 26, minHeight = 80.dp)
            BoothButton("Supprimer", onConfirm, Modifier.fillMaxWidth(), style = BoothButtonStyle.DANGER, fontSize = 26, minHeight = 80.dp)
        }
    }
}
