package com.photobooth.feature.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.material3.OutlinedButton
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
    SectionCard("Transat sharing (Google Drive + QR code)") {
        Text(
            "Each validated photo is sent to the event's Photobooth folder on the Transat Drive a moment later, and the collage " +
                "when the guest prints; the QR code then opens a permanent page on transat.dev with every picture in full quality. " +
                "A cancelled session is removed from the Drive. Turn it on per event in Event settings.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            url, { url = it }, label = { Text("Transat API URL") }, placeholder = { Text("https://api.transat.dev") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            token, { token = it }, label = { Text("Booth key (id.secret)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(AdminIntent.TestUpload(url, token)) }) { Text("Test connection") }
        }
        PrimaryAction("Save sharing settings") { onIntent(AdminIntent.SaveUpload(url, token)) }
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
        HomeAppRow(state.settings.kioskModeEnabled)
        SwitchRow(
            "Kiosk mode",
            "Pins the app so guests cannot leave it (Android may ask once to confirm the pinning), shows it over the lock " +
                "screen, and opens straight on the guest screen of the running event.",
            state.settings.kioskModeEnabled,
        ) { onIntent(AdminIntent.SetKioskMode(it)) }
        SwitchRow("Keep screen on while charging", "On the charger the display never sleeps; on battery it sleeps as usual.", state.settings.keepScreenOn) {
            onIntent(AdminIntent.SetKeepScreenOn(it))
        }
        SwitchRow("Hide system bars", "Immersive mode; swipe from an edge to reveal them briefly.", state.settings.hideSystemBars) {
            onIntent(AdminIntent.SetHideSystemBars(it))
        }
    }
}

/**
 * Whether the booth is the tablet's home app. As home app, Android opens it by itself after every boot (once the
 * tablet is unlocked) and whenever Home is pressed.
 */
@Composable
private fun HomeAppRow(kiosk: Boolean) {
    val context = LocalContext.current
    var isHome by remember { mutableStateOf(isDefaultHome(context)) }
    LifecycleResumeEffect(Unit) {
        isHome = isDefaultHome(context)
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Open at startup", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (isHome) "The booth is the tablet's home app: it opens by itself after a restart and when Home is pressed."
            else "Make the booth the tablet's home app so it opens by itself after a restart. In the list, pick Photobooth.",
            style = MaterialTheme.typography.bodySmall,
            color = if (isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (kiosk) {
            Text(
                "Android settings cannot open while kiosk mode is on: turn kiosk mode off first to change the home app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            enabled = !kiosk,
            onClick = {
                val home = Intent(Settings.ACTION_HOME_SETTINGS)
                runCatching { context.startActivity(home) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            },
        ) { Text(if (isHome) "Change home app" else "Choose home app") }
    }
}

private fun isDefaultHome(context: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
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
