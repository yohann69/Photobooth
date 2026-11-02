package com.photobooth.feature.booth

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.photobooth.core.PhotoboothLog.TAG

/**
 * Logs where the guest screens put their zones and whether any two of them intersect, so the "nothing in
 * front of the preview" rule can be read from logcat on the device.
 */
object UiAudit {
    private val zones = LinkedHashMap<String, Rect>()
    private val lastLine = HashMap<String, String>()

    /** Pairs that are meant to nest (a child drawn inside its parent zone) and are skipped by the overlap test. */
    private val nested = setOf(
        "capture/preview" to "capture/stage", "capture/countdown" to "capture/controls",
        "final/preview" to "final/stage", "final/qr" to "final/controls",
    )

    @Synchronized
    fun record(screen: String, name: String, bounds: Rect) {
        zones["$screen/$name"] = bounds
        val mine = zones.filterKeys { it.startsWith("$screen/") }
        val overlaps = buildList {
            val keys = mine.keys.toList()
            for (i in keys.indices) for (j in i + 1 until keys.size) {
                val a = keys[i]
                val b = keys[j]
                if ((a to b) in nested || (b to a) in nested) continue
                if (mine.getValue(a).intersect(mine.getValue(b)).let { it.width > 1f && it.height > 1f }) add("${a.substringAfter('/')}x${b.substringAfter('/')}")
            }
        }
        val line = "screen=$screen overlaps=${overlaps.size}${if (overlaps.isEmpty()) "" else overlaps} " +
            mine.entries.joinToString(" ") { (k, r) -> "${k.substringAfter('/')}=[${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()}]" }
        if (lastLine[screen] != line) {
            lastLine[screen] = line
            Log.i(TAG, "UiAudit: $line")
        }
    }

    @Synchronized
    fun clear(screen: String) {
        zones.keys.removeAll { it.startsWith("$screen/") }
        lastLine.remove(screen)
    }
}

fun Modifier.auditZone(screen: String, name: String): Modifier =
    onGloballyPositioned { UiAudit.record(screen, name, it.boundsInRoot()) }
