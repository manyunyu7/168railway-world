// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Duniaku backdrop: the ppka-wannabe-2 intro's metro map, blurred behind a thin light scrim.
 * Octilinear lines (H/V/45°) with rounded bends draw themselves in, interchanges (white rings, black rims)
 * sit where lines cross, capsule trains ease in and out of stations and the station they reach pulses once.
 * The network is seeded by the day, so it is stable across reopenings but changes daily.
 *
 * Blur, as the web's `filter: blur(7px)`: Android 12+ blurs this view with a RenderEffect; older phones
 * draw the scene into a quarter-size bitmap and stretch it back with bilinear filtering.
 * After the reveal the still map is one cached bitmap; a frame only moves the camera and the trains,
 * with nothing allocated. It animates only while resumed and shown, and stands still when the system
 * "remove animations" setting is on.
 */
internal class MetroBackdrop(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val effect = Build.VERSION.SDK_INT >= 31
    /** Resolution of the cached map (and of the whole scene below Android 12): it is blurred anyway. */
    private val k = if (effect) .5f else .25f

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

    private class Station(val x: Float, val y: Float, val transit: Boolean) {
        val owners = IntArray(2) { -1 }
        val at = FloatArray(2)
    }

    private val lines = ArrayList<Line>()
    private val stations = ArrayList<Station>()
    private var pulse = FloatArray(0)
    private var pulseColor = IntArray(0)
    private val grown = FloatArray(16)
    private var river: Path? = null
    private var margin = 0f
    private var revealEnd = 0f
    private var still: Bitmap? = null
    private var small: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private var glow: Shader? = null
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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val box = RectF()
    private val stillRect = RectF()
    private val viewRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(PAPER)
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(BLUR * d, BLUR * d, Shader.TileMode.CLAMP))
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
        viewRect.set(0f, 0f, w.toFloat(), h.toFloat())
        stillRect.set(-margin, -margin, w + margin, h + margin)
        // web #intro::after: a soft ellipse at 50% 46% fading out by 55%, over an 8% wash
        val rx = w / 2f * sqrt(2f); val ry = h * .54f * sqrt(2f)
        glow = RadialGradient(0f, 0f, 1f, intArrayOf(SCRIM_GLOW, SCRIM_GLOW and 0x00ffffff), floatArrayOf(0f, .55f),
            Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(w / 2f, h * .46f) })
        }
        still?.recycle(); small?.recycle()
        still = try {
            Bitmap.createBitmap(((w + 2 * margin) * k).toInt(), ((h + 2 * margin) * k).toInt(), Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.scale(k, k)
                c.translate(margin, margin)
                drawMap(c, Float.MAX_VALUE)
            }
        } catch (_: OutOfMemoryError) { null }
        small = if (effect) null else try {
            Bitmap.createBitmap(max(1, (w * k).toInt()), max(1, (h * k).toInt()), Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) { null }
        smallCanvas = small?.let { Canvas(it) }
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
            u += snap(80 * d + random.nextFloat() * 150 * d); push()
            if (u >= to || !bends) continue
            val far = random.nextFloat() < .35f
            var ds = snap(if (far) 60 * d + random.nextFloat() * max(0f, 2 * reach - 60 * d) else 30 * d + random.nextFloat() * 40 * d)
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
        for (q in 0 until n) { m.getPosTan(q * step, pos, null); out[2 * q] = pos[0]; out[2 * q + 1] = pos[1] }
        return out
    }

    private fun nearest(line: Line, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE; var at = 0
        var q = 0
        while (q < line.samples.size) {
            val g = hypot(line.samples[q] - x, line.samples[q + 1] - y)
            if (g < best) { best = g; at = q / 2 }
            q += 2
        }
        return at * STEP * d
    }

    private fun gap(line: Line, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        var q = 0
        while (q < line.samples.size) { best = min(best, hypot(line.samples[q] - x, line.samples[q + 1] - y)); q += 2 }
        return best
    }

    private fun nearCorner(line: Line, x: Float, y: Float, r: Float): Boolean {
        var q = 0
        while (q < line.corners.size) { if (hypot(line.corners[q] - x, line.corners[q + 1] - y) < r) return true; q += 2 }
        return false
    }

    /** True when two polylines run along each other (parallel segments closer than [near] that overlap). */
    private fun clash(a: FloatArray, b: FloatArray, near: Float): Boolean {
        var i = 0
        while (i < a.size - 2) {
            val ax = a[i]; val ay = a[i + 1]; val dx = a[i + 2] - ax; val dy = a[i + 3] - ay
            val la = hypot(dx, dy)
            var j = 0
            while (j < b.size - 2) {
                val bx = b[j]; val by = b[j + 1]; val ex = b[j + 2] - bx; val ey = b[j + 3] - by
                val lb = hypot(ex, ey)
                if (la > 0 && lb > 0 && abs(dx * ey - dy * ex) / (la * lb) < .02f) {
                    val off = abs((bx - ax) * dy - (by - ay) * dx) / la
                    if (off < near) {
                        val t0 = ((bx - ax) * dx + (by - ay) * dy) / (la * la)
                        val t1 = ((bx + ex - ax) * dx + (by + ey - ay) * dy) / (la * la)
                        if (max(t0, t1) > 0f && min(t0, t1) < 1f) return true
                    }
                }
                j += 2
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
        // web: h/180 horizontal lines (3–6), w/420 vertical ones (2–4)
        val nH = (h / (180 * d)).roundToInt().coerceIn(3, 6)
        val nV = (w / (190 * d)).roundToInt().coerceIn(2, 4)
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
            while (horizontal.any { clash(p, it, 18 * d) } && tries++ < 14)
                p = octilinear(false, (i + .5f) * gapV, gapV * .36f, top, h - top, true)
            if (horizontal.any { clash(p, it, 18 * d) }) p = octilinear(false, (i + .5f) * gapV, gapV * .36f, top, h - top, false)
            vertical += p
        }
        // Interleave directions so neighbouring colours do not all run the same way.
        val polys = (horizontal + vertical).shuffled(random)
        polys.forEachIndexed { i, p ->
            val path = rounded(p, r)
            val corners = p.copyOfRange(2, max(2, p.size - 2))
            lines += Line(PALETTE[i % PALETTE.size], path, PathMeasure(path, false).length, i * .13f, sample(path), corners)
        }
        revealEnd = (lines.size - 1) * .13f + GROW + .9f

        // Interchanges where two lines cross on straight track, clear of corners and other lines.
        val visible = RectF(-8 * d, -8 * d, w + 8 * d, h + 8 * d)
        for (a in lines.indices) for (b in a + 1 until lines.size) {
            val pa = polys[a]; val pb = polys[b]
            var i = 0
            while (i < pa.size - 2) {
                var j = 0
                while (j < pb.size - 2) {
                    val x1 = pa[i]; val y1 = pa[i + 1]; val dx = pa[i + 2] - x1; val dy = pa[i + 3] - y1
                    val x2 = pb[j]; val y2 = pb[j + 1]; val ex = pb[j + 2] - x2; val ey = pb[j + 3] - y2
                    val den = dx * ey - dy * ex
                    if (abs(den) > 1e-3f) {
                        val t = ((x2 - x1) * ey - (y2 - y1) * ex) / den
                        val u = ((x2 - x1) * dy - (y2 - y1) * dx) / den
                        if (t in 0f..1f && u in 0f..1f) {
                            val x = x1 + dx * t; val y = y1 + dy * t
                            val clear = visible.contains(x, y) &&
                                !nearCorner(lines[a], x, y, r + 8 * d) && !nearCorner(lines[b], x, y, r + 8 * d) &&
                                stations.none { hypot(it.x - x, it.y - y) < 60 * d } &&
                                lines.indices.none { it != a && it != b && gap(lines[it], x, y) < 20 * d }
                            if (clear) stations += Station(x, y, true).apply {
                                owners[0] = a; at[0] = nearest(lines[a], x, y)
                                owners[1] = b; at[1] = nearest(lines[b], x, y)
                            }
                        }
                    }
                    j += 2
                }
                i += 2
            }
        }
        // Small stations every 110–180 dp along each line, on straight track and away from everything else.
        lines.forEachIndexed { li, line ->
            var along = 50 * d + random.nextFloat() * 90 * d
            while (along < line.length) {
                var placed = false
                var nudge = 0
                while (!placed && nudge < 4) {
                    val s = along + nudge * 14 * d
                    line.measure.getPosTan(s, pos, null)
                    val x = pos[0]; val y = pos[1]
                    if (visible.contains(x, y) && !nearCorner(line, x, y, r + 8 * d) &&
                        stations.none { hypot(it.x - x, it.y - y) < (if (it.transit) 40 else 34) * d } &&
                        lines.indices.none { it != li && gap(lines[it], x, y) < 24 * d }) {
                        stations += Station(x, y, false).apply { owners[0] = li; at[0] = s }
                        placed = true
                    }
                    nudge++
                }
                along += 110 * d + random.nextFloat() * 70 * d
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
                line.trains += Train(start, if (random.nextBoolean()) 1 else -1, (42 + random.nextFloat() * 16) * d).apply {
                    wait = random.nextFloat() * 1.5f
                }
            }
        }
        // A faint river band slanting across, under the lines.
        river = rounded(octilinear(true, h * (.3f + random.nextFloat() * .4f), h * .18f, left, w - left, true), 40 * d)
    }

    // ---------- drawing ----------

    private fun ease(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

    /** The still map at time [t] of the reveal (Float.MAX_VALUE = fully drawn). */
    private fun drawMap(c: Canvas, t: Float) {
        c.drawColor(PAPER)
        river?.let {
            stroke.color = RIVER; stroke.alpha = (255 * min(1f, t / .6f)).toInt(); stroke.strokeWidth = 46 * d
            c.drawPath(it, stroke)
        }
        for (i in lines.indices) {
            val line = lines[i]
            grown[i] = line.length * ease(((t - line.delay) / GROW).coerceIn(0f, 1f))
        }
        stroke.strokeWidth = LINE * d
        for (i in lines.indices) {
            if (grown[i] <= 0f) continue
            stroke.color = lines[i].color; stroke.alpha = 230
            c.drawPath(shape(i), stroke)
        }
        for (s in stations.indices) {
            val st = stations[s]
            var born = ((grown[st.owners[0]] - st.at[0]) / (40 * d)).coerceIn(0f, 1f)
            if (st.owners[1] >= 0) born = if (t >= revealEnd - .6f) min(1f, (t - revealEnd + .6f) / .6f) else 0f
            if (born <= 0f) continue
            val pop = 1f - (1f - born) * (1f - born)
            if (st.transit) {
                // web: white ring, thick dark rim, fading in once the whole network is drawn
                fill.color = 0xffffffff.toInt(); fill.alpha = (255 * born).toInt(); c.drawCircle(st.x, st.y, 8.5f * d, fill)
                stroke.color = RIM; stroke.alpha = (255 * born).toInt(); stroke.strokeWidth = 3.5f * d
                c.drawCircle(st.x, st.y, 8.5f * d, stroke)
            } else {
                // web: a dark dot with a pale grey rim, growing in as the line passes
                fill.color = DOT; c.drawCircle(st.x, st.y, 4.5f * d * pop, fill)
                stroke.color = DOT_RIM; stroke.strokeWidth = 2.2f * d; c.drawCircle(st.x, st.y, 4.5f * d * pop, stroke)
            }
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
        if (tr.dir > 0) { for (q in line.stops.indices) if (line.stops[q] > tr.at + .5f) { target = line.stops[q]; id = line.stopIds[q]; break } }
        else { for (q in line.stops.indices.reversed()) if (line.stops[q] < tr.at - .5f) { target = line.stops[q]; id = line.stopIds[q]; break } }
        val rest = abs(target - tr.at)
        val cruise = tr.top * smooth(min(1f, tr.since / 1.8f))
        val brake = max(4 * d, rest * tr.top / (44 * d))
        val move = min(max(cruise, 2 * d), brake) * dt
        if (move >= rest) {
            tr.at = target
            if (id < 0) { tr.dir = -tr.dir; tr.wait = .5f }
            else { tr.wait = .9f + random.nextFloat(); pulse[id] = 0f; pulseColor[id] = line.color }
        } else tr.at += move * tr.dir
    }

    /** Camera, map, pulses and trains, in view coordinates. */
    private fun drawScene(canvas: Canvas, live: Boolean, dt: Float, w: Float, h: Float) {
        canvas.save()
        if (motion) {
            // a very slow drift and breathe of the camera
            val c = clock
            val dx = (sin(c * TAU / 47f) * 10f + sin(c * TAU / 83f) * 6f) * d
            val dy = cos(c * TAU / 61f) * 9f * d
            val s = 1.02f + .02f * sin(c * TAU / 71f)
            canvas.translate(w / 2 + dx, h / 2 + dy); canvas.scale(s, s); canvas.translate(-w / 2, -h / 2)
        }
        val map = still
        if (clock >= revealEnd && map != null) canvas.drawBitmap(map, null, stillRect, bitmapPaint)
        else drawMap(canvas, clock)

        for (s in pulse.indices) {
            if (pulse[s] >= PULSE) continue
            pulse[s] += dt
            val e = min(1f, pulse[s] / PULSE)
            val st = stations[s]
            stroke.color = pulseColor[s]; stroke.alpha = (170 * (1f - e) * (1f - e)).toInt()
            stroke.strokeWidth = 3f * d
            canvas.drawCircle(st.x, st.y, (if (st.transit) 9f else 5f) * d + 16f * d * ease(e), stroke)
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
                // web: a 28×11 capsule in the line colour with a dark rim
                box.set(-14 * d, -5.5f * d, 14 * d, 5.5f * d)
                fill.color = line.color; fill.alpha = a
                canvas.drawRoundRect(box, 5 * d, 5 * d, fill)
                stroke.color = RIM; stroke.alpha = (a * .85f).toInt(); stroke.strokeWidth = 2.5f * d
                canvas.drawRoundRect(box, 5 * d, 5 * d, stroke)
                canvas.restore()
            }
        }
        canvas.restore()
        // web #intro::after: an 8% light wash and a softer glow in the middle, so cards and text stand out
        canvas.drawColor(SCRIM)
        scrim.shader = glow; canvas.drawRect(0f, 0f, w, h, scrim); scrim.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val live = animating
        val dt = if (live && last != 0L) min(.05f, (now - last) / 1000f) else 0f
        last = if (live) now else 0L
        if (!motion) clock = revealEnd + 1f else clock += dt
        val w = width.toFloat(); val h = height.toFloat()
        val c = smallCanvas
        val bmp = small
        if (effect || c == null || bmp == null) drawScene(canvas, live, dt, w, h)
        else {
            // below Android 12: draw at a quarter size, then stretch it back up smoothly — a cheap blur
            c.save(); c.scale(k, k); drawScene(c, live, dt, w, h); c.restore()
            canvas.drawBitmap(bmp, null, viewRect, bitmapPaint)
        }
        if (live) postInvalidateOnAnimation()
    }

    companion object {
        /** web light theme --bg */
        val PAPER = 0xfff2f4f7.toInt()
        private val RIM = 0xff1c2229.toInt()
        private val DOT = 0xff2a333d.toInt()
        private val DOT_RIM = 0xffdfe4ea.toInt()
        /** web: rgba(110,160,210,.2) on the light background */
        private val RIVER = 0xffd8e3f0.toInt()
        /** web: rgba(235,240,246,.08) and the glow at .35 of that */
        private const val SCRIM = 0x14ebf0f6
        private const val SCRIM_GLOW = 0x07ebf0f6
        private const val BLUR = 7f
        private const val CORNER = 13f
        private const val LINE = 7f
        private const val STEP = 3f
        private const val GROW = 1.5f
        private const val PULSE = 1.1f
        private const val TAU = (2 * PI).toFloat()
        private fun SEED_DAY() = (System.currentTimeMillis() / 86_400_000L).toInt()
        /** web WARNA_METRO */
        private val PALETTE = intArrayOf(
            0xffe6402e.toInt(), 0xfff2a900.toInt(), 0xff2e77e6.toInt(), 0xff2ea84f.toInt(),
            0xff8e4fd1.toInt(), 0xff00b2c9.toInt(), 0xffe05fa0.toInt(), 0xff7a8a2e.toInt(),
        )
    }
}
