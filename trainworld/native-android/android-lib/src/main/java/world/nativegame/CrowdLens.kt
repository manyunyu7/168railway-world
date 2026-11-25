// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The map's crowding marks, after the web's two "Tampilan peta" switches:
 *  - "Kereta padat" (`fullMarks`, map/renderer.ts `setFull`): in the Traffic view, track at
 *    capacity at the demand level in force (`fullTrack`) and the crowded stretches, those whose
 *    busiest hour runs past the rated load (Crowding.kt), get a red edge, stronger as the load passes it.
 *  - "Rel penuh kapasitas" (`capacity`, map/capacityMarkers.ts): a gauge dot at each spot of track or
 *    station 75% full and more, yellow to red, its needle at the utilisation.
 * The tags and full track come from the editor world (src/crowding.rs `marks`), asked again with
 * the Issues list (each game hour and after an edit).
 */
internal object CrowdLens {
    @JvmStatic private external fun marks(handle: Long, level: Int): String
    /** per tag x, y, utilisation, held (1: holds trains back), sorted worst first */
    private var tags = emptyList<DoubleArray>()
    private var full = emptySet<Int>()
    /** whether the Traffic view draws the crowding marks this frame (the `fullMarks` switch) */
    private var on = false

    fun refresh(handle: Long, level: Int) {
        val m = if (handle == 0L) null else runCatching { JSONObject(marks(handle, level)) }.getOrNull()
        val t = m?.optJSONArray("tags")
        tags = (0 until (t?.length() ?: 0)).map { i -> t!!.getJSONArray(i).let { a -> DoubleArray(4) { a.optDouble(it) } } }
            .sortedWith(compareByDescending<DoubleArray> { it[3] }.thenByDescending { it[2] })
        val f = m?.optJSONArray("full")
        full = (0 until (f?.length() ?: 0)).map { f!!.getInt(it) }.toSet()
    }

    /** Web `fullAttr`'s red edge strength from a load over the rated load (rendererShaders `FULL_FLOOR`): stronger past it. */
    private fun alpha(load: Double) = (100 + 155 * ((load - 1) / .4).coerceIn(0.0, 1.0)).toInt()

    /** Before the Traffic view's lines: the red under track at capacity, when [shown]. [path]: a piece's path by edge. */
    fun under(c: Canvas, paint: Paint, width: Float, shown: Boolean, path: (Int) -> Path?) {
        on = shown
        if (!shown || full.isEmpty()) return
        paint.style = Paint.Style.STROKE
        paint.color = 0xffd23c3c.toInt()
        paint.strokeWidth = width * 2.4f
        for (e in full) path(e)?.let { c.drawPath(it, paint) }
    }

    /** Before a Traffic span of line `line`'s stretch `k` of stroke [width]: its red edge when crowded. */
    fun outline(c: Canvas, paint: Paint, line: Int, k: Int, width: Float, path: Path) {
        if (!on || k < 0) return
        val s = Crowding.crowded(line, k) ?: return
        val keep = paint.color
        paint.color = (alpha(s.getDouble(2)) shl 24) or 0xd23c3c
        paint.strokeWidth = width * 1.6f
        c.drawPath(path, paint)
        paint.color = keep
        paint.strokeWidth = width
    }

    private val ramp = arrayOf(.75 to intArrayOf(232, 180, 37), .9 to intArrayOf(229, 122, 36), 1.0 to intArrayOf(204, 58, 44))
    /** Web capacityMarkers `dotColour`: yellow at 75%, orange at 90%, red at 100% and over. */
    private fun dotColour(rho: Double, over: Boolean): Int {
        fun rgb(c: IntArray) = (0xff shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
        if (over || rho >= 1) return rgb(ramp[2].second)
        if (rho <= ramp[0].first) return rgb(ramp[0].second)
        val i = if (rho < ramp[1].first) 1 else 2
        val (r0, a) = ramp[i - 1]; val (r1, b) = ramp[i]; val t = (rho - r0) / (r1 - r0)
        return rgb(IntArray(3) { k -> Math.round(a[k] + (b[k] - a[k]) * t).toInt() })
    }

    private val arc = RectF()
    /** The capacity tags in screen pixels ([sx], [sy]: map to screen), worst kept where they crowd each other (web `place`). */
    fun drawTags(c: Canvas, paint: Paint, density: Float, width: Int, height: Int, sx: (Float) -> Float, sy: (Float) -> Float) {
        if (tags.isEmpty()) return
        val u = density // the web dot's viewBox unit: one CSS px
        val placed = ArrayList<FloatArray>()
        val room = 16 * u + 4 * u
        for (m in tags) {
            val x = sx(m[0].toFloat()); val y = sy(m[1].toFloat())
            if (x < -10 * u || y < -10 * u || x > width + 10 * u || y > height + 10 * u) continue
            if (placed.any { kotlin.math.abs(it[0] - x) < room && kotlin.math.abs(it[1] - y) < room }) continue
            placed.add(floatArrayOf(x, y))
            val held = m[3] >= 1; val rho = m[2]
            paint.style = Paint.Style.FILL; paint.color = 0xffffffff.toInt()
            c.drawCircle(x, y, 8 * u, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * u; paint.color = dotColour(rho, held)
            c.drawCircle(x, y, 6.2f * u, paint)
            paint.strokeWidth = u; paint.strokeCap = Paint.Cap.ROUND; paint.color = 0x4d262626
            arc.set(x - 3.4f * u, y - 3.4f * u, x + 3.4f * u, y + 3.4f * u)
            c.drawArc(arc, 135f, 270f, false, paint)
            val a = Math.toRadians(-135 + 270 * (if (held) 1.0 else min(1.0, rho)))
            paint.strokeWidth = 1.5f * u; paint.color = 0xff262626.toInt()
            c.drawLine(x, y, x + (3.4f * u * sin(a)).toFloat(), y - (3.4f * u * cos(a)).toFloat(), paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(x, y, 1.1f * u, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
    }
}
