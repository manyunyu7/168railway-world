// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Canvas
import android.graphics.Paint
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The passages on the map, as the web draws them (renderer.ts bridges and connectors, T-217,
 * T-313, T-1859; data from passages.rs `drawn` in `Engine.geometry`):
 *  - zoomed in (station boxes, z14+) a thin bridge of the boxes' own white with their rim along
 *    both sides, between the nearest points of two platforms, for each of the player's passages
 *    and for each complex whose boxes do not touch;
 *  - zoomed out (dots, z9–14) a connector of the dots' white and rim between the dots of each
 *    group the complex distances make, and for each of the player's passages.
 * Drawn in two passes round the stations, so bridge, box and dot read as one white shape with one
 * outline: [under] (the rim) before the stations, [over] (the white) after them.
 */
internal class PassageLayer {
    private var bridges = FloatArray(0)
    private var connectors = FloatArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }

    fun load(data: JSONObject) {
        val p = data.optJSONObject("passages")
        fun floats(name: String) = p?.optJSONArray(name)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: FloatArray(0)
        bridges = floats("bridges")
        connectors = floats("connectors")
    }

    val empty get() = bridges.isEmpty() && connectors.isEmpty()

    /** Box mode: the half width of a box as StudioStationGlyph draws it (6 m on the ground, or its floor on screen). */
    private fun boxHalf(scale: Float, density: Float, mercator: Float) = max(6f, max(3.2f * density / scale, 3.2f * 2.38866f / mercator))

    fun under(c: Canvas, cx: Float, cy: Float, scale: Float, w: Int, h: Int, density: Float, mercator: Float, zoom: Float, dot: Float) =
        draw(c, cx, cy, scale, w, h, density, mercator, zoom, dot, true)

    fun over(c: Canvas, cx: Float, cy: Float, scale: Float, w: Int, h: Int, density: Float, mercator: Float, zoom: Float, dot: Float) =
        draw(c, cx, cy, scale, w, h, density, mercator, zoom, dot, false)

    /** [dot]: the outer radius of a station dot on screen, px (the rim is its outer 40 %). */
    private fun draw(c: Canvas, cx: Float, cy: Float, scale: Float, w: Int, h: Int, density: Float, mercator: Float,
                     zoom: Float, dot: Float, rim: Boolean) {
        if (zoom < 9f || empty) return
        val boxes = zoom >= 14f
        val seg = if (boxes) bridges else connectors
        if (seg.isEmpty()) return
        val half: Float; val ring: Float
        if (boxes) {
            // web: the bridge's white is 1.5 px either side at z15 against a box's 2.5 (BRIDGE_HALF_PX)
            val box = boxHalf(scale, density, mercator)
            ring = min(box * .5f, 1.25f * density / scale)
            half = box * .6f
        } else {
            // web connectorHalfPx: 0.3 of a dot's white either side, with the dot's rim along it
            ring = dot * .4f / scale
            half = .3f * dot * .6f / scale
        }
        paint.strokeWidth = if (rim) 2 * (half + ring) else 2 * half
        paint.color = if (rim) (if (boxes) 0xff374b50.toInt() else 0xff26474b.toInt())
            else (if (boxes) 0xfffffcf0.toInt() else 0xfffff9e7.toInt())
        // only what is on screen (plane units)
        val rx = w / 2f / scale + half + ring; val ry = h / 2f / scale + half + ring
        c.save()
        c.translate(w / 2f, h / 2f); c.scale(scale, -scale); c.translate(-cx, -cy)
        var i = 0
        while (i + 3 < seg.size) {
            val x0 = seg[i]; val y0 = seg[i + 1]; val x1 = seg[i + 2]; val y1 = seg[i + 3]
            i += 4
            if (max(x0, x1) < cx - rx || min(x0, x1) > cx + rx || max(y0, y1) < cy - ry || min(y0, y1) > cy + ry) continue
            c.drawLine(x0, y0, x1, y1, paint)
        }
        c.restore()
    }
}
