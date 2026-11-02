package com.photobooth.feature.booth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Circular icon-only action button; the icon is tinted for contrast against [container]. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = BoothColors.Accent,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    iconSize: Dp = 36.dp,
) {
    val background = if (enabled) container else container.copy(alpha = 0.3f)
    Box(
        modifier.size(size).clip(CircleShape).background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = contrastOn(container).copy(alpha = if (enabled) 1f else 0.5f))
    }
}

/** Camera-style shutter: an outer ring around a solid disc, in the event's primary colour. */
@Composable
fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 144.dp) {
    val color = BoothColors.Accent
    Box(
        modifier.size(size).clip(CircleShape).semantics { contentDescription = "Prendre la photo" }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val ring = this.size.minDimension * 0.06f
            drawCircle(color, radius = this.size.minDimension / 2f - ring / 2f, center = Offset(this.size.width / 2f, this.size.height / 2f), style = Stroke(ring))
            drawCircle(color, radius = this.size.minDimension / 2f - ring * 2.2f, center = Offset(this.size.width / 2f, this.size.height / 2f))
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
