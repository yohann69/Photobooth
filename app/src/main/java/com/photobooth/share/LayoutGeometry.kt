package com.photobooth.share

import com.photobooth.domain.layout.DefaultLayoutPresets
import com.photobooth.domain.model.LayoutType
import com.photobooth.domain.model.PhotoAspect
import com.photobooth.domain.model.PhotoSlot
import com.photobooth.feature.booth.frenchTitle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A layout's geometry in canvas pixels as the Transat server takes it: the photo zones (with the shot shown in
 * each), the zone kept free for the title and the canvas size. The frame upload page and the admin panel draw their
 * templates from it.
 */
object LayoutGeometry {
    fun json(type: LayoutType, width: Int, height: Int, slots: List<PhotoSlot>, aspect: PhotoAspect): JSONObject {
        val zone = DefaultLayoutPresets.forType(type, aspect).safeZone
        val photos = JSONArray()
        slots.forEachIndexed { i, s ->
            // Calibration may push a slot past the edge: only what lands on the sheet matters.
            val x = (s.x * width).roundToInt().coerceIn(0, width - 1)
            val y = (s.y * height).roundToInt().coerceIn(0, height - 1)
            val right = ((s.x + s.width) * width).roundToInt().coerceIn(x + 1, width)
            val bottom = ((s.y + s.height) * height).roundToInt().coerceIn(y + 1, height)
            photos.put(JSONObject().put("x", x).put("y", y).put("w", right - x).put("h", bottom - y).put("shot", (s.sourceIndex ?: i) + 1))
        }
        return JSONObject()
            .put("type", type.name)
            .put("title", type.frenchTitle())
            .put("width", width)
            .put("height", height)
            .put("title_zone", JSONObject().put("x", zone.x).put("y", zone.y).put("w", zone.width).put("h", zone.height))
            .put("photos", photos)
    }

    /** Every layout of every photo ratio, uncalibrated, in the app's order: what the booth publishes. */
    fun catalog(): JSONObject {
        val aspects = JSONObject()
        PhotoAspect.entries.forEach { aspect ->
            aspects.put(
                aspect.name,
                JSONArray(
                    DefaultLayoutPresets.all(aspect).map { p ->
                        json(p.type, p.canvasWidthPx, p.canvasHeightPx, p.slots, aspect).put("enabled_by_default", p.type.enabledByDefault)
                    },
                ),
            )
        }
        return JSONObject().put("aspects", aspects)
    }
}
