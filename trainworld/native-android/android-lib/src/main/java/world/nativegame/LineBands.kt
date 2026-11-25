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
        LineLook.load(data)
        routes.clear(); order.clear(); edgeClass.clear(); layout = emptyList(); lineKey = Float.NaN; trafficKey = ""
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

    /**
     * A line's stroke as short paths of at most [CHUNK] points, each point at least `tol` from the one
     * before: the map draws its network on the UI thread in software, where stroking a whole intercity
     * route (thousands of points, mostly off screen) took over a second for the 199 lines of Seluruh
     * Indonesia (ANR while panning). Off-screen pieces are now skipped by the canvas' bounds check and a
     * zoomed-out line keeps only the points a pixel or so apart. Opaque round-capped pieces meet seamlessly.
     */
    private class Pieces {
        val paths = ArrayList<Path>()
        private var count = 0
        private var lastX = Float.NaN; private var lastY = Float.NaN
        fun add(xy: FloatArray, from: Int, to: Int, tol: Float) {
            var path = Path().also { paths.add(it) }
            count = 0
            for (j in from..to) {
                val x = xy[j*2]; val y = xy[j*2+1]
                if (j == from) { path.moveTo(x, y); count = 1; lastX = x; lastY = y; continue }
                // keep the run's last point always, so a piece ends where the next begins
                if (j < to && abs(x - lastX) + abs(y - lastY) < tol) continue
                path.lineTo(x, y); count++; lastX = x; lastY = y
                if (count >= CHUNK && j < to) { path = Path().also { paths.add(it) }; path.moveTo(x, y); count = 1 }
            }
        }
        fun draw(c: Canvas, paint: Paint) { for (p in paths) c.drawPath(p, paint) }
    }

    // ---- the Line view
    /** per track class (`RailLayer.LINE_*`): the casing, the line on its own and in a band */
    private class Drawn(val id: Int, val color: Int, val whole: HashMap<Int, Pieces>, val solo: HashMap<Int, Pieces>, val banded: HashMap<Int, Pieces>)
    private val drawn = mutableListOf<Drawn>()
    private var lineKey = Float.NaN

    /** Rebuilt only when the line width moves by more than about 3%: the shifts are line widths. */
    private fun near(key: Float, width: Float) = !key.isNaN() && abs(ln(width / key)) < .03f

    private fun lineGeometry(width: Float) {
        if (near(lineKey, width)) return
        lineKey = width
        drawn.clear()
        if (layout.size != routes.size) {
            slotIndex.clear()
            for ((edge, slots) in order) slots.forEachIndexed { s, ids -> for (id in ids) slotIndex.putIfAbsent((edge.toLong() shl 32) or (id.toLong() and 0xffffffffL), s) }
            layout = routes.map(::layoutOf)
        }
        for ((r, l) in routes.zip(layout)) {
            val n = r.xy.size / 2
            // the bands' shifts are line widths: the offsets for a width of 1, scaled
            val xy = FloatArray(r.xy.size) { r.xy[it] + l.unit[it] * width }
            val whole = HashMap<Int, Pieces>(); val solo = HashMap<Int, Pieces>(); val banded = HashMap<Int, Pieces>()
            // a quarter of a line's width: about 1,5 px zoomed out, far below what shows close in
            val tol = width / 4f
            var i = 0
            while (i < l.runs.size) {
                val j = l.runs[i]; val k = l.runs[i+1]; val c = l.runs[i+2]; val b = l.runs[i+3] == 1
                (if (b) banded else solo).getOrPut(c) { Pieces() }.add(xy, j, k, tol)
                whole.getOrPut(c) { Pieces() }.add(xy, j, k, tol)
                i += 4
            }
            if (n < 2) continue
            drawn.add(Drawn(r.id, r.color, whole, solo, banded))
        }
    }

    /** What of a route's drawing does not depend on the width, worked out once per load. */
    private class Layout(val unit: FloatArray, val runs: IntArray)
    private var layout: List<Layout> = emptyList()
    /** per shared edge and line (edge shl 32 or id): the line's slot there */
    private val slotIndex = HashMap<Long, Int>()
    private fun layoutOf(r: Route): Layout {
        val n = r.xy.size / 2
        val shift = FloatArray(n)
        val shared = BooleanArray(n)
        if (r.at.isNotEmpty()) {
            // line `r.id`'s slot per edge and the edge's band count, asked once per run of points on one edge
            var lastEdge = Int.MIN_VALUE; var ab = 0f; var has = false
            for (j in 0 until n) {
                val edge = r.at[j] shr 1
                if (edge != lastEdge) {
                    lastEdge = edge
                    val s = slotIndex[(edge.toLong() shl 32) or (r.id.toLong() and 0xffffffffL)]
                    has = s != null
                    if (s != null) ab = BAND_SHARE * ((order[edge]!!.size - 1) / 2f - s)
                }
                if (!has) continue
                shift[j] = if (r.at[j] and 1 == 0) ab else -ab
                shared[j] = true
            }
        }
        val moved = shifted(r.xy, shift)
        val unit = FloatArray(r.xy.size) { moved[it] - r.xy[it] }
        // a segment is a band's where either end is on a shared edge; its width by the track under it (web T-3136)
        fun cls(j: Int) = if (r.at.isEmpty()) RailLayer.LINE_SINGLE else edgeClass[r.at[j+1] shr 1] ?: RailLayer.LINE_SINGLE
        val runs = ArrayList<Int>()
        var j = 0
        while (j < n - 1) {
            val b = shared[j] || shared[j+1]
            val c = cls(j)
            var k = j + 1
            while (k < n - 1 && (shared[k] || shared[k+1]) == b && cls(k) == c) k++
            runs.add(j); runs.add(k); runs.add(c); runs.add(if (b) 1 else 0)
            j = k
        }
        return Layout(unit, runs.toIntArray())
    }

    /** The Line view's lines, casing first (`NetworkView` line mode). `px`: one screen pixel in world units. */
    fun drawLines(c: Canvas, paint: Paint, width: Float, casing: Float, px: Float) {
        lineGeometry(width)
        paint.color = 0xfffaf6e9.toInt()
        // lines hidden on the map (LineVisibility.kt) leave their band empty, as the web's alpha 0
        val off = LineVisibility.off
        for (d in drawn) if (d.id !in off) for ((k, p) in d.whole) { paint.strokeWidth = width * RailLayer.lineShare(k) + casing; p.draw(c, paint) }
        for (d in drawn) {
            if (d.id in off) continue
            paint.color = LineLook.tint(d.id) ?: d.color
            for ((k, p) in d.solo) { paint.strokeWidth = max(px, width * RailLayer.lineShare(k)); p.draw(c, paint) }
            for ((k, p) in d.banded) { paint.strokeWidth = max(px, width * RailLayer.lineShare(k) * BAND_SHARE - BAND_GAP_PX * px); p.draw(c, paint) }
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
        /** points per piece of a drawn line ([Pieces]) */
        const val CHUNK = 64
    }
}
