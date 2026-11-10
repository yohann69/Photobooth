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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Left: the finished montage. Right: copy counter and the confirm button; cancel stays where it is on the capture screen. */
@Composable
fun FinalSummaryScreen(
    state: BoothUiState,
    onCopies: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmAbort by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { UiAudit.clear("final") } }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().auditZone("final", "stage"), contentAlignment = Alignment.Center) {
                val preview = state.finalPreview
                if (preview != null) {
                    Image(preview.asImageBitmap(), "Composition finale", Modifier.fillMaxSize().auditZone("final", "preview"), contentScale = ContentScale.Fit)
                }
                if (state.isRendering) CircularProgressIndicator(Modifier.size(72.dp), color = BoothColors.Accent)
            }
            Column(
                Modifier.width(SidePanelWidth).fillMaxHeight().padding(bottom = 100.dp).auditZone("final", "controls"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
            ) {
                Text("Votre photo est prête !", color = BoothColors.OnDark, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 1)
                state.message?.let { Text(it, color = BoothColors.Retake, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RoundStepper("−", state.copies > 0) { onCopies(state.copies - 1) }
                        Text("${state.copies}", color = BoothColors.OnDark, fontSize = 64.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
                        RoundStepper("+", state.copies < state.maxCopies) { onCopies(state.copies + 1) }
                    }
                    Text(
                        if (state.copies == 0) "Pas d'impression : photos à télécharger" else "Copies à imprimer",
                        color = BoothColors.Muted, fontSize = 20.sp, textAlign = TextAlign.Center,
                    )
                }

                val printing = state.copies > 0
                WideActionButton(
                    label = if (printing) "IMPRIMER" else "TERMINER",
                    icon = if (printing) PrintIcon else Icons.Rounded.Check,
                    enabled = !state.isRendering,
                    onClick = onConfirm,
                )
            }
        }
        if (!confirmAbort) CancelSessionButton { confirmAbort = true }
        if (confirmAbort) AbortConfirmOverlay(onConfirm = onCancel, onDismiss = { confirmAbort = false })
    }
}

/** Wide green confirm button: icon and label side by side. */
@Composable
private fun WideActionButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val container = BoothColors.Keep
    Row(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(48.dp))
            .background(if (enabled) container else container.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(52.dp), tint = contrastOn(container))
        Text(label, color = contrastOn(container), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
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
                if (state.copies == 0) {
                    Text("Vos photos sont prêtes", color = BoothColors.OnDark, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    if (state.share != null || state.shareLoading) Text("Scannez le QR code pour les télécharger.", color = BoothColors.Muted, fontSize = 28.sp)
                } else if (state.printWaiting) {
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
        if (state.share != null || state.shareLoading || state.shareFailed) {
            Column(Modifier.weight(0.9f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scannez pour récupérer vos photos", color = BoothColors.OnDark, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                when {
                    state.share != null -> QrBlock(state.share, Modifier.weight(1f).fillMaxWidth(), "Disponible même loin de la borne")
                    state.shareLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(64.dp), color = BoothColors.Accent)
                            Text("Préparation du lien…", color = BoothColors.Muted, fontSize = 24.sp)
                        }
                    }
                    else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Lien de téléchargement indisponible pour le moment.", color = BoothColors.Muted, fontSize = 24.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
