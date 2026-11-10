package com.photobooth.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Second admin panel: settings shared by every event (upload server, printer, display, PIN). */
@Composable
fun AppSettingsScreen(state: AdminUiState, onIntent: (AdminIntent) -> Unit, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onIntent(AdminIntent.DismissMessage)
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = Modifier.fillMaxSize()) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App settings", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "These apply to every event.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onBack) { Text("Back") }
            }
            HorizontalDivider()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                UploadServerCard(state, onIntent)
                PrinterCard(state, onIntent)
                KioskCard(state, onIntent)
                PinCard(onIntent)
            }
        }
    }
}

/** Where photos are uploaded for the QR-code download link; shared by every event. */
@Composable
private fun UploadServerCard(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    val s = state.settings
    var url by remember(s.uploadBaseUrl) { mutableStateOf(s.uploadBaseUrl) }
    var token by remember(s.uploadToken) { mutableStateOf(s.uploadToken) }
    SectionCard("Online gallery server") {
        Text(
            "Each photo is uploaded 2.5 s after it is taken, removed again if the guest retakes it or cancels, and the final montage " +
                "returns the download link shown in the QR code. Turn it on per event in Event settings.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("https://example.com") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            token, { token = it }, label = { Text("Bearer token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction("Save upload server") { onIntent(AdminIntent.SaveUpload(url, token)) }
    }
}

@Composable
private fun PrinterCard(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SectionCard("Printer: DNP DS-RX1HS (USB)") {
        DnpUsbPanel(state, onIntent)
        SwitchRow("Dry-run mode", "Simulate printing: jobs complete without sending anything to the printer.", state.settings.printDryRun) {
            onIntent(AdminIntent.SetDryRun(it))
        }
    }
}

@Composable
private fun KioskCard(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SectionCard("Kiosk & display") {
        SwitchRow(
            "Kiosk mode (lock task)",
            "Pins the app with startLockTask(); guests cannot leave it. Android may ask to confirm screen pinning.",
            state.settings.kioskModeEnabled,
        ) { onIntent(AdminIntent.SetKioskMode(it)) }
        SwitchRow("Keep screen awake", "Prevents the display from sleeping while the app is open.", state.settings.keepScreenOn) {
            onIntent(AdminIntent.SetKeepScreenOn(it))
        }
        SwitchRow("Hide system bars", "Immersive mode; swipe from an edge to reveal them briefly.", state.settings.hideSystemBars) {
            onIntent(AdminIntent.SetHideSystemBars(it))
        }
    }
}

/** Live readout of the DNP DS-RX1HS plus the raster orientation controls and test sheets. */
@Composable
private fun DnpUsbPanel(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    val s = state.settings
    val link = state.dnp
    val t = link.telemetry
    Text(
        when {
            t != null -> "${t.statusText} | paper ${t.mediaName ?: "?"}: " +
                (t.remaining?.let { "$it" + (t.capacity?.let { c -> " / $c" } ?: "") + " prints left" } ?: "count not reported") +
                (t.firmware?.let { " | FW $it" } ?: "")
            else -> link.problem ?: "Reading printer status…"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "If a print comes out mirrored or upside down, change these and print the test sheets again.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = s.dnpFlipVertical, label = { Text("Flip vertically") },
            onClick = { onIntent(AdminIntent.SetDnpOrientation(!s.dnpFlipVertical, s.dnpMirror, s.dnpPortraitClockwise)) },
        )
        FilterChip(
            selected = s.dnpMirror, label = { Text("Mirror") },
            onClick = { onIntent(AdminIntent.SetDnpOrientation(s.dnpFlipVertical, !s.dnpMirror, s.dnpPortraitClockwise)) },
        )
        FilterChip(
            selected = s.dnpPortraitClockwise, label = { Text("Portrait: rotate clockwise") },
            onClick = { onIntent(AdminIntent.SetDnpOrientation(s.dnpFlipVertical, s.dnpMirror, !s.dnpPortraitClockwise)) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryAction("Print landscape test sheet") { onIntent(AdminIntent.PrintTestSheet(portrait = false)) }
        PrimaryAction("Print portrait test sheet") { onIntent(AdminIntent.PrintTestSheet(portrait = true)) }
    }
}

@Composable
private fun PinCard(onIntent: (AdminIntent) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = new.length in 4..8 && new == confirm && current.isNotEmpty()
    val numeric = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

    SectionCard("Admin PIN") {
        Text("The PIN is 4 to 8 digits. The factory default is 0000.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(current, { current = it.filter(Char::isDigit).take(8) }, label = { Text("Current PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = numeric, modifier = Modifier.weight(1f))
            OutlinedTextField(new, { new = it.filter(Char::isDigit).take(8) }, label = { Text("New PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = numeric, modifier = Modifier.weight(1f))
            OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("Confirm") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = numeric, modifier = Modifier.weight(1f))
        }
        PrimaryAction("Change PIN", enabled = valid) {
            onIntent(AdminIntent.ChangePin(current, new))
            current = ""
            new = ""
            confirm = ""
        }
    }
}
