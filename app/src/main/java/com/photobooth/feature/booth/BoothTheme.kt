package com.photobooth.feature.booth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventThemeDefaults
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Resolved art direction of the active event; every guest screen reads colours from here. */
@Immutable
data class BoothPalette(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val text: Color,
    val backgroundImage: File?,
) {
    companion object {
        fun from(event: EventEntity?): BoothPalette = BoothPalette(
            primary = parseHexColor(event?.primaryColorHex, EventThemeDefaults.PRIMARY),
            secondary = parseHexColor(event?.secondaryColorHex, EventThemeDefaults.SECONDARY),
            background = parseHexColor(event?.backgroundColorHex, EventThemeDefaults.BACKGROUND),
            text = parseHexColor(event?.textColorHex, EventThemeDefaults.TEXT),
            backgroundImage = event?.customBackgroundImagePath?.let(::File)?.takeIf { it.isFile },
        )
    }
}

val LocalBoothPalette = staticCompositionLocalOf { BoothPalette.from(null) }

/** Accepts #RRGGBB or #AARRGGBB; anything else falls back to [fallbackHex]. */
fun parseHexColor(hex: String?, fallbackHex: String): Color {
    fun parse(value: String?): Color? = value?.trim()?.takeIf { Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(it) }
        ?.let { Color(android.graphics.Color.parseColor(it)) }
    return parse(hex) ?: parse(fallbackHex) ?: Color.Black
}

/** Black or white, whichever reads better on [background]. */
fun contrastOn(background: Color): Color = if (background.luminance() > 0.55f) Color(0xFF101018) else Color.White

/**
 * Applies the active event's art direction (colours and background image) to everything inside, including
 * Material components. Passing null yields the default look.
 */
@Composable
fun EventTheme(event: EventEntity?, content: @Composable () -> Unit) {
    val palette = remember(
        event?.primaryColorHex, event?.secondaryColorHex, event?.backgroundColorHex,
        event?.textColorHex, event?.customBackgroundImagePath,
    ) { BoothPalette.from(event) }
    val panel = remember(palette) { lerp(palette.background, palette.text, 0.10f) }
    val base = if (palette.background.luminance() > 0.5f) lightColorScheme() else darkColorScheme()
    val scheme = remember(palette, base) {
        base.copy(
            primary = palette.primary,
            onPrimary = contrastOn(palette.primary),
            secondary = palette.secondary,
            onSecondary = contrastOn(palette.secondary),
            background = palette.background,
            onBackground = palette.text,
            surface = panel,
            onSurface = palette.text,
        )
    }
    CompositionLocalProvider(LocalBoothPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Solid event background colour with the optional event image on top, softened so text stays readable. */
@Composable
fun EventBackdrop(modifier: Modifier = Modifier) {
    val palette = LocalBoothPalette.current
    Box(modifier.background(palette.background)) {
        palette.backgroundImage?.let { file ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(file).size(1800).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.25f)))
        }
    }
}

object BoothColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.background
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.let { lerp(it.background, it.text, 0.10f) }
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.primary
    val AccentAlt: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.secondary
    val OnDark: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalBoothPalette.current.text.copy(alpha = 0.7f)
    val OnAccent: Color @Composable @ReadOnlyComposable get() = contrastOn(LocalBoothPalette.current.primary)
    val Keep = Color(0xFF00C853)
    val Retake = Color(0xFFFF5252)
}

/** Oversized pill button readable from arm's length; at least 96dp tall. */
@Composable
fun BoothButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = BoothColors.Accent,
    content: Color = contrastOn(container),
    enabled: Boolean = true,
    fontSize: Int = 34,
    minHeight: Dp = 96.dp,
    horizontalPadding: Dp = 48.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = content.copy(alpha = 0.6f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
    ) {
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
