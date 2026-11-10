package com.photobooth.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photobooth.data.db.entity.PrintJobStatus
import com.photobooth.diagnostics.HardwareBadge
import com.photobooth.print.PrintStatus
import java.io.File

@Composable
fun DashboardTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionCard("Hardware diagnostics") {
            val d = state.diagnostics
            if (d == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Probing hardware…")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BadgeCard(d.camera, Modifier.weight(1f))
                    BadgeCard(d.printer, Modifier.weight(1f))
                    BadgeCard(d.flash, Modifier.weight(1f))
                }
                PaperCounter(d.paperRemaining, d.paperCapacity, onReplaceRoll = { onIntent(AdminIntent.ReplaceRoll) })
            }
        }

        SectionCard("Print queue") {
            PrintQueue(state.printStatus, onIntent)
        }

        SectionCard("Test shot & test print") {
            Text(
                "Takes a real photo with the active camera, saves it to the event folder and composes a test print " +
                    "with the first enabled layout and its frame.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val test = state.selfTest
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction("Test shot & test print", enabled = test !is SelfTestState.Running && state.activeEvent != null) {
                    onIntent(AdminIntent.RunSelfTest)
                }
                if (test is SelfTestState.Done) OutlinedButton(onClick = { onIntent(AdminIntent.DismissSelfTest) }) { Text("Clear") }
            }
            when (test) {
                SelfTestState.Idle -> Unit
                is SelfTestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(test.step)
                }
                is SelfTestState.Done -> SelfTestResultView(test.result)
            }
        }
    }
}

@Composable
private fun PrintQueue(status: PrintStatus, onIntent: (AdminIntent) -> Unit) {
    var confirmAll by remember { mutableStateOf(false) }
    if (status.jobs.isEmpty()) {
        Text("No print waiting.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        "${status.pendingJobs} job(s), ${status.pendingSheets} sheet(s) waiting" + (status.message?.let { " - $it" } ?: ""),
        style = MaterialTheme.typography.titleMedium,
    )
    status.jobs.forEach { job ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(model = File(job.imagePath), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(96.dp).width(96.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (job.status == PrintJobStatus.PRINTING) "Printing" else "Waiting") +
                        ": ${job.copiesRemaining} of ${job.copies} sheet(s) left",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    job.lastError ?: File(job.imagePath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onIntent(AdminIntent.CancelPrintJob(job.id)) }) { Text("Cancel") }
        }
    }
    OutlinedButton(onClick = { confirmAll = true }) { Text("Cancel all") }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Cancel all waiting prints?") },
            text = { Text("The printer is told to stop and none of these ${status.pendingSheets} sheet(s) will be printed.") },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; onIntent(AdminIntent.CancelAllPrintJobs) }) { Text("Cancel all prints") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BadgeCard(badge: HardwareBadge, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthDot(badge.health)
                Text(badge.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(badge.status, style = MaterialTheme.typography.titleLarge)
            Text(badge.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaperCounter(remaining: Int, capacity: Int, onReplaceRoll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Paper roll: $remaining of $capacity prints remaining", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { if (capacity == 0) 0f else remaining.toFloat() / capacity }, modifier = Modifier.fillMaxWidth().height(10.dp))
        OutlinedButton(onClick = onReplaceRoll) { Text("New roll installed") }
    }
}

@Composable
private fun SelfTestResultView(result: SelfTestResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            result.message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text("Printer: ${result.printerStatus}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOfNotNull(result.shotPath, result.printPath).forEach { path ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(220.dp))
                    Text(File(path).name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
