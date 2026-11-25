// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Duniaku backdrop: a calm metro map on cream paper (after ppka-wannabe-2's intro).
 * Octilinear lines (H/V/45°) with rounded bends draw themselves in, interchanges sit where lines cross,
 * capsule trains ease in and out of stations and the station they reach pulses once.
 * The network is seeded by the day, so it is stable across reopenings but changes daily.
 * After the reveal the still map is one cached bitmap; a frame only moves the camera and the trains,
 * with nothing allocated. It animates only while resumed and shown, and stands still when the system
 * "remove animations" setting is on.
 */
internal class MetroBackdrop(context: Context) : View(context) {
    private val d = resources.displayMetrics.density

    private class Train(var at: Float, var dir: Int, val top: Float) {
        var wait = 0f
        var since = 0f
        var alpha = 0f
    }

    private class Line(
        val color: Int, val path: Path, val length: Float, val delay: Float,
        val samples: FloatArray, val corners: FloatArray,
    ) {
        val measure = PathMeasure(path, false)
        val reveal = Path()
        var stops = FloatArray(0)
        var stopIds = IntArray(0)
        val trains = ArrayList<Train>(2)
    }

    private class Station(val x: Float, val y: Float, val transit: Boolean, val color: Int) {
        val owners = IntArray(2) { -1 }
        val at = FloatArray(2)
        var label: String? = null
        var lx = 0f
        var ly = 0f
    }

    private val lines = ArrayList<Line>()
    private val stations = ArrayList<Station>()
    private var pulse = FloatArray(0)
    private var pulseColor = IntArray(0)
    private val grown = FloatArray(16)
    private var river: Path? = null
    private var parks = FloatArray(0)
    private var grid = FloatArray(0)
    private var margin = 0f
    private var revealEnd = 0f
    private var still: Bitmap? = null
    private var topScrim: Shader? = null
    private var bottomScrim: Shader? = null
    private val random = Random(SEED_DAY())

    private var clock = 0f
    private var last = 0L
    private var resumed = true
    private var shown = false
    private var motion = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply { color = 0x0c22204a; strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = StudioUi.face(context, true); textSize = 9.5f * resources.displayMetrics.scaledDensity
        color = StudioUi.INK
    }
    private val halo = Paint(label).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeJoin = Paint.Join.ROUND; color = PAPER
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(PAPER)
    }

    /** Activity onResume: animate again from where it stopped. */
    fun resume() { resumed = true; last = 0L; motion = motionAllowed(); invalidate() }
    /** Activity onPause: stop asking for frames. */
    fun pause() { resumed = false }

    private val animating get() = resumed && shown && motion

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        shown = visibility == VISIBLE
        last = 0L
        if (shown) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion = motionAllowed()
    }

    private fun motionAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled()
        return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        build(w.toFloat(), h.toFloat())
        topScrim = LinearGradient(0f, 0f, 0f, 150 * d, intArrayOf(PAPER and 0xf0ffffff.toInt(), PAPER and 0x00ffffff),
            null, Shader.TileMode.CLAMP)
        bottomScrim = LinearGradient(0f, h - 110 * d, 0f, h.toFloat(), intArrayOf(PAPER and 0x00ffffff, PAPER and 0xb0ffffff.toInt()),
            null, Shader.TileMode.CLAMP)
        still?.recycle()
        still = try {
            val m = margin.toInt()
            Bitmap.createBitmap(w + 2 * m, h + 2 * m, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.translate(m.toFloat(), m.toFloat())
                drawMap(c, Float.MAX_VALUE)
            }
        } catch (_: OutOfMemoryError) { null }
        clock = 0f
        last = 0L
    }

    // ---------- network ----------

    private fun snap(v: Float) = (v / (4 * d)).roundToInt() * 4 * d

    /** Octilinear run across the board: straight stretches joined by 45° bends, kept within band ± reach. */
    private fun octilinear(horizontal: Boolean, band: Float, reach: Float, from: Float, to: Float, bends: Boolean): FloatArray {
        val out = ArrayList<Float>()
        var u = snap(from)
        var s = snap(band + (random.nextFloat() - .5f) * reach * .6f)
        fun push() { if (horizontal) { out += u; out += s } else { out += s; out += u } }
        push()
        while (u < to) {
            u += snap(60 * d + random.nextFloat() * 130 * d); push()
            if (u >= to || !bends) continue
            val far = random.nextFloat() < .35f
            var ds = snap(if (far) 56 * d + random.nextFloat() * max(0f, 2 * reach - 56 * d) else 24 * d + random.nextFloat() * 24 * d)
            if (random.nextBoolean()) ds = -ds
            if (abs(s + ds - band) > reach) ds = -ds
            if (abs(s + ds - band) > reach) ds = 0f
            if (ds != 0f) { u += abs(ds); s += ds; push() }
        }
        return out.toFloatArray()
    }

    private fun rounded(p: FloatArray, r: Float) = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size - 2) {
            val ax = p[i - 2]; val ay = p[i - 1]; val cx = p[i]; val cy = p[i + 1]; val bx = p[i + 2]; val by = p[i + 3]
            val la = hypot(cx - ax, cy - ay); val lb = hypot(bx - cx, by - cy)
            val c = min(r, min(la, lb) / 2)
            lineTo(cx - (cx - ax) / la * c, cy - (cy - ay) / la * c)
            quadTo(cx, cy, cx + (bx - cx) / lb * c, cy + (by - cy) / lb * c)
            i += 2
        }
        lineTo(p[p.size - 2], p[p.size - 1])
    }

    private fun sample(path: Path): FloatArray {
        val m = PathMeasure(path, false)
        val step = STEP * d
        val n = (m.length / step).toInt() + 1
        val out = FloatArray(n * 2)
        for (k in 0 until n) { m.getPosTan(k * step, pos, null); out[2 * k] = pos[0]; out[2 * k + 1] = pos[1] }
        return out
    }

    private fun nearest(line: Line, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE; var at = 0
        var k = 0
        while (k < line.samples.size) {
            val q = hypot(line.samples[k] - x, line.samples[k + 1] - y)
            if (q < best) { best = q; at = k / 2 }
            k += 2
        }
        return at * STEP * d
    }

    private fun gap(line: Line, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        var k = 0
        while (k < line.samples.size) { best = min(best, hypot(line.samples[k] - x, line.samples[k + 1] - y)); k += 2 }
        return best
    }

    private fun nearCorner(line: Line, x: Float, y: Float, r: Float): Boolean {
        var k = 0
        while (k < line.corners.size) { if (hypot(line.corners[k] - x, line.corners[k + 1] - y) < r) return true; k += 2 }
        return false
    }

    /** True when two polylines run along each other (parallel segments closer than [near] that overlap). */
    private fun clash(a: FloatArray, b: FloatArray, near: Float): Boolean {
        var i = 0
        while (i < a.size - 2) {
            val ax = a[i]; val ay = a[i + 1]; val dx = a[i + 2] - ax; val dy = a[i + 3] - ay
            val la = hypot(dx, dy)
            var k = 0
            while (k < b.size - 2) {
                val bx = b[k]; val by = b[k + 1]; val ex = b[k + 2] - bx; val ey = b[k + 3] - by
                val lb = hypot(ex, ey)
                if (la > 0 && lb > 0 && abs(dx * ey - dy * ex) / (la * lb) < .02f) {
                    val off = abs((bx - ax) * dy - (by - ay) * dx) / la
                    if (off < near) {
                        val t0 = ((bx - ax) * dx + (by - ay) * dy) / (la * la)
                        val t1 = ((bx + ex - ax) * dx + (by + ey - ay) * dy) / (la * la)
                        if (max(t0, t1) > 0f && min(t0, t1) < 1f) return true
                    }
                }
                k += 2
            }
            i += 2
        }
        return false
    }

    private fun build(w: Float, h: Float) {
        lines.clear(); stations.clear()
        margin = 28 * d
        val r = CORNER * d
        val left = -margin - 40 * d; val top = -margin - 40 * d
        val nH = (h / (150 * d)).roundToInt().coerceIn(3, 6)
        val nV = (w / (170 * d)).roundToInt().coerceIn(2, 4)
        val horizontal = ArrayList<FloatArray>()
        for (i in 0 until nH) {
            val gapH = h / nH
            horizontal += octilinear(true, (i + .5f) * gapH, gapH * .36f, left, w - left, true)
        }
        val vertical = ArrayList<FloatArray>()
        for (i in 0 until nV) {
            val gapV = w / nV
            var p = octilinear(false, (i + .5f) * gapV, gapV * .36f, top, h - top, true)
            var tries = 0
            while (horizontal.any { clash(p, it, 16 * d) } && tries++ < 14)
                p = octilinear(false, (i + .5f) * gapV, gapV * .36f, top, h - top, true)
            if (horizontal.any { clash(p, it, 16 * d) }) p = octilinear(false, (i + .5f) * gapV, gapV * .36f, top, h - top, false)
            vertical += p
        }
        // Interleave directions so neighbouring colours do not all run the same way.
        val polys = (horizontal + vertical).shuffled(random)
        val palette = PALETTE.toList().shuffled(random)
        polys.forEachIndexed { i, p ->
            val path = rounded(p, r)
            val corners = p.copyOfRange(2, max(2, p.size - 2))
            lines += Line(palette[i % palette.size], path, PathMeasure(path, false).length, i * .16f, sample(path), corners)
        }
        revealEnd = (lines.size - 1) * .16f + GROW + .9f

        // Interchanges where an H line crosses a V line on straight track, clear of corners and other lines.
        val visible = RectF(-8 * d, -8 * d, w + 8 * d, h + 8 * d)
        for (a in lines.indices) for (b in a + 1 until lines.size) {
            val pa = polys[a]; val pb = polys[b]
            var i = 0
            while (i < pa.size - 2) {
                var k = 0
                while (k < pb.size - 2) {
                    val x1 = pa[i]; val y1 = pa[i + 1]; val dx = pa[i + 2] - x1; val dy = pa[i + 3] - y1
                    val x2 = pb[k]; val y2 = pb[k + 1]; val ex = pb[k + 2] - x2; val ey = pb[k + 3] - y2
                    val den = dx * ey - dy * ex
                    if (abs(den) > 1e-3f) {
                        val t = ((x2 - x1) * ey - (y2 - y1) * ex) / den
                        val u = ((x2 - x1) * dy - (y2 - y1) * dx) / den
                        if (t in 0f..1f && u in 0f..1f) {
                            val x = x1 + dx * t; val y = y1 + dy * t
                            val clear = visible.contains(x, y) &&
                                !nearCorner(lines[a], x, y, r + 8 * d) && !nearCorner(lines[b], x, y, r + 8 * d) &&
                                stations.none { hypot(it.x - x, it.y - y) < 40 * d } &&
                                lines.indices.none { it != a && it != b && gap(lines[it], x, y) < 18 * d }
                            if (clear) stations += Station(x, y, true, StudioUi.INK).apply {
                                owners[0] = a; at[0] = nearest(lines[a], x, y)
                                owners[1] = b; at[1] = nearest(lines[b], x, y)
                            }
                        }
                    }
                    k += 2
                }
                i += 2
            }
        }
        // Small stations every 70–110 dp along each line, on straight track and away from everything else.
        lines.forEachIndexed { li, line ->
            var along = 30 * d + random.nextFloat() * 60 * d
            while (along < line.length) {
                var placed = false
                var nudge = 0
                while (!placed && nudge < 4) {
                    val s = along + nudge * 12 * d
                    line.measure.getPosTan(s, pos, null)
                    val x = pos[0]; val y = pos[1]
                    if (visible.contains(x, y) && !nearCorner(line, x, y, r + 8 * d) &&
                        stations.none { hypot(it.x - x, it.y - y) < (if (it.transit) 36 else 30) * d } &&
                        lines.indices.none { it != li && gap(lines[it], x, y) < 22 * d }) {
                        stations += Station(x, y, false, line.color).apply { owners[0] = li; at[0] = s }
                        placed = true
                    }
                    nudge++
                }
                along += 70 * d + random.nextFloat() * 40 * d
            }
        }
        pulse = FloatArray(stations.size) { PULSE }
        pulseColor = IntArray(stations.size)
        // Each line's stops in order, then its trains parked at a stop, ready to leave.
        lines.forEachIndexed { li, line ->
            val own = stations.indices.mapNotNull { s ->
                val st = stations[s]
                when (li) { st.owners[0] -> st.at[0] to s; st.owners[1] -> st.at[1] to s; else -> null }
            }.sortedBy { it.first }
            line.stops = FloatArray(own.size) { own[it].first }
            line.stopIds = IntArray(own.size) { own[it].second }
            val count = if (line.length > 900 * d) 2 else 1
            for (n in 0 until count) {
                val start = if (own.isEmpty()) random.nextFloat() * line.length
                    else own[((n + random.nextFloat()) / count * own.size).toInt().coerceIn(0, own.size - 1)].first
                line.trains += Train(start, if (random.nextBoolean()) 1 else -1, (38 + random.nextFloat() * 14) * d).apply {
                    wait = random.nextFloat() * 1.5f
                }
            }
        }
        labelStations(w, h)

        // Paper: faint grid, a river and a few parks, all under the lines.
        val step = 28 * d
        val cols = ((w + 2 * margin) / step).toInt() + 2; val rows = ((h + 2 * margin) / step).toInt() + 2
        grid = FloatArray((cols + rows) * 4)
        for (c in 0 until cols) { val x = -margin + c * step; grid[c * 4] = x; grid[c * 4 + 1] = -margin; grid[c * 4 + 2] = x; grid[c * 4 + 3] = h + margin }
        for (q in 0 until rows) { val y = -margin + q * step; val o = (cols + q) * 4; grid[o] = -margin; grid[o + 1] = y; grid[o + 2] = w + margin; grid[o + 3] = y }
        river = rounded(octilinear(true, h * (.38f + random.nextFloat() * .3f), h * .18f, left, w - left, true), 46 * d)
        parks = FloatArray(12)
        for (p in 0 until 3) {
            val pw = (64 + random.nextFloat() * 70) * d; val ph = (48 + random.nextFloat() * 56) * d
            val px = random.nextFloat() * (w - pw); val py = h * (p + .2f + random.nextFloat() * .5f) / 3f
            parks[p * 4] = px; parks[p * 4 + 1] = py; parks[p * 4 + 2] = px + pw; parks[p * 4 + 3] = py + ph
        }
    }

    private fun labelStations(w: Float, h: Float) {
        val names = NAMES.toMutableList().apply { shuffle(random) }
        val cap = (w * h / (26000 * d * d)).toInt().coerceIn(6, 18)
        val taken = ArrayList<RectF>()
        val metrics = label.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val order = stations.filter { it.transit } + stations.filter { !it.transit }.shuffled(random)
        var count = 0
        for (st in order) {
            if (count >= cap || names.isEmpty()) break
            val name = names.first()
            val tw = label.measureText(name)
            val g = (if (st.transit) 12 else 9) * d
            val candidates = arrayOf(
                floatArrayOf(st.x + g, st.y - textH / 2), floatArrayOf(st.x - g - tw, st.y - textH / 2),
                floatArrayOf(st.x - tw / 2, st.y - g - textH), floatArrayOf(st.x - tw / 2, st.y + g),
            )
            for (c in candidates) {
                val rect = RectF(c[0], c[1], c[0] + tw, c[1] + textH)
                // keep clear of the toolbar, the edges, other names, every line and every station
                if (rect.top < 96 * d || rect.left < 8 * d || rect.right > w - 8 * d || rect.bottom > h - 8 * d) continue
                val pad = RectF(rect).apply { inset(-4 * d, -3 * d) }
                if (taken.any { RectF.intersects(it, pad) }) continue
                if (stations.any { pad.contains(it.x, it.y) || hypot(it.x - pad.centerX(), it.y - pad.centerY()) < 6 * d }) continue
                if (lines.any { line -> var hit = false; var k = 0
                        while (k < line.samples.size && !hit) { hit = pad.contains(line.samples[k], line.samples[k + 1]); k += 2 }
                        hit }) continue
                st.label = name; st.lx = rect.left; st.ly = rect.top - metrics.ascent
                taken += pad
                names.removeAt(0)
                count++
                break
            }
        }
    }

    // ---------- drawing ----------

    private fun ease(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

    /** The still map at time [t] of the reveal (Float.MAX_VALUE = fully drawn). */
    private fun drawMap(c: Canvas, t: Float) {
        c.drawColor(PAPER)
        c.drawLines(grid, gridPaint)
        val paper = min(1f, t / .6f)
        fill.color = PARK; fill.alpha = (255 * paper).toInt()
        var p = 0
        while (p < parks.size) { c.drawRoundRect(parks[p], parks[p + 1], parks[p + 2], parks[p + 3], 18 * d, 18 * d, fill); p += 4 }
        river?.let { stroke.color = RIVER; stroke.alpha = (255 * paper).toInt(); stroke.strokeWidth = 34 * d; c.drawPath(it, stroke) }

        for (i in lines.indices) {
            val line = lines[i]
            grown[i] = line.length * ease(((t - line.delay) / GROW).coerceIn(0f, 1f))
        }
        val lw = LINE * d
        // depth: a soft offset shadow under every line first, so crossings stay clean
        c.save(); c.translate(0f, 1.8f * d)
        for (i in lines.indices) {
            if (grown[i] <= 0f) continue
            val path = shape(i)
            stroke.color = SHADOW; stroke.strokeWidth = lw + 7 * d; c.drawPath(path, stroke)
            stroke.color = SHADOW; stroke.strokeWidth = lw + 3.5f * d; c.drawPath(path, stroke)
        }
        c.restore()
        for (i in lines.indices) {
            if (grown[i] <= 0f) continue
            val path = shape(i)
            stroke.color = CASING; stroke.strokeWidth = lw + 4 * d; c.drawPath(path, stroke)
            stroke.color = lines[i].color; stroke.strokeWidth = lw; c.drawPath(path, stroke)
        }
        for (s in stations.indices) {
            val st = stations[s]
            var born = ((grown[st.owners[0]] - st.at[0]) / (26 * d)).coerceIn(0f, 1f)
            if (st.owners[1] >= 0) born = min(born, ((grown[st.owners[1]] - st.at[1]) / (26 * d)).coerceIn(0f, 1f))
            if (born <= 0f) continue
            // a small overshoot as each station pops in
            val k = born - 1f
            val pop = 1f + 2.4f * k * k * k + 1.4f * k * k
            if (st.transit) {
                fill.color = SHADOW; c.drawCircle(st.x, st.y + 1.6f * d, 8.6f * d * pop, fill)
                fill.color = 0xffffffff.toInt(); c.drawCircle(st.x, st.y, 7f * d * pop, fill)
                stroke.color = StudioUi.INK; stroke.strokeWidth = 2.6f * d; c.drawCircle(st.x, st.y, 7f * d * pop, stroke)
            } else {
                fill.color = 0xffffffff.toInt(); c.drawCircle(st.x, st.y, 3.4f * d * pop, fill)
                stroke.color = StudioUi.INK; stroke.strokeWidth = 1.8f * d; c.drawCircle(st.x, st.y, 3.4f * d * pop, stroke)
            }
            val name = st.label ?: continue
            val a = (born * 2f - 1f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            halo.alpha = (230 * a).toInt(); c.drawText(name, st.lx, st.ly, halo)
            label.alpha = (175 * a).toInt(); c.drawText(name, st.lx, st.ly, label)
        }
    }

    private fun shape(i: Int): Path {
        val line = lines[i]
        if (grown[i] >= line.length) return line.path
        line.reveal.rewind()
        line.measure.getSegment(0f, grown[i], line.reveal, true)
        return line.reveal
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    /** Stop-and-go: ease out of a stop, cruise, ease into the next one, wait, then the station pulses. */
    private fun step(line: Line, tr: Train, dt: Float) {
        if (tr.wait > 0f) {
            tr.wait -= dt
            if (tr.wait <= 0f) { tr.wait = 0f; tr.since = 0f }
            return
        }
        tr.since += dt
        var target = if (tr.dir > 0) line.length else 0f
        var id = -1
        if (tr.dir > 0) { for (k in line.stops.indices) if (line.stops[k] > tr.at + .5f) { target = line.stops[k]; id = line.stopIds[k]; break } }
        else { for (k in line.stops.indices.reversed()) if (line.stops[k] < tr.at - .5f) { target = line.stops[k]; id = line.stopIds[k]; break } }
        val rest = abs(target - tr.at)
        val cruise = tr.top * smooth(min(1f, tr.since / 1.8f))
        val brake = max(4 * d, rest * tr.top / (44 * d))
        val move = min(max(cruise, 2 * d), brake) * dt
        if (move >= rest) {
            tr.at = target
            if (id < 0) { tr.dir = -tr.dir; tr.wait = .4f }
            else { tr.wait = 1.4f + random.nextFloat(); pulse[id] = 0f; pulseColor[id] = line.color }
        } else tr.at += move * tr.dir
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val live = animating
        val dt = if (live && last != 0L) min(.05f, (now - last) / 1000f) else 0f
        last = if (live) now else 0L
        if (!motion) clock = revealEnd + 1f else clock += dt
        val w = width.toFloat(); val h = height.toFloat()

        // a very slow drift and breathe of the camera
        canvas.save()
        if (motion) {
            val c = clock
            val dx = (sin(c * TAU / 47f) * 10f + sin(c * TAU / 83f) * 6f) * d
            val dy = cos(c * TAU / 61f) * 9f * d
            val s = 1f + .018f * (.5f + .5f * sin(c * TAU / 71f))
            canvas.translate(w / 2 + dx, h / 2 + dy); canvas.scale(s, s); canvas.translate(-w / 2, -h / 2)
        }
        val map = still
        if (clock >= revealEnd && map != null) canvas.drawBitmap(map, -margin, -margin, bitmapPaint)
        else drawMap(canvas, clock)

        for (s in pulse.indices) {
            if (pulse[s] >= PULSE) continue
            pulse[s] += dt
            val e = min(1f, pulse[s] / PULSE)
            val st = stations[s]
            stroke.color = pulseColor[s]; stroke.alpha = (150 * (1f - e) * (1f - e)).toInt()
            stroke.strokeWidth = 2.5f * d
            canvas.drawCircle(st.x, st.y, (if (st.transit) 8f else 4.5f) * d + 13f * d * ease(e), stroke)
        }
        for (i in lines.indices) {
            val line = lines[i]
            if (clock < line.delay + GROW) continue
            for (tr in line.trains) {
                if (live) step(line, tr, dt)
                tr.alpha = if (motion) min(1f, tr.alpha + dt / .5f) else 1f
                line.measure.getPosTan(tr.at, pos, tan)
                canvas.save()
                canvas.translate(pos[0], pos[1])
                canvas.rotate((atan2(tan[1], tan[0]) * 180.0 / PI).toFloat())
                val a = (255 * tr.alpha).toInt()
                box.set(-11 * d, -4.5f * d, 11 * d, 4.5f * d)
                fill.color = SHADOW; fill.alpha = (fill.alpha * tr.alpha).toInt()
                box.offset(0f, 1.5f * d); canvas.drawRoundRect(box, 4.5f * d, 4.5f * d, fill); box.offset(0f, -1.5f * d)
                fill.color = line.color; fill.alpha = a
                canvas.drawRoundRect(box, 4.5f * d, 4.5f * d, fill)
                stroke.color = 0xffffffff.toInt(); stroke.alpha = a; stroke.strokeWidth = 1.8f * d
                canvas.drawRoundRect(box, 4.5f * d, 4.5f * d, stroke)
                // a pale windscreen at the front
                fill.color = 0xffffffff.toInt(); fill.alpha = (150 * tr.alpha).toInt()
                val f = if (tr.dir > 0) 1f else -1f
                canvas.drawCircle(f * 6.5f * d, 0f, 1.6f * d, fill)
                canvas.restore()
            }
        }
        canvas.restore()

        // keep the toolbar and the bottom of the list readable over the map
        scrim.shader = topScrim; canvas.drawRect(0f, 0f, w, 150 * d, scrim)
        scrim.shader = bottomScrim; canvas.drawRect(0f, h - 110 * d, w, h, scrim)
        scrim.shader = null
        if (live) postInvalidateOnAnimation()
    }

    companion object {
        val PAPER = 0xfffbf6ec.toInt()
        private val CASING = 0xfffffcf6.toInt()
        private const val SHADOW = 0x1422204a
        private val RIVER = 0xffdcebf1.toInt()
        private val PARK = 0xffe9f2dc.toInt()
        private const val CORNER = 14f
        private const val LINE = 5.5f
        private const val STEP = 3f
        private const val GROW = 1.9f
        private const val PULSE = 1.1f
        private const val TAU = (2 * PI).toFloat()
        private fun SEED_DAY() = (System.currentTimeMillis() / 86_400_000L).toInt()
        // StudioUi's game colours (orange, purple, leaf, red, yellow) plus a blue, teal and pink that sit with them.
        private val PALETTE = intArrayOf(
            0xffff7a2f.toInt(), 0xff6c4df6.toInt(), 0xff3fb06a.toInt(), 0xff3e86f5.toInt(),
            0xffe5484d.toInt(), 0xfff5a623.toInt(), 0xff17a9b8.toInt(), 0xffd6589b.toInt(),
        )
        private val NAMES = listOf(
            "Manggarai", "Tanah Abang", "Sudirman", "Juanda", "Gambir", "Kota", "Jatinegara", "Senen",
            "Kemayoran", "Pasar Minggu", "Cawang", "Duri", "Palmerah", "Kebayoran", "Bundaran HI", "Dukuh Atas",
            "Blok M", "Fatmawati", "Lebak Bulus", "Cipete", "Setiabudi", "Bendungan Hilir", "Istora", "Senayan",
            "Harmoni", "Glodok", "Mangga Besar", "Sawah Besar", "Tebet", "Cikini", "Gondangdia", "Karet",
            "Grogol", "Pesing", "Angke", "Kampung Bandan", "Ancol", "Rajawali", "Klender", "Buaran", "Cakung",
            "Kalibata", "Lenteng Agung", "Tanjung Barat", "Pancoran", "Kuningan", "Velodrome", "Pulomas",
        )
    }
}
