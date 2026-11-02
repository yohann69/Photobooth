package com.photobooth.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.photobooth.data.db.entity.PrintFinish
import com.photobooth.data.settings.PrinterConnection
import com.photobooth.data.settings.PrinterModel

@Composable
fun SettingsTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PrinterSettingsCard(state, onIntent)
        PinCard(onIntent)
    }
}

@Composable
private fun PrinterSettingsCard(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    val s = state.settings
    var connection by remember(s.printerConnection) { mutableStateOf(s.printerConnection) }
    var model by remember(s.printerModel) { mutableStateOf(s.printerModel) }
    var ip by remember(s.printerIp) { mutableStateOf(s.printerIp) }
    var port by remember(s.printerPort) { mutableStateOf(s.printerPort.toString()) }
    var capacity by remember(s.rollCapacity) { mutableStateOf(s.rollCapacity.toString()) }
    var ippPort by remember(s.printerIppPort) { mutableStateOf(s.printerIppPort.toString()) }
    var ippPath by remember(s.printerIppPath) { mutableStateOf(s.printerIppPath) }
    var finish by remember(s.printFinish) { mutableStateOf(s.printFinish) }
    var dryRun by remember(s.printDryRun) { mutableStateOf(s.printDryRun) }

    SectionCard("Printer") {
        Text("Model", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrinterModel.entries.forEach { m -> FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m.label) }) }
        }
        Text("Connection", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrinterConnection.entries.forEach { c ->
                FilterChip(selected = connection == c, onClick = { connection = c }, label = { Text(connectionLabel(c)) })
            }
        }
        if (connection == PrinterConnection.NETWORK || connection == PrinterConnection.NETWORK_RAW) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(ip, { ip = it }, label = { Text("Printer IP") }, singleLine = true, modifier = Modifier.weight(2f))
                if (connection == PrinterConnection.NETWORK) {
                    OutlinedTextField(
                        ippPort, { ippPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("IPP port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(ippPath, { ippPath = it }, label = { Text("IPP path") }, singleLine = true, modifier = Modifier.weight(1.5f))
                } else {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Raw port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text("Overcoat finish", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintFinish.entries.forEach { f -> FilterChip(selected = finish == f, onClick = { finish = f }, label = { Text(f.name.lowercase().replaceFirstChar(Char::titlecase)) }) }
        }
        SwitchRow("Dry-run mode", "Simulate printing: jobs complete without sending anything to a printer.", dryRun) { dryRun = it }
        OutlinedTextField(
            capacity, { capacity = it.filter(Char::isDigit).take(6) },
            label = { Text("Roll capacity (prints)") }, singleLine = true,
            supportingText = { Text("Remaining paper = capacity minus prints since the last \"New roll installed\".") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction("Save printer settings") {
            onIntent(
                AdminIntent.SavePrinter(
                    connection = connection,
                    model = model,
                    ip = ip,
                    port = port.toIntOrNull() ?: 9100,
                    rollCapacity = capacity.toIntOrNull() ?: s.rollCapacity,
                    ippPort = ippPort.toIntOrNull() ?: 631,
                    ippPath = ippPath.ifBlank { "/ipp/print" },
                    finish = finish,
                    dryRun = dryRun,
                ),
            )
        }
    }
}

private fun connectionLabel(c: PrinterConnection) = when (c) {
    PrinterConnection.NONE -> "None"
    PrinterConnection.USB -> "USB"
    PrinterConnection.NETWORK -> "Network (IPP)"
    PrinterConnection.NETWORK_RAW -> "Network (raw 9100)"
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
