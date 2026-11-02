package com.photobooth.feature.booth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
        val titleSp = (maxHeight.value * 0.06f).coerceIn(24f, 56f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choisissez votre format", color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text("Touchez la composition de votre choix", color = BoothColors.Muted, fontSize = (titleSp * 0.45f).sp, maxLines = 1)
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(rows) { r ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(columns) { c ->
                            val cfg = layouts.getOrNull(r * columns + c)
                            if (cfg == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                LayoutCard(
                                    title = cfg.layoutType.frenchTitle(),
                                    subtitle = "${cfg.photoCount} photo${if (cfg.photoCount > 1) "s" else ""}",
                                    preview = state.layoutPreviews[cfg.layoutType],
                                    onClick = { onSelect(cfg.layoutType) },
                                    modifier = Modifier.weight(1f).fillMaxHeight().auditZone("layouts", cfg.layoutType.name),
                                )
                            }
                        }
                    }
                }
            }
            BoothButton("ANNULER", onCancel, container = BoothColors.Panel, fontSize = 22, minHeight = 64.dp)
        }
    }
}

@Composable
private fun LayoutCard(title: String, subtitle: String, preview: android.graphics.Bitmap?, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(28.dp)).background(BoothColors.Panel).clickable(onClick = onClick)) {
        val titleSp = (maxHeight.value * 0.085f).coerceIn(15f, 34f)
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) Image(preview.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else CircularProgressIndicator(Modifier.size(40.dp))
            }
            Text(title, color = BoothColors.OnDark, fontSize = titleSp.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = BoothColors.AccentAlt, fontSize = (titleSp * 0.75f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
