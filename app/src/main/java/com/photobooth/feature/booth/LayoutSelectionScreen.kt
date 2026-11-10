package com.photobooth.feature.booth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photobooth.domain.model.LayoutType

/** Fixed grid: every enabled layout gets an equal cell, sized from the available space (no scrolling). */
@Composable
fun LayoutSelectionScreen(state: BoothUiState, onSelect: (LayoutType) -> Unit, onCancel: () -> Unit) {
    val layouts = state.layouts
    val columns = when {
        layouts.size <= 4 -> layouts.size.coerceAtLeast(1)
        layouts.size <= 8 -> 4
        else -> 5
    }
    val rows = (layouts.size + columns - 1) / columns
    DisposableEffect(Unit) { onDispose { UiAudit.clear("layouts") } }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val titleSp = (maxHeight.value * 0.06f).coerceIn(26f, 56f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.enter(0), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choisissez votre format", color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, maxLines = 1)
                Text("Touchez la composition qui vous plaît", color = BoothColors.Muted, fontSize = (titleSp * 0.42f).sp, maxLines = 1)
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(rows) { r ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        repeat(columns) { c ->
                            val index = r * columns + c
                            val cfg = layouts.getOrNull(index)
                            if (cfg == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                LayoutCard(
                                    title = cfg.layoutType.frenchTitle(),
                                    subtitle = "${cfg.photoCount} photo${if (cfg.photoCount > 1) "s" else ""}",
                                    preview = state.layoutPreviews[cfg.layoutType],
                                    onClick = { onSelect(cfg.layoutType) },
                                    modifier = Modifier.weight(1f).fillMaxHeight().enter(100 + index * 70).auditZone("layouts", cfg.layoutType.name),
                                )
                            }
                        }
                    }
                }
            }
            BoothButton("Annuler", onCancel, Modifier.enter(250), style = BoothButtonStyle.GLASS, fontSize = 22, minHeight = 64.dp)
        }
    }
}

@Composable
private fun LayoutCard(title: String, subtitle: String, preview: android.graphics.Bitmap?, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.pressable(pressedScale = 0.96f, onClick = onClick).glass(RoundedCornerShape(32.dp))) {
        val titleSp = (maxHeight.value * 0.07f).coerceIn(15f, 30f)
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                if (preview != null) Image(
                    preview.asImageBitmap(), title,
                    Modifier.aspectRatio(preview.width.toFloat() / preview.height).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit,
                )
                else CircularProgressIndicator(Modifier.size(40.dp), color = BoothColors.Accent)
            }
            Text(title, color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = BoothColors.Muted, fontSize = (titleSp * 0.72f).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
