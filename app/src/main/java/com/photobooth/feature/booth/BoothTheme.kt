package com.photobooth.feature.booth

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The booth's one look: near-black surfaces, soft off-white text and a single violet-to-pink accent. Nothing is
 * pure white, so the screen stays easy on the eyes in a dark room.
 */
object BoothColors {
    val Background = Color(0xFF08080D)
    val Surface = Color(0xFF14141C)
    val SurfaceHigh = Color(0xFF1E1E29)
    val Outline = Color(0x1FFFFFFF)
    val OnDark = Color(0xFFECECF4)
    val Muted = Color(0xFF8E8EA3)
    val Accent = Color(0xFF8B6CFF)
    val AccentAlt = Color(0xFFFF5C9A)
    val OnAccent = Color(0xFFFDFCFF)
    val Keep = Color(0xFF3DD68C)
    val Retake = Color(0xFFFF6B6B)
    /** Paper of the QR code: light enough to scan, dimmer than white. */
    val Paper = Color(0xFFE9E9F0)

    val AccentBrush: Brush get() = Brush.linearGradient(listOf(Accent, AccentAlt))
}

/** Material colours for the whole app (admin panel included), matching the booth. */
val BoothColorScheme = darkColorScheme(
    primary = BoothColors.Accent,
    onPrimary = BoothColors.OnAccent,
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = BoothColors.OnDark,
    secondary = BoothColors.AccentAlt,
    onSecondary = BoothColors.OnAccent,
    background = BoothColors.Background,
    onBackground = BoothColors.OnDark,
    surface = BoothColors.Background,
    onSurface = BoothColors.OnDark,
    surfaceVariant = BoothColors.SurfaceHigh,
    onSurfaceVariant = BoothColors.Muted,
    surfaceContainer = BoothColors.Surface,
    surfaceContainerHigh = BoothColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF272733),
    outline = Color(0xFF3A3A4A),
    error = BoothColors.Retake,
)

@Composable
fun BoothTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = BoothColorScheme, content = content)

/** Slow drifting glows behind every guest screen: enough life to feel dynamic, never bright. */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(27_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier.background(BoothColors.Background)) {
        val r = size.maxDimension * 0.62f
        drawRect(
            Brush.radialGradient(
                listOf(BoothColors.Accent.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * (0.05f + 0.5f * a), size.height * (0.1f + 0.25f * b)), radius = r,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(BoothColors.AccentAlt.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * (0.95f - 0.5f * b), size.height * (0.95f - 0.3f * a)), radius = r,
            ),
        )
    }
}

/** Shrinks slightly while pressed and springs back: the tactile answer of every booth control. */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** Fades and rises into place [delayMs] after entering the composition; used to stagger a screen's content. */
fun Modifier.enter(delayMs: Int = 0, rise: Dp = 28.dp): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(520, easing = FastOutSlowInEasing), label = "enter")
    graphicsLayer {
        alpha = p
        translationY = (1f - p) * rise.toPx()
    }
}

/** Translucent panel with a hairline border. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(32.dp), color: Color = BoothColors.Surface.copy(alpha = 0.82f)): Modifier =
    clip(shape).background(color).border(1.dp, BoothColors.Outline, shape)

enum class BoothButtonStyle { PRIMARY, GLASS, DANGER }

/** Pill button readable from arm's length. PRIMARY carries the accent gradient; GLASS is the quiet secondary. */
@Composable
fun BoothButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BoothButtonStyle = BoothButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fontSize: Int = 30,
    minHeight: Dp = 92.dp,
    horizontalPadding: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(50)
    val content = if (style == BoothButtonStyle.GLASS) BoothColors.OnDark else BoothColors.OnAccent
    Row(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled, onClick = onClick)
            .defaultMinSize(minHeight = minHeight)
            .then(
                when (style) {
                    BoothButtonStyle.PRIMARY -> Modifier.clip(shape).background(BoothColors.AccentBrush)
                    BoothButtonStyle.DANGER -> Modifier.clip(shape).background(BoothColors.Retake.copy(alpha = 0.9f))
                    BoothButtonStyle.GLASS -> Modifier.glass(shape, BoothColors.SurfaceHigh.copy(alpha = 0.85f))
                },
            )
            .padding(horizontal = horizontalPadding, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size((fontSize * 1.3f).dp), tint = content)
        Text(text, color = content, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Round icon action. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = BoothColors.Accent,
    tint: Color = BoothColors.Background,
    outlined: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    iconSize: Dp = 36.dp,
) {
    Box(
        modifier.size(size).graphicsLayer { alpha = if (enabled) 1f else 0.35f }.pressable(enabled, onClick = onClick)
            .then(if (outlined) Modifier.glass(CircleShape, container) else Modifier.clip(CircleShape).background(container)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}
