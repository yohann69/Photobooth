package com.photobooth.feature.booth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
                val reveal by animateFloatAsState(
                    if (preview != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "reveal",
                )
                if (preview != null) {
                    Image(
                        preview.asImageBitmap(), "Composition finale",
                        Modifier.aspectRatio(preview.width.toFloat() / preview.height).graphicsLayer {
                            alpha = reveal.coerceIn(0f, 1f)
                            scaleX = 0.92f + 0.08f * reveal
                            scaleY = 0.92f + 0.08f * reveal
                        }.clip(RoundedCornerShape(14.dp)).auditZone("final", "preview"),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (state.isRendering) CircularProgressIndicator(Modifier.size(64.dp), color = BoothColors.Accent, strokeWidth = 5.dp)
            }
            Column(
                Modifier.width(SidePanelWidth).fillMaxHeight().padding(bottom = 100.dp).auditZone("final", "controls"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                Column(Modifier.enter(80), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradientText("C'est dans la boîte", 38.sp, fontWeight = FontWeight.Black)
                    state.message?.let { Text(it, color = BoothColors.Retake, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
                }

                Column(
                    Modifier.enter(180).fillMaxWidth().glass(RoundedCornerShape(36.dp)).padding(vertical = 28.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("COPIES À IMPRIMER", color = BoothColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RoundStepper("−", state.copies > 0) { onCopies(state.copies - 1) }
                        AnimatedContent(
                            targetState = state.copies,
                            transitionSpec = {
                                val up = targetState > initialState
                                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                            },
                            label = "copies",
                        ) { copies ->
                            Text("$copies", color = BoothColors.OnDark, fontSize = 76.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(100.dp), textAlign = TextAlign.Center)
                        }
                        RoundStepper("+", state.copies < state.maxCopies) { onCopies(state.copies + 1) }
                    }
                    // Only promise a download when this event shares online.
                    val sharing = state.event?.enableDigitalQRShare == true
                    val hint = when {
                        state.copies == 0 -> if (sharing) "Sans impression : photos à télécharger" else "Sans impression"
                        sharing -> "Vos photos seront aussi à télécharger"
                        else -> null
                    }
                    hint?.let { Text(it, color = BoothColors.Muted, fontSize = 17.sp, textAlign = TextAlign.Center) }
                }

                val printing = state.copies > 0
                BoothButton(
                    if (printing) "Imprimer" else "Terminer", onConfirm, Modifier.enter(280).fillMaxWidth(),
                    icon = if (printing) PrintIcon else Icons.Rounded.Check, enabled = !state.isRendering, fontSize = 32, minHeight = 100.dp,
                )
            }
        }
        if (!confirmAbort) CancelSessionButton { confirmAbort = true }
        if (confirmAbort) AbortConfirmOverlay(onConfirm = onCancel, onDismiss = { confirmAbort = false })
    }
}

/** A disabled stepper (counter at its limit) is simply dimmed; no limit text is shown. */
@Composable
private fun RoundStepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).graphicsLayer { alpha = if (enabled) 1f else 0.3f }.pressable(enabled, pressedScale = 0.85f, onClick = onClick)
            .glass(CircleShape, BoothColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BoothColors.OnDark, fontSize = 40.sp, fontWeight = FontWeight.Medium)
    }
}

/** QR code scaled to whatever room the column has left, with its caption underneath. */
@Composable
private fun QrBlock(share: ShareUi, modifier: Modifier, caption: String) {
    val pop = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = (minOf(maxWidth, maxHeight - 48.dp)).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                share.qr.asImageBitmap(), "QR code de téléchargement",
                Modifier.size(side).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .clip(RoundedCornerShape(32.dp)).background(BoothColors.Paper).padding(18.dp),
                // The code is drawn black on white; multiplying by the paper colour dims its white to match.
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(BoothColors.Paper, androidx.compose.ui.graphics.BlendMode.Multiply),
            )
            Text(caption, color = BoothColors.Muted, fontSize = 18.sp, maxLines = 1)
        }
    }
}

/** Thin bar that empties as the return to the welcome screen approaches. */
@Composable
private fun ReturnTimer(secondsLeft: Int, total: Int = 15) {
    val fraction by animateFloatAsState((secondsLeft - 1).coerceAtLeast(0) / total.toFloat(), tween(1_000, easing = LinearEasing), label = "timer")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(320.dp).height(6.dp).clip(CircleShape).background(BoothColors.OnDark.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(BoothColors.AccentBrush))
        }
        Text("Retour à l'accueil dans $secondsLeft s", color = BoothColors.Muted, fontSize = 20.sp)
    }
}

@Composable
fun ThanksScreen(state: BoothUiState, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxSize().safeDrawingPadding().padding(56.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GradientText("Merci !", 120.sp, Modifier.enter(0))
            Column(Modifier.enter(140), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isRendering) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        CircularProgressIndicator(Modifier.size(40.dp), color = BoothColors.Accent, strokeWidth = 4.dp)
                        Text("Préparation de votre photo…", color = BoothColors.OnDark, fontSize = 30.sp)
                    }
                } else {
                    val plural = if (state.copies > 1) "s" else ""
                    val (title, detail) = when {
                        state.copies == 0 -> "Vos photos sont prêtes" to
                            if (state.share != null || state.shareLoading) "Scannez le QR code pour les télécharger." else null
                        state.printWaiting -> "Impression en attente" to
                            "L'imprimante est momentanément indisponible, votre photo sera imprimée dès que possible."
                        else -> "Impression de ${state.copies} copie$plural en cours" to "Récupérez votre photo à la sortie de l'imprimante."
                    }
                    Text(title, color = BoothColors.OnDark, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    detail?.let { Text(it, color = BoothColors.Muted, fontSize = 24.sp, lineHeight = 32.sp) }
                }
            }
            Column(Modifier.enter(280).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ReturnTimer(state.thanksSecondsLeft)
                BoothButton("Terminer", onDone, style = BoothButtonStyle.GLASS, fontSize = 24, minHeight = 72.dp)
            }
        }
        if (state.share != null || state.shareLoading || state.shareFailed) {
            Column(
                Modifier.weight(0.9f).fillMaxHeight().enter(200).glass(RoundedCornerShape(44.dp)).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Récupérez vos photos", color = BoothColors.OnDark, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                AnimatedContent(
                    targetState = when { state.share != null -> 0; state.shareLoading -> 1; else -> 2 },
                    transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.92f)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center, label = "share",
                ) { which ->
                    when (which) {
                        0 -> state.share?.let { QrBlock(it, Modifier.fillMaxSize(), "Scannez avec votre téléphone · lien permanent") }
                        1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                CircularProgressIndicator(Modifier.size(56.dp), color = BoothColors.Accent, strokeWidth = 5.dp)
                                Text("Envoi de vos photos…", color = BoothColors.Muted, fontSize = 22.sp)
                            }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Lien de téléchargement indisponible pour le moment.", color = BoothColors.Muted, fontSize = 22.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
