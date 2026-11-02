package com.photobooth.feature.booth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photobooth.domain.model.PhotoFilterType

/** Left: the finished montage. Right: filters, copy counter, print, and the session QR code, all in view at once. */
@Composable
fun FinalSummaryScreen(
    state: BoothUiState,
    onFilter: (PhotoFilterType) -> Unit,
    onCopies: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmAbort by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { UiAudit.clear("final") } }

    Row(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(0.55f).fillMaxHeight().auditZone("final", "stage"), contentAlignment = Alignment.Center) {
            val preview = state.finalPreview
            if (preview != null) {
                Image(preview.asImageBitmap(), "Composition finale", Modifier.fillMaxSize().auditZone("final", "preview"), contentScale = ContentScale.Fit)
            }
            if (state.isRendering) CircularProgressIndicator(Modifier.size(72.dp), color = BoothColors.Accent)
        }
        Column(
            Modifier.weight(0.45f).fillMaxHeight().auditZone("final", "controls"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        ) {
            Text("Votre photo est prête !", color = BoothColors.OnDark, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 1)
            state.message?.let { Text(it, color = BoothColors.Retake, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }

            if (state.showFilterBar) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.filters.forEach { f -> FilterPill(f, f == state.filter, Modifier.weight(1f)) { onFilter(f) } }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoundStepper("−", state.copies > 1) { onCopies(state.copies - 1) }
                Text("${state.copies}", color = BoothColors.OnDark, fontSize = 64.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
                RoundStepper("+", state.copies < state.maxCopies) { onCopies(state.copies + 1) }
            }

            if (confirmAbort) {
                Text("Supprimer toutes les photos de cette session ?", color = BoothColors.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    BoothButton("SUPPRIMER", onCancel, Modifier.weight(1f), container = BoothColors.Retake, fontSize = 20, minHeight = 64.dp, horizontalPadding = 8.dp)
                    BoothButton("CONTINUER", { confirmAbort = false }, Modifier.weight(1f), container = BoothColors.Panel, fontSize = 20, minHeight = 64.dp, horizontalPadding = 8.dp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    CircleIconButton(Icons.Rounded.Close, "Annuler la session", { confirmAbort = true }, container = BoothColors.Panel, size = 80.dp, iconSize = 44.dp)
                    CircleIconButton(PrintIcon, "Imprimer", onConfirm, container = BoothColors.Keep, enabled = !state.isRendering, size = 120.dp, iconSize = 72.dp)
                }
            }

        }
    }
}

@Composable
private fun FilterPill(filter: PhotoFilterType, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(72.dp).clip(RoundedCornerShape(24.dp))
            .background(if (selected) BoothColors.Accent else BoothColors.Panel)
            .border(3.dp, if (selected) BoothColors.OnAccent else BoothColors.OnDark.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            filter.label, color = if (selected) BoothColors.OnAccent else BoothColors.OnDark,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

/** A disabled stepper (counter at its limit) is simply dimmed; no limit text is shown. */
@Composable
private fun RoundStepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(if (enabled) BoothColors.AccentAlt else BoothColors.Panel.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) contrastOn(BoothColors.AccentAlt) else BoothColors.OnDark.copy(alpha = 0.3f), fontSize = 44.sp, fontWeight = FontWeight.Black)
    }
}

/** QR code scaled to whatever room the column has left, with its caption underneath. */
@Composable
private fun QrBlock(share: ShareUi, modifier: Modifier, caption: String) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = (minOf(maxWidth, maxHeight - 40.dp)).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                share.qr.asImageBitmap(), "QR code de téléchargement",
                Modifier.size(side).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp),
            )
            Text(caption, color = BoothColors.Muted, fontSize = 18.sp, maxLines = 1)
        }
    }
}

@Composable
fun ThanksScreen(state: BoothUiState, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxSize().safeDrawingPadding().padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("MERCI !", color = BoothColors.Accent, fontSize = 110.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (state.isRendering) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator(Modifier.size(48.dp), color = BoothColors.Accent)
                    Text("Préparation de votre photo…", color = BoothColors.OnDark, fontSize = 32.sp)
                }
            } else {
                val plural = if (state.copies > 1) "s" else ""
                if (state.printWaiting) {
                    Text("Impression en attente", color = BoothColors.OnDark, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("L'imprimante est momentanément indisponible, votre photo sera imprimée dès que possible.", color = BoothColors.Muted, fontSize = 28.sp)
                } else {
                    Text("Impression de ${state.copies} copie$plural en cours", color = BoothColors.OnDark, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("Récupérez votre photo à la sortie de l'imprimante.", color = BoothColors.Muted, fontSize = 28.sp)
                }
            }
            Text("Retour à l'accueil dans ${state.thanksSecondsLeft} s", color = BoothColors.AccentAlt, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            BoothButton("TERMINER", onDone, container = BoothColors.Panel, fontSize = 26, minHeight = 80.dp)
        }
        state.share?.let { share ->
            Column(Modifier.weight(0.9f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scannez pour récupérer vos fichiers", color = BoothColors.OnDark, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                QrBlock(share, Modifier.weight(1f).fillMaxWidth(), "Même Wi-Fi que la borne")
            }
        }
    }
}
