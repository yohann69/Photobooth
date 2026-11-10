package com.photobooth.domain.layout

import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.PhotoAspect
import com.photobooth.feature.booth.frenchTitle
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Exports the geometry of every layout, for every photo ratio, from the same presets the booth prints with, so the
 * designers' templates (tools/layout-templates) can never drift from the real slots. Runs only when
 * LAYOUT_TEMPLATES_OUT names the output file; see tools/layout-templates/README.md.
 *
 * One line per layout, tab separated: ratio, type, title, canvas width, canvas height, title zone "x,y,w,h", then
 * the photo zones "x,y,w,h,shot" separated by ';' (canvas pixels, shot numbered from 1).
 */
class LayoutTemplateExport {

    @Test
    fun export() {
        val out = System.getenv("LAYOUT_TEMPLATES_OUT")
        assumeTrue("LAYOUT_TEMPLATES_OUT not set", !out.isNullOrBlank())
        val lines = PhotoAspect.entries.flatMap { aspect ->
            LayoutType.entries.map { type ->
                val p = DefaultLayoutPresets.forType(type, aspect)
                val z = p.safeZone
                val slots = p.slots.mapIndexed { i, s ->
                    listOf(
                        (s.x * p.canvasWidthPx).roundToInt(), (s.y * p.canvasHeightPx).roundToInt(),
                        (s.width * p.canvasWidthPx).roundToInt(), (s.height * p.canvasHeightPx).roundToInt(),
                        (s.sourceIndex ?: i) + 1,
                    ).joinToString(",")
                }
                listOf(
                    aspect.label, type.name, type.frenchTitle(), p.canvasWidthPx, p.canvasHeightPx,
                    "${z.x},${z.y},${z.width},${z.height}", slots.joinToString(";"),
                ).joinToString("\t")
            }
        }
        File(out).apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }
}
