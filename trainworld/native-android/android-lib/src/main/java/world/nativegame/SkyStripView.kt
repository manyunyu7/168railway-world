// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The clock card's sky, the web's SkyBanner (app/src/ui/SkyBanner.tsx) on the phone: 24 hours at the
 * place in view, 12 back and 12 ahead, sliding left as game time passes. Sky colours by the sun's
 * height (a port of app/src/game/sun.ts), deeper at the top and lighter at the horizon; stars at
 * night, clouds at fixed times, far hills with town lights after dark, a track with sleepers, the
 * sun at the middle of each day and a crescent moon at the middle of each night peeking in from the
 * top, the red train at now, and each hour's demand level in a thin band underneath. Redrawn only
 * when the clock has moved a fifth of a pixel, the place changed or the size changed.
 */
internal class SkyStripView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    /** Local time in seconds (game clock + the shift to local time) and that time's UTC offset. */
    private var now = 8.0 * 3600
    private var utcOffset = 7.0
    private var lon = 106.83
    private var lat = -6.2
    private var placeKnown = false
    private var drawnAt = Double.NaN
    private var drawnKey = ""
    private var pixelsStale = true
    private var lastHour = -1L

    /** Moves the sky to game clock [clock] (seconds from day 1) on a clock running [shift] s from
     *  the registry clock (UTC-5); invalidates only when that shows. */
    fun setClock(clock: Double, shift: Int) {
        now = clock + shift
        utcOffset = CLOCK_UTC_OFFSET + shift / 3600.0
        if (!placeKnown) lon = utcOffset * 15
        val w = width
        if (w <= 0) return
        val secPx = HOURS * 3600.0 / w
        if (key() != drawnKey || drawnAt.isNaN() || abs(now - drawnAt) >= secPx * 0.2) { pixelsStale = true; invalidate() }
        val hour = floor(now / 3600).toLong()
        if (hour != lastHour) { lastHour = hour; describe() }
    }

    /** The view's centre as [lon, lat] (CitySurface.lonLat), where the sun is reckoned. */
    fun setPlace(ll: DoubleArray) { if (ll.size >= 2) setPlace(ll[0], ll[1]) }

    /** The view's centre, where the sun is reckoned. */
    fun setPlace(lon: Double, lat: Double) {
        if (!lon.isFinite() || !lat.isFinite()) return
        if (placeKnown && abs(lon - this.lon) < 0.05 && abs(lat - this.lat) < 0.05) return
        this.lon = lon; this.lat = lat; placeKnown = true
        pixelsStale = true; invalidate(); describe()
    }

    private fun key() = "$width $height $lon $lat $utcOffset"

    private fun describe() {
        val e = sunElevation(now, lon, lat, utcOffset)
        val sky = when { e > 5 -> "siang"; e > -6 -> if (localHour(now) < 12) "fajar" else "senja"; else -> "malam" }
        val h = localHour(now)
        val (level, until) = when (h) { in 6..19 -> "tinggi" to 20; in 20..23 -> "sedang" to 0; else -> "rendah" to 6 }
        contentDescription = "Langit 24 jam: sekarang $sky, permintaan $level sampai %02d.00. Ketuk untuk periode permintaan".format(java.util.Locale.ROOT, until)
    }

    // --- drawing -----------------------------------------------------------------------------

    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private val clip = Path()
    private val box = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flat = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val crescent = Path()
    private val cut = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap?.recycle(); bitmap = null
        clip.reset()
        val r = min(h / 2f, 11f * density)
        clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW)
        pixelsStale = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width; val h = height
        if (w < 2 || h < 2) return
        drawnAt = now; drawnKey = key()
        val scale = h / (SKY + GAP + BAND)
        val skyRows = Math.round(SKY * scale)
        val bandH = max(2f, BAND * scale)
        val railRow = floor(RAIL_Y * scale).toInt()
        val railRows = max(1, Math.round(scale * 0.9f))
        val secPx = HOURS * 3600.0 / w
        val nowX = Math.round(NOW_AT * w)
        val tAt = { x: Float -> now + (x - nowX) * secPx }
        val elevAt = { x: Float -> sunElevation(tAt(x), lon, lat, utcOffset) }

        canvas.save()
        canvas.clipPath(clip)

        // the sky, hills and ground, a column at a time into one bitmap
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != skyRows) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, skyRows, Bitmap.Config.ARGB_8888); bitmap = bmp
            pixels = IntArray(w * skyRows); pixelsStale = true
        }
        if (pixelsStale) {
            val hillStep = HILL_EVERY * scale * secPx
            for (x in 0 until w) {
                val t = now + (x + 0.5 - nowX) * secPx
                val e = sunElevation(t, lon, lat, utcOffset)
                val l = daylight(e)
                val top = ramp(TOP, e); val horizon = ramp(HORIZON, e)
                val hillTop = railRow - (1.2f + 3.4f * hillNoise(t / hillStep)) * scale
                val hill = mix(mix(HILL_NIGHT, HILL_DAY, l), horizon, 0.25f)
                val ground = mix(GROUND_NIGHT, GROUND_DAY, l)
                val rail = mix(RAIL_NIGHT, RAIL_DAY, l)
                for (y in 0 until skyRows) {
                    val c = when {
                        y >= railRow && y < railRow + railRows -> rail
                        y >= railRow + railRows -> ground
                        y >= hillTop -> if (y < hillTop + 1) mix(hill, horizon, 0.35f) else hill
                        else -> mix(top, horizon, (y / max(1f, hillTop)).pow(1.4f).coerceIn(0f, 1f))
                    }
                    pixels[y * w + x] = c
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, skyRows)
            pixelsStale = false
        }
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)

        // scenery fixed to times: the k-th item at time k * every web px
        fun each(every: Float, f: (Float, Long) -> Unit) {
            val step = every * scale * secPx
            var k = floor((now - nowX * secPx) / step).toLong() - 1
            while (true) {
                val x = (nowX + (k * step - now) / secPx).toFloat()
                if (x > w + 4 * scale) break
                f(x, k); k++
            }
        }

        // stars where it is night, a few brighter
        each(STAR_EVERY) { x, k ->
            val l = daylight(elevAt(x))
            if (l > 0.3f) return@each
            val big = hash(k + 0.9) > 0.8
            val a = (if (big) 1f else 0.7f) * (1 - l / 0.3f)
            paint.color = Color.argb((255 * a).toInt(), 255, 250, 225)
            val sx = x + (hash(k.toDouble()) * 4 * scale).toFloat()
            val sy = ((1 + hash(k + 0.5) * (STAR_LOW - 2)) * scale).toFloat()
            canvas.drawCircle(sx, sy, (if (big) 0.75f else 0.5f) * scale, paint)
        }

        // town lights on the hills at night
        each(LIGHT_EVERY) { x, k ->
            val l = daylight(elevAt(x))
            if (l > 0.5f || hash(k + 0.33) < 0.55) return@each
            val hillStep = HILL_EVERY * scale * secPx
            val lx = x + (hash(k + 0.77) * 2 * scale).toFloat()
            val hillTop = railRow - (1.2f + 3.4f * hillNoise(tAt(lx) / hillStep)) * scale
            val ly = hillTop + (0.8f + hash(k + 0.12).toFloat() * (railRow - hillTop - 1.2f * scale).coerceAtLeast(0f) / scale) * scale
            if (ly >= railRow - 0.4f * scale) return@each
            flat.color = Color.argb((230 * (1 - l / 0.5f)).toInt(), 255, 214, 120)
            canvas.drawRect(lx, ly, lx + 0.7f * scale, ly + 0.7f * scale, flat)
        }

        // T-902: none to two clouds a day as the day's number falls, white by day, dim blue at night
        val day0 = floor((now - nowX * secPx) / 86400).toLong()
        for (dd in day0 - 1..day0 + 2) {
            val r = hash(dd + 0.25)
            val n = if (r < 0.35) 0 else if (r < 0.8) 1 else 2
            for (i in 0 until n) {
                val t = (dd + (i + hash(dd * 7.0 + i + 0.1)) / n) * 86400
                val x = (nowX + (t - now) / secPx).toFloat()
                val s = ((0.8 + 0.5 * hash(dd * 3.0 + i + 0.7)) * scale).toFloat()
                val y = ((4 + 5 * hash(dd * 13.0 + i + 0.3)) * scale).toFloat()
                if (x < -16 * s || x > w + 4 * s) continue
                val l = daylight(elevAt(x + 6 * s))
                paint.color = mix(CLOUD_NIGHT, CLOUD_DAY, l)
                box.set(x, y, x + 12 * s, y + 3 * s)
                canvas.drawRoundRect(box, 1.5f * s, 1.5f * s, paint)
                canvas.drawCircle(x + 4 * s, y + 0.4f * s, 2.4f * s, paint)
                canvas.drawCircle(x + 7.4f * s, y, 3f * s, paint)
            }
        }

        // sleepers under the rail
        each(SLEEPER_EVERY) { x, _ ->
            flat.color = mix(SLEEPER_NIGHT, SLEEPER_DAY, daylight(elevAt(x)))
            canvas.drawRect(x, (railRow + railRows).toFloat(), x + scale * 0.9f, railRow + railRows + scale * 1.1f, flat)
        }

        // T-1800: the sun at the middle of each day, the moon at the middle of each night
        val bodies = sunMoon(now, lon, lat, utcOffset)
        for (t in bodies.first) {
            val x = (nowX + (t - now) / secPx).toFloat()
            if (x < -10 * scale || x > w + 10 * scale) continue
            val cy = 0.6f * scale
            paint.shader = RadialGradient(x, cy, 10f * scale, intArrayOf(0x66ffe27a, 0x00ffe27a), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, cy, 10f * scale, paint)
            paint.shader = null
            paint.color = 0xffffc93c.toInt(); canvas.drawCircle(x, cy, 5.2f * scale, paint)
            paint.color = 0xffffe98a.toInt(); canvas.drawCircle(x, cy, 3.6f * scale, paint)
        }
        for (t in bodies.second) {
            val x = (nowX + (t - now) / secPx).toFloat()
            if (x < -10 * scale || x > w + 10 * scale) continue
            val r = 4.6f * scale
            paint.shader = RadialGradient(x, 0f, 8f * scale, intArrayOf(0x33fff6d0, 0x00fff6d0), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, 0f, 8f * scale, paint)
            paint.shader = null
            crescent.reset(); cut.reset()
            crescent.addCircle(x, 0f, r, Path.Direction.CW)
            cut.addCircle(x + 2.3f * scale, -1.1f * scale, 4f * scale, Path.Direction.CW)
            crescent.op(cut, Path.Op.DIFFERENCE)
            paint.color = 0xfff8f1d2.toInt()
            canvas.drawPath(crescent, paint)
        }

        // the train at now: the present-time marker, nose to the right, a headlamp after dark
        val l = daylight(sunElevation(now, lon, lat, utcOffset))
        val len = TRAIN_LEN * scale; val th = TRAIN_H * scale
        val tx = nowX - len / 2; val top = railRow - th
        if (l < 0.6f) {
            val a = ((1 - l / 0.6f) * 0x88).toInt()
            paint.shader = RadialGradient(tx + len, top + th * 0.65f, 7f * scale,
                intArrayOf(Color.argb(a, 255, 226, 140), Color.argb(0, 255, 226, 140)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(tx + len, top + th * 0.65f, 7f * scale, paint)
            paint.shader = null
        }
        paint.color = mix(0xff9c3a33.toInt(), 0xffe2574a.toInt(), l)
        crescent.reset()
        crescent.addRoundRect(tx, top, tx + len, top + th,
            floatArrayOf(scale, scale, 2.6f * scale, 2.6f * scale, .5f * scale, .5f * scale, .5f * scale, .5f * scale), Path.Direction.CW)
        canvas.drawPath(crescent, paint)
        paint.color = mix(0xff7a2a25.toInt(), 0xffb8423a.toInt(), l)
        canvas.drawRect(tx + 0.4f * scale, top + th - 1.1f * scale, tx + len - 0.4f * scale, top + th - 0.5f * scale, paint)
        paint.color = if (l > 0.5f) 0xfffff6e0.toInt() else 0xffffd98a.toInt()
        for (i in 0 until 4) {
            box.set(tx + (1.6f + i * 3.6f) * scale, top + 1.1f * scale, tx + (4f + i * 3.6f) * scale, top + 2.7f * scale)
            canvas.drawRoundRect(box, .35f * scale, .35f * scale, paint)
        }

        // each hour's demand level under the scene, the colour under the train the level now
        val bandTop = h - bandH
        var x0 = 0
        while (x0 < w) {
            val c = DEMAND[demandAt(now + (x0 + 0.5 - nowX) * secPx)]
            var x1 = x0 + 1
            while (x1 < w && DEMAND[demandAt(now + (x1 + 0.5 - nowX) * secPx)] == c) x1++
            flat.color = c
            canvas.drawRect(x0.toFloat(), bandTop, x1.toFloat(), h.toFloat(), flat)
            x0 = x1
        }
        flat.color = 0x55ffffff
        canvas.drawRect(0f, skyRows.toFloat(), w.toFloat(), bandTop, flat)
        canvas.restore()
    }

    // --- the sun ---------------------------------------------------------------------------

    private var bodiesKey = ""
    private var bodiesAt = Double.NaN
    private var bodies: Pair<List<Double>, List<Double>> = emptyList<Double>() to emptyList()

    /** T-1800: the middles of each day (the sun above -3 degrees) and night near [now], as on the web. */
    private fun sunMoon(now: Double, lon: Double, lat: Double, off: Double): Pair<List<Double>, List<Double>> {
        val key = "$lon $lat $off"
        if (key == bodiesKey && abs(now - bodiesAt) < 6 * 3600) return bodies
        val sun = ArrayList<Double>(); val moon = ArrayList<Double>()
        val t0 = floor((now - BODY_SPAN) / BODY_STEP) * BODY_STEP
        var start = Double.NaN; var was = false
        var hi = doubleArrayOf(-99.0, 0.0); var lo = doubleArrayOf(99.0, 0.0)
        val days = ArrayList<Pair<DoubleArray, DoubleArray>>()
        var day = floor(t0 / 86400)
        var t = t0
        while (t <= now + BODY_SPAN) {
            val e = sunElevation(t, lon, lat, off); val up = e > -3
            if (floor(t / 86400) != day) { days.add(hi to lo); hi = doubleArrayOf(-99.0, 0.0); lo = doubleArrayOf(99.0, 0.0); day = floor(t / 86400) }
            if (e > hi[0]) hi = doubleArrayOf(e, t)
            if (e < lo[0]) lo = doubleArrayOf(e, t)
            if (t > t0 && up != was) {
                if (!start.isNaN()) (if (was) sun else moon).add((start + t - BODY_STEP) / 2)
                start = t
            }
            was = up
            t += BODY_STEP
        }
        days.add(hi to lo)
        if (sun.isEmpty()) for (d in days) if (d.first[0] > -3) sun.add(d.first[1])
        if (moon.isEmpty()) for (d in days) if (d.second[0] <= -3) moon.add(d.second[1])
        bodiesKey = key; bodiesAt = now; bodies = sun to moon
        return bodies
    }

    private companion object {
        const val HOURS = 24
        const val NOW_AT = 0.5f
        /** web px: the scene, gap and band; everything below is in the web's px times `scale` */
        const val SKY = 18f
        const val GAP = 0.6f
        const val BAND = 2.2f
        const val STAR_LOW = 10.0
        const val RAIL_Y = 15.5f
        const val SLEEPER_EVERY = 3f
        const val STAR_EVERY = 7f
        const val LIGHT_EVERY = 4f
        const val HILL_EVERY = 14f
        const val TRAIN_LEN = 18f
        const val TRAIN_H = 5f
        const val BODY_STEP = 600.0
        const val BODY_SPAN = 36 * 3600.0
        /** registry clockUtcOffset (app/src/game/localTime.ts, sim/src/track/localtime.rs) */
        const val CLOCK_UTC_OFFSET = -5.0
        const val RAD = Math.PI / 180

        /** T-882's flat colours (night #2c3d78, dusk pink, warm sunrise, day #9fd8f7) split into the
         *  top of the sky and the horizon.
         *  Spread wider than the web's (-6..5 degrees) and warm mostly at the horizon, so dawn and dusk
         *  read as a glow rather than a stripe. */
        val TOP = arrayOf(-90.0 to 0xff1a2554.toInt(), -12.0 to 0xff1f2c66.toInt(), -5.0 to 0xff3b4285.toInt(),
            1.0 to 0xff7d7cbc.toInt(), 12.0 to 0xff6fbff0.toInt(), 90.0 to 0xff67b9ee.toInt())
        val HORIZON = arrayOf(-90.0 to 0xff34478a.toInt(), -12.0 to 0xff3a4c90.toInt(), -5.0 to 0xffc98bb8.toInt(),
            1.0 to 0xffffb47a.toInt(), 12.0 to 0xffc6ebfc.toInt(), 90.0 to 0xffc9ecfc.toInt())
        const val HILL_NIGHT = 0xff1c2650.toInt()
        const val HILL_DAY = 0xff8cc3a6.toInt()
        const val GROUND_NIGHT = 0xff1d2a3a.toInt()
        const val GROUND_DAY = 0xff86bf72.toInt()
        const val RAIL_NIGHT = 0xff0f1620.toInt()
        const val RAIL_DAY = 0xff5c4c44.toInt()
        const val SLEEPER_NIGHT = 0xff141c27.toInt()
        const val SLEEPER_DAY = 0xff6a5a50.toInt()
        const val CLOUD_NIGHT = 0xff55639a.toInt()
        const val CLOUD_DAY = 0xffffffff.toInt()
        /** --dl-high, --dl-medium, --dl-low (app/src/ui/style.css) */
        val DEMAND = intArrayOf(0xffd64545.toInt(), 0xffe5b22a.toInt(), 0xff3f7fd4.toInt())

        /** periods.ts: 06-20 high, 20-24 medium, 00-06 low */
        fun demandAt(t: Double) = when (localHour(t)) { in 6..19 -> 0; in 20..23 -> 1; else -> 2 }
        fun localHour(t: Double) = Math.floorMod(floor(t / 3600).toLong(), 24L).toInt()

        fun ramp(stops: Array<Pair<Double, Int>>, e: Double): Int {
            var i = 1
            while (i < stops.size - 1 && e > stops[i].first) i++
            val (e0, c0) = stops[i - 1]; val (e1, c1) = stops[i]
            return mix(c0, c1, ((e - e0) / (e1 - e0)).coerceIn(0.0, 1.0).toFloat())
        }
        /** 0 at night to 1 by day: how lit the ground is */
        fun daylight(e: Double) = ((e + 5) / 10).coerceIn(0.0, 1.0).toFloat()
        fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
        fun hash(k: Double): Double { val s = sin(k * 12.9898) * 43758.5453; return s - floor(s) }
        fun hash(k: Long) = hash(k.toDouble())
        /** smooth 0..1 noise along time (in hill steps), so the hills slide with the sky */
        fun hillNoise(u: Double): Float {
            fun smooth(v: Double, salt: Double): Double {
                val k = floor(v); val f = v - k
                val s = (1 - cos(f * Math.PI)) / 2
                return hash(k + salt) * (1 - s) + hash(k + 1 + salt) * s
            }
            return (0.65 * smooth(u, 0.17) + 0.35 * smooth(u * 2.7, 0.61)).toFloat()
        }

        /** sun.ts: the sun's elevation in degrees at local time [t] (s) at (lon, lat). */
        fun sunElevation(t: Double, lon: Double, lat: Double, utcOffset: Double): Double {
            val day = floor(t / 86400)
            val doy = Math.floorMod((day.toLong() - 1 + 26), 365L).toDouble()
            val dec = -23.44 * RAD * cos((2 * Math.PI / 365) * (doy + 10))
            val b = (2 * Math.PI / 365) * (doy - 81)
            val eot = (9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)) / 60
            val solar = t / 3600 - day * 24 + (lon / 15 - utcOffset + eot)
            val h = (solar - 12) * 15 * RAD; val phi = lat * RAD
            return asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)) / RAD
        }
    }
}
