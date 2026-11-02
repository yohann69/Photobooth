package com.photobooth.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AdminScreen(viewModel: AdminViewModel, onExit: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.onIntent(AdminIntent.DismissMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.fillMaxSize(),
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Admin Mode", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.activeEvent?.let { "Active event: ${it.name}" } ?: "No active event",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onExit) { Text("Exit to guest screen") }
            }
            HorizontalDivider()
            val print = state.printStatus
            if (print.needsAttention || print.state == com.photobooth.print.PrintState.DRY_RUN) {
                Text(
                    text = if (print.needsAttention) {
                        "Printing paused: ${print.message ?: "printer unavailable"} — ${print.pendingJobs} job(s), ${print.pendingSheets} sheet(s) waiting; they resume automatically."
                    } else {
                        print.message.orEmpty()
                    },
                    color = if (print.needsAttention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (print.needsAttention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(240.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AdminTab.entries.forEach { tab ->
                        NavigationDrawerItem(
                            label = { Text(tab.title) },
                            selected = state.tab == tab,
                            onClick = { viewModel.onIntent(AdminIntent.SelectTab(tab)) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (state.tab) {
                        AdminTab.DASHBOARD -> DashboardTab(state, viewModel::onIntent)
                        AdminTab.EVENTS -> EventsTab(state, viewModel::onIntent)
                        AdminTab.LAYOUTS -> LayoutsTab(state, viewModel::onIntent, viewModel::renderPreview, viewModel::frameSize)
                        AdminTab.SETTINGS -> SettingsTab(state, viewModel::onIntent)
                    }
                }
            }
        }
    }
}
