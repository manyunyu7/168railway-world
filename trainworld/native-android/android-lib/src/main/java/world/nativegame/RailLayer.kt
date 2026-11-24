package world.nativegame

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.max

/**
 * The track under the lines, drawn the web's way (`map/renderer.ts` T-658, T-3136, T-2926/7, T-817,
 * T-322): zoomed out one stroke per edge, single track thinner and double thicker; closer in double
 * track grows to its pair's width and parts into its two tracks, each 2.4 m wide with their centres
 * 4 m apart (quad track is two such pairs side by side, so four), with a crossover's diagonals as one
 * track joining them. Tram track is narrower with its two wires, high-speed track has its centre line,
 * blueprint track a light blue middle between dark blue rails. Close in the height shows as well: a
 * viaduct's deck with its edges under raised track, tunnel track faint and dashed (a ramp switches
 * where it leaves the ground, `heights` from `rail_detail.rs`). On the Line view the lines over
 * double track part with it ([drawLines]); zoomed out `LineBands` draws them, as wide as the track.
 *
 * Everything is drawn into `NetworkView`'s static bitmap; the paths are built at [load] and the
 * parted ones again only when the parting moves (between about zoom 16.7 and 17.2).
 */
internal class RailLayer(private val density: Float) {
    /** One bucket of runs drawn alike: blueprint or built, -1 tunnel 0 ground 1 raised, kind group (0 rail,
     *  1 high-speed, 2 tram), double, a crossover's diagonal. */
    private data class Key(val built: Boolean, val level: Int, val kind: Int, val double: Boolean, val crossover: Boolean)
    private class Run(val xy: FloatArray, val normal: FloatArray, val from: Int, val to: Int)

    private val buckets = LinkedHashMap<Key, MutableList<Run>>()
    private val centre = HashMap<Key, Path>()
    private val lanes = Shifted { it.double }
    private val wires = Shifted { it.kind == 2 }

    // ---- the lines (Line view) over the track
    private class LineRun(val cls: Int, val from: Int, val to: Int)
    private class Route(val color: Int, val xy: FloatArray, val normal: FloatArray, val runs: List<LineRun>) {
        val centre = HashMap<Int, Path>()
        var d = Float.NaN
        val plus = Path(); val minus = Path()
    }
    private val routes = mutableListOf<Route>()

    fun load(data: JSONObject) {
        buckets.clear(); centre.clear(); lanes.clear(); wires.clear(); routes.clear()
        val share = HashMap<Int, Int>()
        val tracks = data.optJSONArray("tracks")
        for (i in 0 until (tracks?.length() ?: 0)) {
            val t = tracks!!.getJSONObject(i)
            val p = t.getJSONArray("points")
            if (p.length() < 4) continue
            val xy = FloatArray(p.length()) { p.getDouble(it).toFloat() }
            val crossover = t.optBoolean("crossover")
            val kind = when (t.optInt("kind")) { 1 -> 1; 2, 3 -> 2; else -> 0 }
            val double = t.optInt("tracks", 1) >= 2 && !crossover
            share[t.getInt("id")] = lineClass(t)
            val built = t.optBoolean("built", true)
            val level = t.optInt("level")
            val h = t.optJSONArray("heights")
            val normal = mitres(xy)
            val n = xy.size / 2
            // each segment's height: below ground, at grade or raised, by its ends' heights on a ramp
            fun cls(j: Int): Int {
                if (!built) return 0
                val z = if (h != null && h.length() == n) (h.getInt(j) + h.getInt(j + 1)) / 20f else level.toFloat()
                return if (z <= -.5f) -1 else if (z >= .5f) 1 else 0
            }
            var j = 0
            while (j < n - 1) {
                val c = cls(j)
                var k = j + 1
                while (k < n - 1 && cls(k) == c) k++
                buckets.getOrPut(Key(built, c, kind, double, crossover)) { mutableListOf() }.add(Run(xy, normal, j, k))
                j = k
            }
        }
        // deepest first, crossovers over the pairs they join
        val sorted = buckets.entries.sortedWith(compareBy({ it.key.level }, { it.key.crossover }))
        buckets.clear(); for ((k, v) in sorted) buckets[k] = v
        for ((k, runs) in buckets) centre[k] = Path().also { for (r in runs) add(it, r.xy, r.normal, r.from, r.to, 0f) }

        val list = data.optJSONArray("routes") ?: return
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val p = r.getJSONArray("points")
            if (p.length() < 4) continue
            val xy = FloatArray(p.length()) { p.getDouble(it).toFloat() }
            val at = r.optJSONArray("at")
            val n = xy.size / 2
            fun cls(j: Int) = if (at == null || at.length() != n) LINE_SINGLE else share[at.getInt(j + 1) shr 1] ?: LINE_SINGLE
            val runs = mutableListOf<LineRun>()
            var j = 0
            while (j < n - 1) {
                val c = cls(j)
                var k = j + 1
                while (k < n - 1 && cls(k) == c) k++
                runs.add(LineRun(c, j, k)); j = k
            }
            val route = Route(r.getInt("color") or Color.BLACK, xy, mitres(xy), runs)
            for (run in runs) add(route.centre.getOrPut(run.cls) { Path() }, xy, route.normal, run.from, run.to, 0f)
            routes.add(route)
        }
    }

    /** How far double track has opened at this zoom (web `laneState`), by 4 m on screen in dp. */
    private class Lanes(val unit: Float, val grow: Float, val t: Float, val share: Float) {
        /** 4 m in world units, a track's width and how far each is off the centre line */
        val gap get() = 4f * unit
        val track get() = share * gap
        val off get() = gap / 2f * t
    }
    private fun lanes(scale: Float, centerY: Float): Lanes {
        val unit = cosh(centerY / 6371008.8f) // plane units per ground metre (1 off the Mercator frame)
        val gap = 4f * unit * scale / density
        fun ramp(a: Float, b: Float) = ((gap - a) / (b - a)).coerceIn(0f, 1f)
        return Lanes(unit, ramp(LANE_GROW_DP, LANE_PART_DP), ramp(LANE_PART_DP, LANE_APART_DP), LANE_SHARE * (1 - LANE_NARROW * ramp(LANE_APART_DP, LANE_NARROW_DP)))
    }

    /** Built track. `w` a line's width in world units (`NetworkView`'s route width). */
    fun drawTrack(c: Canvas, paint: Paint, scale: Float, centerY: Float, w: Float) {
        val px = 1f / scale
        val l = lanes(scale, centerY)
        val parted = l.t > 0f
        if (parted) lanes.at(l.off)
        for ((k, path) in centre) {
            if (!k.built) continue
            val one = width(k, l, w, px)
            val pair = if (k.double && parted) l.gap + l.track else one
            val ink = when { k.kind == 2 -> TRAM; k.level < 0 && l.grow == 0f -> TUNNEL; k.level > 0 && l.grow == 0f -> RAISED; else -> TRACK }
            // the structure under it close in: a viaduct's deck and edges, a tunnel's faint bore
            if (l.grow > 0f && k.level != 0 && !k.crossover) {
                paint.pathEffect = null
                if (k.level > 0) {
                    paint.color = RAISED; paint.strokeWidth = pair + 2 * max(1.2f * density * px, .5f * l.unit); c.drawPath(path, paint)
                    paint.color = DECK; paint.strokeWidth = pair + max(px, .3f * l.unit); c.drawPath(path, paint)
                } else {
                    paint.color = BORE; paint.strokeWidth = pair + 2 * max(px, .5f * l.unit); c.drawPath(path, paint)
                }
            }
            val tunnel = k.level < 0
            paint.pathEffect = if (tunnel && l.grow > 0f) DashPathEffect(floatArrayOf(7 * density * px, 4 * density * px), 0f) else null
            val colour = if (tunnel) faint(ink) else ink
            if (k.double && parted) {
                // the pale band between the two tracks, then each track
                if (!tunnel) { paint.color = mix(ink, Color.WHITE, .6f * l.t); paint.strokeWidth = pair; c.drawPath(path, paint) }
                paint.color = colour; paint.strokeWidth = max(px, l.track)
                c.drawPath(lanes.plus[k]!!, paint); c.drawPath(lanes.minus[k]!!, paint)
            } else {
                paint.color = colour; paint.strokeWidth = one; c.drawPath(path, paint)
            }
            paint.pathEffect = null
            val stroke = if (k.double && parted) max(px, l.track) else one
            if (k.kind == 1 && !tunnel && stroke >= HSR_STRIPE_DP * density * px) {
                paint.color = Color.WHITE; paint.strokeWidth = max(.3f * px, .18f * stroke)
                if (k.double && parted) { c.drawPath(lanes.plus[k]!!, paint); c.drawPath(lanes.minus[k]!!, paint) } else c.drawPath(path, paint)
            }
            if (k.kind == 2 && stroke >= WIRE_FROM_DP * density * px) {
                paint.color = Color.WHITE; paint.strokeWidth = max(.7f * px, WIRE_SHARE * stroke)
                if (k.double && parted) { c.drawPath(lanes.plus[k]!!, paint); c.drawPath(lanes.minus[k]!!, paint) }
                else { wires.at(WIRE_OFF * stroke); c.drawPath(wires.plus[k]!!, paint); c.drawPath(wires.minus[k]!!, paint) }
            }
        }
        paint.pathEffect = null
    }

    /** Blueprint track (web T-322): a light blue middle between two dark blue rails, parting as built track does. */
    fun drawBlueprint(c: Canvas, paint: Paint, scale: Float, centerY: Float, w: Float) {
        val px = 1f / scale
        val l = lanes(scale, centerY)
        val parted = l.t > 0f
        if (parted) lanes.at(l.off)
        val rail = max(px, BP_RAIL_SHARE * w)
        for ((k, path) in centre) {
            if (k.built) continue
            val split = k.double && parted
            val full = if (split) max(px, l.track) else max(BP_NARROW * (width(k, l, w, px) + 2 * rail), 2 * rail + px)
            for (shade in 0..1) {
                paint.color = if (shade == 0) BP_RAIL else BP_MID
                paint.strokeWidth = if (shade == 0) full else full - 2 * rail
                if (paint.strokeWidth <= 0f) continue
                if (split) { c.drawPath(lanes.plus[k]!!, paint); c.drawPath(lanes.minus[k]!!, paint) } else c.drawPath(path, paint)
            }
        }
    }

    /** The one stroke's width for bucket `k` before its tracks part (web STROKE_VS `tk`, its growth to the pair). */
    private fun width(k: Key, l: Lanes, w: Float, px: Float): Float {
        if (k.kind == 2) return max(px, TRAM_WIDTH * w)
        if (!k.double) return max(max(px, w * (SINGLE_WIDTH + (1 - SINGLE_WIDTH) * l.grow)), l.track * l.t)
        val doubled = max(DOUBLE_FLOOR_DP * density * px * (1 - l.grow), w * DOUBLE_WIDTH)
        return doubled + l.grow * max(0f, l.gap + l.track - doubled)
    }

    /**
     * The Line view's lines once double track starts to open (web lane modes 1-3): over double track a
     * line grows to the pair's width, then is drawn on each of its two tracks over a band of its colour
     * paled towards the casing. False while it is still one stroke: `LineBands` draws the lines then.
     */
    fun drawLines(c: Canvas, paint: Paint, scale: Float, centerY: Float, w: Float, casing: Float): Boolean {
        val l = lanes(scale, centerY)
        if (l.grow <= 0f || routes.isEmpty()) return false
        val px = 1f / scale
        val parted = l.t > 0f
        val pair = l.gap + l.track
        fun lineWidth(cls: Int) = when (cls) {
            LINE_TRAM -> TRAM_WIDTH * w
            LINE_DOUBLE -> if (parted) pair else { val d = w * DOUBLE_WIDTH; d + l.grow * max(0f, pair - d) }
            else -> max(w * (SINGLE_WIDTH + (1 - SINGLE_WIDTH) * l.grow), l.track * l.t)
        }
        paint.pathEffect = null
        paint.color = CASING
        for (r in routes) for ((cls, path) in r.centre) { paint.strokeWidth = lineWidth(cls) + casing; c.drawPath(path, paint) }
        for (r in routes) {
            if (parted && !(abs(l.off - r.d) <= l.off * .02f)) {
                r.d = l.off; r.plus.reset(); r.minus.reset()
                for (run in r.runs) if (run.cls == LINE_DOUBLE) { add(r.plus, r.xy, r.normal, run.from, run.to, l.off); add(r.minus, r.xy, r.normal, run.from, run.to, -l.off) }
            }
            for ((cls, path) in r.centre) {
                paint.strokeWidth = lineWidth(cls)
                paint.color = if (cls == LINE_DOUBLE && parted) mix(r.color, CASING, .6f * l.t) else r.color
                c.drawPath(path, paint)
            }
            if (parted && LINE_DOUBLE in r.centre) {
                paint.color = r.color; paint.strokeWidth = max(px, l.track)
                c.drawPath(r.plus, paint); c.drawPath(r.minus, paint)
            }
        }
        return true
    }

    /** Paths of the buckets matching [pick], shifted sideways by ±d, rebuilt only when d moves. */
    private inner class Shifted(val pick: (Key) -> Boolean) {
        var d = Float.NaN
        val plus = HashMap<Key, Path>(); val minus = HashMap<Key, Path>()
        fun clear() { d = Float.NaN; plus.clear(); minus.clear() }
        fun at(to: Float) {
            if (abs(to - d) <= abs(to) * .02f) return
            d = to
            for ((k, runs) in buckets) {
                if (!pick(k)) continue
                val p = plus.getOrPut(k) { Path() }.apply { reset() }
                val m = minus.getOrPut(k) { Path() }.apply { reset() }
                for (r in runs) { add(p, r.xy, r.normal, r.from, r.to, to); add(m, r.xy, r.normal, r.from, r.to, -to) }
            }
        }
    }

    companion object {
        /** web LANE_M and its px steps (renderer.ts), here dp: the stroke grows, the tracks part, they are apart */
        private const val LANE_GROW_DP = 4f; private const val LANE_PART_DP = 7f; private const val LANE_APART_DP = 10f
        private const val LANE_NARROW = .2f; private const val LANE_NARROW_DP = 16f
        private const val LANE_SHARE = .6f
        /** web rendererShaders: single and double track's share of a line's width, tram track's */
        const val SINGLE_WIDTH = .71f; const val DOUBLE_WIDTH = 1.24f; const val TRAM_WIDTH = .75f
        private const val DOUBLE_FLOOR_DP = 2f
        private const val WIRE_OFF = .17f; private const val WIRE_SHARE = .11f; private const val WIRE_FROM_DP = 4f
        private const val HSR_STRIPE_DP = 4f
        private const val BP_RAIL_SHARE = .16f; private const val BP_NARROW = .75f
        private val BP_MID = 0xffa9cdf3.toInt(); private val BP_RAIL = 0xff2f62b3.toInt()
        private val TRACK = 0xff485c64.toInt(); private val TRAM = 0xffa66c39.toInt()
        private val RAISED = 0xff366d74.toInt(); private val DECK = 0xffe0e9df.toInt()
        private val TUNNEL = 0xff82768b.toInt(); private val BORE = 0x3382768b
        private val CASING = 0xfffaf6e9.toInt()
        /** a route segment's track for the lines' width: single (crossovers too), double, tram */
        const val LINE_SINGLE = 0; const val LINE_DOUBLE = 1; const val LINE_TRAM = 2

        /** A track row's (`geometry.tracks[]`) class for the lines over it. */
        fun lineClass(t: JSONObject) = when {
            t.optInt("kind") >= 2 -> LINE_TRAM
            t.optInt("tracks", 1) >= 2 && !t.optBoolean("crossover") -> LINE_DOUBLE
            else -> LINE_SINGLE
        }
        /** A line's width share over track of class `cls` zoomed out (web STROKE_VS `tk`), for `LineBands`. */
        fun lineShare(cls: Int) = when (cls) { LINE_DOUBLE -> DOUBLE_WIDTH; LINE_TRAM -> TRAM_WIDTH; else -> SINGLE_WIDTH }

        private fun faint(c: Int) = (c and 0xffffff) or (0x99 shl 24)
        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((a shr s and 255) * (1 - f) + (b shr s and 255) * f).toInt() shl s
            return (a and (0xff shl 24)) or ch(16) or ch(8) or ch(0)
        }

        /** Per point the unit normal to its left, mitred at corners and capped so a hairpin does not throw it far
         *  (`LineBands.shifted`'s rule): the point shifted by d is xy + normal * d. */
        fun mitres(xy: FloatArray): FloatArray {
            val n = xy.size / 2
            val out = FloatArray(xy.size)
            for (j in 0 until n) {
                var nx = 0f; var ny = 0f
                if (j > 0) { val dx = xy[j*2]-xy[j*2-2]; val dy = xy[j*2+1]-xy[j*2-1]; val l = hypot(dx, dy); if (l > 0) { nx -= dy/l; ny += dx/l } }
                val ox = nx; val oy = ny
                var tx = 0f; var ty = 0f
                if (j < n-1) { val dx = xy[j*2+2]-xy[j*2]; val dy = xy[j*2+3]-xy[j*2+1]; val l = hypot(dx, dy); if (l > 0) { tx = -dy/l; ty = dx/l; nx += tx; ny += ty } }
                val l = hypot(nx, ny)
                if (l <= 0) continue
                nx /= l; ny /= l
                val d = if (tx != 0f || ty != 0f) nx*tx+ny*ty else nx*ox+ny*oy
                val k = 1f / max(d, .5f)
                out[j*2] = nx * k; out[j*2+1] = ny * k
            }
            return out
        }

        private fun add(into: Path, xy: FloatArray, normal: FloatArray, from: Int, to: Int, d: Float) {
            for (j in from..to) {
                val x = xy[j*2] + normal[j*2] * d; val y = xy[j*2+1] + normal[j*2+1] * d
                if (j == from) into.moveTo(x, y) else into.lineTo(x, y)
            }
        }
    }
}
