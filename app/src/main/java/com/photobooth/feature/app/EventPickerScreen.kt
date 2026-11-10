package com.photobooth.feature.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photobooth.core.EventDates
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.feature.admin.EventCreatorDialog

/** First screen of every launch: confirm the running event, switch to another one or start a new one. */
@Composable
fun EventPickerScreen(
    events: List<EventEntity>,
    onPick: (String) -> Unit,
    onCreate: (String, Long) -> Unit,
    onSettings: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Choisir l'évènement", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Les cadres et réglages sont propres à chaque évènement.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onSettings,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Réglages de l'application") }
            Button(onClick = { creating = true }) { Text("Nouvel évènement") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(events, key = { it.id }) { event -> EventRow(event, onClick = { onPick(event.id) }) }
        }
        Text(
            "Un autre évènement que celui en cours, ou un nouvel évènement, demande le code PIN.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (creating) EventCreatorDialog(onCreate = { name, date -> creating = false; onCreate(name, date) }, onDismiss = { creating = false })
}

@Composable
private fun EventRow(event: EventEntity, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (event.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(event.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    EventDates.format(event.eventDate.takeIf { it > 0 } ?: event.createdAt, "EEEE d MMMM yyyy"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (event.isActive) {
                AssistChip(onClick = onClick, label = { Text("En cours") })
                Button(onClick = onClick) { Text("Continuer") }
            }
        }
    }
}
