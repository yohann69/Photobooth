package com.photobooth.feature.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private const val MAX_PIN_LENGTH = 8
private const val REQUIRED_TAPS = 5
private const val TAP_WINDOW_MS = 2_000L

/** Invisible touch target: five quick taps or one long press asks for the admin PIN. */
@Composable
fun AdminHotspot(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    Box(
        modifier
            .size(140.dp)
            .testTag("admin_hotspot")
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onTriggered() },
                    onTap = {
                        val now = System.currentTimeMillis()
                        taps = if (now - lastTap > TAP_WINDOW_MS) 1 else taps + 1
                        lastTap = now
                        if (taps >= REQUIRED_TAPS) {
                            taps = 0
                            onTriggered()
                        }
                    },
                )
            },
    )
}

@Composable
fun PinPadDialog(
    error: String?,
    attempt: Int,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember(attempt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin PIN") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(pin.length) { Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
                }
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.height(20.dp))
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { digit -> KeypadButton(digit.toString()) { if (pin.length < MAX_PIN_LENGTH) pin += digit } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeypadButton("⌫", outlined = true) { pin = pin.dropLast(1) }
                    KeypadButton("0") { if (pin.length < MAX_PIN_LENGTH) pin += "0" }
                    KeypadButton("OK") { if (pin.length >= 4) onSubmit(pin) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KeypadButton(label: String, outlined: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.width(84.dp).height(64.dp).padding(0.dp).testTag("key_$label")
    if (outlined) OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, style = MaterialTheme.typography.titleLarge) }
    else Button(onClick = onClick, modifier = modifier) { Text(label, style = MaterialTheme.typography.titleLarge) }
}
