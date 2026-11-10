package com.photobooth.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Everything that belongs to the running event: behaviour and colours. Layouts and frames have their own tab. */
@Composable
fun EventSettingsTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    val event = state.activeEvent
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (event == null) {
            Text("No event is running. Choose or create one in the Events tab.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Event: ${event.name}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Everything on this page, and the layouts and frames of the next tab, applies to this event only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        ActiveEventSettings(event, onIntent)
        ThemeEditorCard(event, onIntent)
    }
}
