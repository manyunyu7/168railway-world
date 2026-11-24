package world.nativegame

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Lines sharing track drawn side by side (web `map/lineBands.ts`, T-1799). Rust (`line_bands.rs`)
 * orders the bands on every shared edge with the web's pairwise rule and tells each route point the
 * path entry it lies on; here each route is shifted sideways by its slot, in line widths for the
 * Line view (a band [BAND_SHARE] of a line wide, a [BAND_GAP_PX] gap between) and by the bands'
 * own widths for the Traffic view, so a ribbon there is as wide as its lines' riders together.
 */
internal class LineBands {
    private class Route(val id: Int, val color: Int, val xy: FloatArray, val at: IntArray) {
        /** each point's share of the way along the unshifted polyline (the spans' fractions) */
        val along = FloatArray(xy.size / 2).also { f ->
            var s = 0f
            for (j in 1 until f.size) { s += hypot(xy[j*2]-xy[j*2-2], xy[j*2+1]-xy[j*2-1]); f[j] = s }
            if (s > 0) for (j in f.indices) f[j] /= s
        }
    }
    private val routes = mutableListOf<Route>()
    /** per shared edge: its bands left to right in its a to b direction, each the line ids in it */
    private val order = HashMap<Int, List<IntArray>>()

    /** per edge its class for a line's width over it (`RailLayer.lineClass`: single, double or tram track) */
    private val edgeClass = HashMap<Int, Int>()

    fun load(data: JSONObject) {
        routes.clear(); order.clear(); edgeClass.clear(); lineKey = Float.NaN; trafficKey = ""
        data.optJSONArray("tracks")?.let { t -> for (i in 0 until t.length()) t.getJSONObject(i).let { edgeClass[it.getInt("id")] = RailLayer.lineClass(it) } }
        val rows = data.optJSONArray("bands")
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows!!.getJSONArray(i)
            val slots = row.getJSONArray(1)
            order[row.getInt(0)] = List(slots.length()) { s -> slots.getJSONArray(s).let { ids -> IntArray(ids.length()) { ids.getInt(it) } } }
        }
        val list = data.optJSONArray("routes") ?: return
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val points = r.getJSONArray("points")
            val at = r.optJSONArray("at")
            val xy = FloatArray(points.length()) { points.getDouble(it).toFloat() }
            routes.add(Route(r.getInt("id"), r.getInt("color") or android.graphics.Color.BLACK, xy,
                if (at != null && at.length() * 2 == xy.size) IntArray(at.length()) { at.getInt(it) } else IntArray(0)))
        }
    }

    /** the slot of line `id` on point `j`'s edge: (slot, bands) or null where it shares none */
    private fun slotAt(r: Route, j: Int): Pair<Int, List<IntArray>>? {
        if (r.at.isEmpty()) return null
        val slots = order[r.at[j] shr 1] ?: return null
        val s = slots.indexOfFirst { r.id in it }
        return if (s < 0) null else s to slots
    }

    /** the route shifted by `shift[j]` (world units, left of travel) at each point */
    private fun shifted(xy: FloatArray, shift: FloatArray): FloatArray {
        val n = xy.size / 2
        val out = xy.copyOf()
        for (j in 0 until n) {
            if (shift[j] == 0f) continue
            var nx = 0f; var ny = 0f
            if (j > 0) { val dx = xy[j*2]-xy[j*2-2]; val dy = xy[j*2+1]-xy[j*2-1]; val l = hypot(dx,dy); if (l > 0) { nx -= dy/l; ny += dx/l } }
            val ox = nx; val oy = ny
            var tx = 0f; var ty = 0f
            if (j < n-1) { val dx = xy[j*2+2]-xy[j*2]; val dy = xy[j*2+3]-xy[j*2+1]; val l = hypot(dx,dy); if (l > 0) { tx = -dy/l; ty = dx/l; nx += tx; ny += ty } }
            val l = hypot(nx, ny)
            if (l <= 0) continue
            nx /= l; ny /= l
            // a corner's mitre, capped so a hairpin does not throw the band far out
            val d = if (tx != 0f || ty != 0f) nx*tx+ny*ty else nx*ox+ny*oy
            val k = shift[j] / max(d, .5f)
            out[j*2] += nx * k; out[j*2+1] += ny * k
        }
        return out
    }

    private fun path(xy: FloatArray, from: Int = 0, to: Int = xy.size/2 - 1, into: Path = Path()): Path {
        for (j in from..to) if (j == from) into.moveTo(xy[j*2], xy[j*2+1]) else into.lineTo(xy[j*2], xy[j*2+1])
        return into
    }

    // ---- the Line view
    /** per track class (`RailLayer.LINE_*`): the casing, the line on its own and in a band */
    private class Drawn(val color: Int, val whole: HashMap<Int, Path>, val solo: HashMap<Int, Path>, val banded: HashMap<Int, Path>)
    private val drawn = mutableListOf<Drawn>()
    private var lineKey = Float.NaN

    /** Rebuilt only when the line width moves by more than about 3%: the shifts are line widths. */
    private fun near(key: Float, width: Float) = !key.isNaN() && abs(ln(width / key)) < .03f

    private fun lineGeometry(width: Float) {
        if (near(lineKey, width)) return
        lineKey = width
        drawn.clear()
        for (r in routes) {
            val n = r.xy.size / 2
            val shift = FloatArray(n)
            val shared = BooleanArray(n)
            for (j in 0 until n) {
                val (s, slots) = slotAt(r, j) ?: continue
                val ab = BAND_SHARE * ((slots.size - 1) / 2f - s)
                shift[j] = (if (r.at[j] and 1 == 0) ab else -ab) * width
                shared[j] = true
            }
            val xy = shifted(r.xy, shift)
            val whole = HashMap<Int, Path>(); val solo = HashMap<Int, Path>(); val banded = HashMap<Int, Path>()
            // a segment is a band's where either end is on a shared edge; its width by the track under it (web T-3136)
            fun cls(j: Int) = if (r.at.isEmpty()) RailLayer.LINE_SINGLE else edgeClass[r.at[j+1] shr 1] ?: RailLayer.LINE_SINGLE
            var j = 0
            while (j < n - 1) {
                val b = shared[j] || shared[j+1]
                val c = cls(j)
                var k = j + 1
                while (k < n - 1 && (shared[k] || shared[k+1]) == b && cls(k) == c) k++
                path(xy, j, k, (if (b) banded else solo).getOrPut(c) { Path() })
                path(xy, j, k, whole.getOrPut(c) { Path() })
                j = k
            }
            drawn.add(Drawn(r.color, whole, solo, banded))
        }
    }

    /** The Line view's lines, casing first (`NetworkView` line mode). `px`: one screen pixel in world units. */
    fun drawLines(c: Canvas, paint: Paint, width: Float, casing: Float, px: Float) {
        lineGeometry(width)
        paint.color = 0xfffaf6e9.toInt()
        for (d in drawn) for ((k, p) in d.whole) { paint.strokeWidth = width * RailLayer.lineShare(k) + casing; c.drawPath(p, paint) }
        for (d in drawn) {
            paint.color = d.color
            for ((k, p) in d.solo) { paint.strokeWidth = max(px, width * RailLayer.lineShare(k)); c.drawPath(p, paint) }
            for ((k, p) in d.banded) { paint.strokeWidth = max(px, width * RailLayer.lineShare(k) * BAND_SHARE - BAND_GAP_PX * px); c.drawPath(p, paint) }
        }
    }

    // ---- the Traffic view
    private var trafficKey = ""
    private var trafficWidth = Float.NaN
    private val trafficRoutes = HashMap<Int, PathMeasure>()
    private val spanPaths = HashMap<Triple<Int, Float, Float>, Path>()

    /** The Traffic view's span strokes beside each other, for `StudioPassengerOverlay.drawRoutes`. */
    fun traffic(overlay: StudioPassengerOverlay, width: Float): ((Int, Float, Float) -> Path?)? {
        if (order.isEmpty()) return null
        val key = overlay.trafficKey()
        if (key != trafficKey || !near(trafficWidth, width)) {
            trafficKey = key; trafficWidth = width
            trafficRoutes.clear(); spanPaths.clear()
            val shares = overlay.trafficShares()
            // each point's band width, and per edge and line the widest along it
            val pointWidth = HashMap<Int, FloatArray>()
            val edgeWidth = HashMap<Long, Float>()
            for (r in routes) {
                val rows = shares[r.id] ?: continue
                val w = FloatArray(r.along.size)
                var i = 0
                for (j in w.indices) {
                    val f = r.along[j]
                    while (i < rows.size - 1 && rows[i][1] < f) i++
                    val row = rows[i]
                    w[j] = if (f >= row[0] - 1e-4f && f <= row[1] + 1e-4f) overlay.trafficWidth(width, row[2]) else 0f
                    if (r.at.isNotEmpty()) {
                        val k = (r.at[j] shr 1).toLong() shl 32 or (r.id.toLong() and 0xffffffffL)
                        edgeWidth[k] = max(edgeWidth[k] ?: 0f, w[j])
                    }
                }
                pointWidth[r.id] = w
            }
            for (r in routes) {
                if (r.id !in pointWidth) continue
                val n = r.xy.size / 2
                val shift = FloatArray(n)
                for (j in 0 until n) {
                    val (s, slots) = slotAt(r, j) ?: continue
                    val e = (r.at[j] shr 1).toLong() shl 32
                    val widths = slots.map { ids -> ids.maxOf { edgeWidth[e or (it.toLong() and 0xffffffffL)] ?: 0f } }
                    val left = widths.take(s).sum()
                    val ab = widths.sum() / 2 - (left + widths[s] / 2)
                    shift[j] = if (r.at[j] and 1 == 0) ab else -ab
                }
                trafficRoutes[r.id] = PathMeasure(path(shifted(r.xy, shift)), false)
            }
        }
        return { id, f0, f1 ->
            val m = trafficRoutes[id]
            if (m == null) null else spanPaths.getOrPut(Triple(id, f0, f1)) {
                Path().also { m.getSegment(f0 * m.length, f1 * m.length, it, true) }
            }
        }
    }

    companion object {
        /** a band's width as a share of a line's (web `BAND_SHARE`) */
        const val BAND_SHARE = .8f
        /** px of casing between two bands side by side (web `BAND_GAP_PX`) */
        const val BAND_GAP_PX = 1f
    }
}
