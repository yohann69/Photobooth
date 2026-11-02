package com.photobooth.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photobooth.domain.model.PhotoAspect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventsTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Events", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onIntent(AdminIntent.RefreshEvents) }) { Text("Refresh counts") }
                PrimaryAction("New event") { creating = true }
            }
        }
        state.activeEvent?.let { active ->
            item { ActiveEventSettings(active, onIntent) }
            item { ThemeEditorCard(active, onIntent) }
            item { FilterTogglesCard(active, onIntent) }
        }
        items(state.events, key = { it.event.id }) { row -> EventCard(row, onSwitch = { onIntent(AdminIntent.SwitchEvent(row.event.id)) }) }
    }

    if (creating) {
        EventCreatorDialog(
            onCreate = {
                creating = false
                onIntent(AdminIntent.CreateEvent(it))
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun ActiveEventSettings(event: com.photobooth.data.db.entity.EventEntity, onIntent: (AdminIntent) -> Unit) {
    SectionCard("Settings for \"${event.name}\"") {
        Stepper("Countdown", event.countdownSeconds, 1..15, unit = " s", onChange = { v -> onIntent(AdminIntent.UpdateActiveEvent { it.copy(countdownSeconds = v) }) })
        Stepper("Max prints per session", event.maxPrintsPerSession, 1..20, onChange = { v -> onIntent(AdminIntent.UpdateActiveEvent { it.copy(maxPrintsPerSession = v) }) })
        SwitchRow("Allow retakes", checked = event.allowRetakes) { v -> onIntent(AdminIntent.UpdateActiveEvent { it.copy(allowRetakes = v) }) }
        SwitchRow("Digital QR share", "Offline sharing page reachable by QR code", event.enableDigitalQRShare) { v ->
            onIntent(AdminIntent.UpdateActiveEvent { it.copy(enableDigitalQRShare = v) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Photo ratio", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoAspect.entries.forEach { a ->
                    FilterChip(selected = event.photoAspect == a, onClick = { onIntent(AdminIntent.SetPhotoAspect(a)) }, label = { Text(a.label) })
                }
            }
            Text(
                "Match your camera so photos are never cropped: tablet camera 4:3, Canon in photo mode 3:2, USB webcam mode / HDMI 16:9. " +
                    "Changing it rebuilds every layout's slots, so frames must be checked again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow("Mirror live preview", checked = event.mirrorLivePreview) { v -> onIntent(AdminIntent.UpdateActiveEvent { it.copy(mirrorLivePreview = v) }) }
    }
}

@Composable
private fun EventCard(row: EventRow, onSwitch: () -> Unit) {
    val active = row.event.isActive
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.event.name, style = MaterialTheme.typography.titleLarge)
                    if (active) AssistChip(onClick = {}, label = { Text("ACTIVE") })
                }
                Text(
                    "Created ${SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(row.event.createdAt))}  |  " +
                        "${row.photoCount} photos  |  ${row.printCount} prints",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer { Text(row.folderPath, style = MaterialTheme.typography.bodySmall) }
            }
            if (!active) PrimaryAction("Make active", onClick = onSwitch)
        }
    }
}

@Composable
fun EventCreatorDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val folder = name.trim().ifEmpty { "Event" }.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), "_")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Event name") },
                    placeholder = { Text("Mariage Julie & Thomas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Folder: Pictures/Photobooth/<EventId>_$folder/", style = MaterialTheme.typography.bodyMedium)
                Text("Subfolders: Originals/, Composited_Prints/, Frames/, Theme/, Sessions/", style = MaterialTheme.typography.bodySmall)
                Text("A new event starts with clean default layouts: no frame or design from another event.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create & activate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
