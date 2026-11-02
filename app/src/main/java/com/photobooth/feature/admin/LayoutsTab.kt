package com.photobooth.feature.admin

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.domain.model.Orientation
import com.photobooth.domain.model.PhotoAspect
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

typealias PreviewRenderer = suspend (EventLayoutConfigEntity, Int, Boolean) -> Bitmap
typealias FrameSizeReader = suspend (String) -> Pair<Int, Int>?

@Composable
fun LayoutsTab(
    state: AdminUiState,
    onIntent: (AdminIntent) -> Unit,
    render: PreviewRenderer,
    frameSize: FrameSizeReader,
) {
    var selected by remember { mutableStateOf<LayoutType?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Compositions", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Every layout fills one 10×15 cm sheet. Frames and designs belong to this event only: " +
                            (state.events.firstOrNull { it.event.id == state.activeEvent?.id }?.folderPath?.let { "$it/Frames" } ?: "the event's Frames folder") + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(state.layouts, key = { it.layoutType.name }) { cfg ->
            LayoutCard(cfg, onIntent, render, onConfigure = { selected = cfg.layoutType })
        }
    }

    val current = state.layouts.firstOrNull { it.layoutType == selected }
    if (current != null) {
        FrameConfiguratorDialog(current, onIntent, render, frameSize, onClose = { selected = null })
    }
}

private fun orientationLabel(o: Orientation) = if (o == Orientation.PORTRAIT) "Portrait 10×15" else "Landscape 15×10"

/** Orientation, canvas, slot shape and the share of the sheet kept free for logos. */
private fun layoutSummary(cfg: EventLayoutConfigEntity): String {
    val aspect = cfg.photoSlots.firstOrNull()?.aspectRatio ?: 1f
    val shape = if (aspect < 1.1f) "1:1" else PhotoAspect.entries.minBy { kotlin.math.abs(it.ratio - aspect) }.label
    val zone = (DefaultLayoutPresets.forType(cfg.layoutType).safeZoneFraction * 100).roundToInt()
    return "${orientationLabel(cfg.orientation)} | ${cfg.canvasWidthPx}×${cfg.canvasHeightPx}px | ${cfg.photoCount} photos ($shape) | logo zone $zone%"
}

@Composable
private fun LayoutCard(
    cfg: EventLayoutConfigEntity,
    onIntent: (AdminIntent) -> Unit,
    render: PreviewRenderer,
    onConfigure: () -> Unit,
) {
    val density = LocalDensity.current.density
    val ratio = cfg.canvasWidthPx.toFloat() / cfg.canvasHeightPx
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cfg.layoutType.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = cfg.isEnabled,
                    onCheckedChange = { onIntent(AdminIntent.ToggleLayout(cfg.layoutType, it)) },
                )
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LayoutPreview(
                    config = cfg,
                    renderWidthPx = (240 * density * ratio).roundToInt(),
                    showGuides = false,
                    render = render,
                    modifier = Modifier.height(240.dp).aspectRatio(ratio).clickable(onClick = onConfigure),
                )
            }
            Text(layoutSummary(cfg), style = MaterialTheme.typography.bodySmall)
            Text(
                if (cfg.customFramePngPath != null) "Frame: ${File(cfg.customFramePngPath).name}" else "No custom frame",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("Frame & calibration") }
        }
    }
}

/** Composite preview (dummy photos + background + PNG frame), re-rendered off the main thread whenever [config] changes. */
@Composable
fun LayoutPreview(
    config: EventLayoutConfigEntity,
    renderWidthPx: Int,
    showGuides: Boolean,
    render: PreviewRenderer,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, config, renderWidthPx, showGuides) {
        delay(60)
        value = render(config, renderWidthPx, showGuides)
    }
    Box(modifier.background(Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = "${config.layoutType.name} preview", modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(28.dp))
    }
}

private val BACKGROUND_SWATCHES = listOf("#FFFFFF", "#000000", "#F5E6D3", "#1E296E", "#FCE4EC", "#E8F5E9")

@Composable
fun FrameConfiguratorDialog(
    cfg: EventLayoutConfigEntity,
    onIntent: (AdminIntent) -> Unit,
    render: PreviewRenderer,
    frameSize: FrameSizeReader,
    onClose: () -> Unit,
) {
    var showGuides by remember { mutableStateOf(true) }
    var padding by remember(cfg.slotPaddingPx) { mutableIntStateOf(cfg.slotPaddingPx) }
    var offsetX by remember(cfg.slotOffsetXPx) { mutableIntStateOf(cfg.slotOffsetXPx) }
    var offsetY by remember(cfg.slotOffsetYPx) { mutableIntStateOf(cfg.slotOffsetYPx) }
    val draft = cfg.copy(slotPaddingPx = padding, slotOffsetXPx = offsetX, slotOffsetYPx = offsetY)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) onIntent(AdminIntent.ImportFrame(cfg.layoutType, uri))
    }
    val frameDimensions by produceState<Pair<Int, Int>?>(null, cfg.customFramePngPath) {
        value = cfg.customFramePngPath?.let { frameSize(it) }
    }
    val ratio = cfg.canvasWidthPx.toFloat() / cfg.canvasHeightPx

    fun commit() = onIntent(AdminIntent.SetCalibration(cfg.layoutType, padding, offsetX, offsetY))

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(0.96f), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    LayoutPreview(
                        config = draft,
                        renderWidthPx = if (ratio < 1f) 900 else 1200,
                        showGuides = showGuides,
                        render = render,
                        modifier = Modifier.aspectRatio(ratio),
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.layoutType.name, style = MaterialTheme.typography.titleLarge)
                            Text(layoutSummary(cfg), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                    SwitchRow("Enabled for guests", checked = cfg.isEnabled) { onIntent(AdminIntent.ToggleLayout(cfg.layoutType, it)) }

                    SectionCard("Custom frame overlay") {
                        val path = cfg.customFramePngPath
                        Text(
                            "Stored in this event's Frames folder; never shared with other events. Keep logos inside the dashed logo zone of the test frame.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (path == null) "No frame: photos print on the background only."
                            else "${File(path).name}" + (frameDimensions?.let { " (${it.first}×${it.second})" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        frameDimensions?.let { (w, h) ->
                            if (w != cfg.canvasWidthPx || h != cfg.canvasHeightPx) {
                                Text(
                                    "Expected ${cfg.canvasWidthPx}×${cfg.canvasHeightPx}px; the frame will be stretched to fit.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryAction("Pick PNG…") { picker.launch("image/png") }
                            OutlinedButton(onClick = { onIntent(AdminIntent.GenerateTestFrame(cfg.layoutType)) }) { Text("Generate test frame") }
                            if (path != null) TextButton(onClick = { onIntent(AdminIntent.RemoveFrame(cfg.layoutType)) }) { Text("Remove") }
                        }
                    }

                    SectionCard("Slot calibration") {
                        SwitchRow("Show slot guides", "Pink outlines mark where photos land", showGuides) { showGuides = it }
                        CalibrationRow("Padding (inset)", padding, -40..120, { padding = it }, ::commit)
                        CalibrationRow("Shift X", offsetX, -120..120, { offsetX = it }, ::commit)
                        CalibrationRow("Shift Y", offsetY, -120..120, { offsetY = it }, ::commit)
                        OutlinedButton(onClick = {
                            padding = 0
                            offsetX = 0
                            offsetY = 0
                            onIntent(AdminIntent.SetCalibration(cfg.layoutType, 0, 0, 0))
                        }) { Text("Reset calibration") }
                    }

                    SectionCard("Background colour") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BACKGROUND_SWATCHES.forEach { hex ->
                                val selected = cfg.backgroundColorHex.equals(hex, ignoreCase = true)
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                        .border(
                                            BorderStroke(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray),
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable { onIntent(AdminIntent.SetBackgroundColor(cfg.layoutType, hex)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, onFinished: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$value px", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
