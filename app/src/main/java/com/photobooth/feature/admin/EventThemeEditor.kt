package com.photobooth.feature.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.domain.model.PhotoFilterType
import com.photobooth.feature.booth.BoothColors
import com.photobooth.feature.booth.EventBackdrop
import com.photobooth.feature.booth.EventTheme
import com.photobooth.feature.booth.parseHexColor
import java.io.File

private val HEX_6 = Regex("#[0-9a-fA-F]{6}")

private class ThemePreset(val name: String, val primary: String, val secondary: String, val background: String, val text: String)

private val PRESETS = listOf(
    ThemePreset("Neon", "#FF2E7E", "#00E5FF", "#07070D", "#FFFFFF"),
    ThemePreset("Mariage", "#C9A24B", "#8E6B3E", "#FBF6EC", "#3A2D1F"),
    ThemePreset("Corporate", "#1E6BFF", "#00B8A9", "#0B1B33", "#FFFFFF"),
    ThemePreset("Fête", "#FF6A00", "#FFD400", "#1A0B2E", "#FFFFFF"),
)

/** Live art-direction editor: edits are saved as soon as a colour is a valid #RRGGBB and the preview follows. */
@Composable
fun ThemeEditorCard(event: EventEntity, onIntent: (AdminIntent) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) onIntent(AdminIntent.SetEventBackgroundImage(uri))
    }
    SectionCard("Art direction for \"${event.name}\"") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Presets", style = MaterialTheme.typography.bodyMedium)
                    PRESETS.forEach { p ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onIntent(
                                    AdminIntent.UpdateActiveEvent {
                                        it.copy(
                                            primaryColorHex = p.primary, secondaryColorHex = p.secondary,
                                            backgroundColorHex = p.background, textColorHex = p.text,
                                        )
                                    },
                                )
                            },
                            label = { Text(p.name) },
                        )
                    }
                }
                HexColorField("Primary (buttons, borders, countdown)", event.primaryColorHex) { v ->
                    onIntent(AdminIntent.UpdateActiveEvent { it.copy(primaryColorHex = v) })
                }
                HexColorField("Secondary (accents, badges)", event.secondaryColorHex) { v ->
                    onIntent(AdminIntent.UpdateActiveEvent { it.copy(secondaryColorHex = v) })
                }
                HexColorField("Background (solid fallback)", event.backgroundColorHex) { v ->
                    onIntent(AdminIntent.UpdateActiveEvent { it.copy(backgroundColorHex = v) })
                }
                HexColorField("Text & icons", event.textColorHex) { v ->
                    onIntent(AdminIntent.UpdateActiveEvent { it.copy(textColorHex = v) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryAction("Background image…") { picker.launch("image/*") }
                    if (event.customBackgroundImagePath != null) {
                        TextButton(onClick = { onIntent(AdminIntent.RemoveEventBackgroundImage) }) { Text("Remove") }
                    }
                    Text(
                        event.customBackgroundImagePath?.let { File(it).name } ?: "No image: solid background colour",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live preview", style = MaterialTheme.typography.labelLarge)
                GuestPreview(event)
            }
        }
    }
}

@Composable
private fun HexColorField(label: String, saved: String, onValid: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    val valid = HEX_6.matches(text.trim())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(parseHexColor(if (valid) text else saved, saved))
                .border(1.dp, Color.Gray, RoundedCornerShape(10.dp)),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                val candidate = input.trim()
                if (HEX_6.matches(candidate) && !candidate.equals(saved, ignoreCase = true)) onValid(candidate.uppercase())
            },
            label = { Text(label) },
            isError = !valid,
            supportingText = if (valid) null else ({ Text("Use #RRGGBB") }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Miniature attract screen drawn with the event's palette, so the admin sees what guests will see. */
@Composable
private fun GuestPreview(event: EventEntity) {
    EventTheme(event) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))) {
            EventBackdrop(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BIENVENUE", color = BoothColors.AccentAlt, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text(event.name, color = BoothColors.OnDark, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "3",
                        color = BoothColors.OnAccent, fontSize = 18.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.background(BoothColors.Accent, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    Text(
                        "Badge",
                        color = BoothColors.OnDark,
                        fontSize = 12.sp,
                        modifier = Modifier.border(2.dp, BoothColors.AccentAlt, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Text(
                    "TOUCHER POUR COMMENCER",
                    color = BoothColors.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(BoothColors.Accent, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Per-event filter toggles; the guest filter bar is hidden when nothing but ORIGINAL (or nothing) is enabled. */
@Composable
fun FilterTogglesCard(event: EventEntity, onIntent: (AdminIntent) -> Unit) {
    fun set(filters: List<PhotoFilterType>) = onIntent(AdminIntent.UpdateActiveEvent { it.copy(enabledFilters = filters) })
    val enabled = event.enabledFilters
    SectionCard("Photo filters for \"${event.name}\"") {
        PhotoFilterType.entries.forEach { filter ->
            SwitchRow(
                label = filter.label,
                description = if (filter == PhotoFilterType.ORIGINAL) "Unfiltered photo" else null,
                checked = filter in enabled,
            ) { on -> set(PhotoFilterType.entries.filter { (it in enabled && it != filter) || (it == filter && on) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { set(PhotoFilterType.ALL) }) { Text("Enable all") }
            OutlinedButton(onClick = { set(emptyList()) }) { Text("Disable all filters") }
        }
        val hidden = enabled.none { it != PhotoFilterType.ORIGINAL }
        Text(
            if (hidden) "The filter bar is hidden on the guest final screen." else "Guests can choose between: ${enabled.joinToString { it.label }}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
