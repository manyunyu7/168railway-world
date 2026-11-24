// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Faces rising from platforms as riders get off a train (web map/faces.ts, T-686), from the
 * platforms' zoom under Tampilan peta's "Wajah penumpang" switch (web `display.faces`, on): a happy
 * one, a tired one when the platform's riders waited long, a sad one when it is crowded
 * (station_life.rs decides, as web stationLife.ts), a few with a rare look (T-1154), skin tone and
 * hair by the station's country (web faceTones.ts, T-1907). Each look is drawn once into a bitmap;
 * the faces are drawn over the network each frame while one is alive, and only then: with none
 * there is no work at all and no frame asked for. Nothing is allocated per frame.
 */
internal object FaceLayer {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun country(zone: String): String

    const val HAPPY = 0
    const val TIRED = 1
    const val SAD = 2

    /** Web stationLife `RIDERS_PER_FACE`, `MAX_FACES_PER_TRAIN`. */
    private const val RIDERS_PER_FACE = 20.0
    private const val MAX_FACES_PER_TRAIN = 4
    /** Faces alive at once, all platforms together, and on one platform. */
    private const val MAX_ALIVE = 36
    private const val MAX_PER_PLATFORM = 5
    /** A face's life, ms, and how far it rises, dp. */
    private const val LIFE_MS = 2000L
    private const val RISE = 30f
    /** Faces on one platform side sit in this many places along it, kept apart (T-2751). */
    private const val SLOTS = 5
    /** A face starts this share of the box's half width out from its centre line, on the riders' side. */
    private const val ACROSS = .55f
    /** The sprite's drawing units (web SPRITE): a face's size is that of a 48-unit sprite. */
    private const val SPRITE = 64f
    private const val SPRITE_SCALE = SPRITE / 48f
    private const val CHILD_SCALE = .72f
    /** Without a drawn box: where faces spread along the stop, frame units either way, and their offset, dp. */
    private const val LOOSE_HALF = 60f
    private const val LOOSE_OUT = 6f

    fun enabled(prefs: SharedPreferences?) = prefs?.getBoolean("faces", true) ?: false

    private class Face {
        var node = 0
        var sideKey = 0
        var slot = 0
        /** world anchor (frame units, y north) and the unit normal out towards the riders */
        var x = 0f; var y = 0f; var nx = 0f; var ny = 0f
        var glyph: StudioStationGlyph? = null
        var born = 0L
        var sway = 0f
        var child = false
        var sprite: Bitmap? = null
    }

    private val faces = Array(MAX_ALIVE) { Face() }
    private var alive = 0
    private val sprites = HashMap<String, Bitmap>()
    private val countries = HashMap<Int, String>()
    private val cam = FloatArray(4)
    private val spot = FloatArray(4)
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Drop every face (the switch turned off, or the view left the platforms' zoom). */
    fun clear() { alive = 0 }

    /** Web stationLife `arrive`: [off] riders getting off at [node] (the stop's world position
     *  [x], [y], its riders' side ([rx], [ry]), 0, 0 unknown) raise up to four faces. */
    fun arrive(map: NetworkView, node: Int, x: Float, y: Float, rx: Float, ry: Float, off: Double, mood: Int) {
        val count = min(MAX_FACES_PER_TRAIN, floor(off / RIDERS_PER_FACE + Math.random()).toInt())
        if (count > 0) spawn(map, node, x, y, rx, ry, count, mood)
    }

    private fun on(node: Int): Int {
        var n = 0
        for (i in 0 until alive) if (faces[i].node == node) n++
        return n
    }

    private fun spawn(map: NetworkView, node: Int, x: Float, y: Float, rx: Float, ry: Float, count: Int, mood: Int) {
        val room = min(count, min(MAX_PER_PLATFORM - on(node), MAX_ALIVE - alive))
        if (room <= 0) return
        var station: NetworkView.Station? = null
        for (s in map.stops) if (s.id == node) { station = s; break }
        val glyph = station?.platform
        // the riders' side of the box: the half its normal shares with theirs (web platformSpot)
        var side = if (Math.random() < .5) 1f else -1f
        if (glyph != null && (rx != 0f || ry != 0f)) {
            glyph.along(.5f, spot)
            side = if (spot[2] * rx + spot[3] * ry >= 0) 1f else -1f
        }
        val sideKey = node * 2 + if (side > 0) 1 else 0
        val cc = countryOf(map, node, x, y)
        val density = map.resources.displayMetrics.density
        val used = IntArray(SLOTS)
        for (i in 0 until alive) if (faces[i].sideKey == sideKey) used[faces[i].slot]++
        val now = SystemClock.uptimeMillis()
        repeat(room) { i ->
            // the least used place, at random among equals
            val least = used.minOrNull() ?: 0
            var ties = 0
            for (u in used) if (u == least) ties++
            var pick = (Math.random() * ties).toInt()
            var slot = 0
            for (k in 0 until SLOTS) if (used[k] == least && pick-- == 0) { slot = k; break }
            used[slot]++
            // places spread over the middle 80% of the box, a little jitter within each
            val t = (.1f + .8f * (slot + .5f + (Math.random().toFloat() - .5f) * .4f) / SLOTS).coerceIn(0f, 1f)
            val f = faces[alive++]
            f.node = node; f.sideKey = sideKey; f.slot = slot; f.glyph = glyph
            if (glyph != null && station != null) {
                glyph.along(t, spot)
                f.x = station.x + spot[0]; f.y = station.y + spot[1]
                f.nx = spot[2] * side; f.ny = spot[3] * side
            } else {
                // no box drawn for it: along the track at the stop, out on the riders' side
                f.x = x + ry * (t - .5f) * 2 * LOOSE_HALF; f.y = y - rx * (t - .5f) * 2 * LOOSE_HALF
                f.nx = rx; f.ny = ry
            }
            f.born = now + i * 160L + (Math.random() * 220).toLong()
            f.sway = (Math.random().toFloat() * 2 - 1) * 5 * density
            val look = Looks.pick(mood, cc)
            f.child = look.child
            f.sprite = sprites.getOrPut(look.key) { Looks.sprite(look, spriteSide(density)) }
        }
        map.animateFaces()
    }

    /** The bitmap's side, px: the largest face drawn (20 dp, web's at zoom 17) at full resolution. */
    private fun spriteSide(density: Float) = ceil(20f * SPRITE_SCALE * density).toInt().coerceAtLeast(32)

    /** The station's country (T-1907), looked up at its first face. */
    private fun countryOf(map: NetworkView, node: Int, x: Float, y: Float): String =
        countries.getOrPut(node) {
            val h = map.trainEngine()
            val zones = map.zones
            if (h == 0L || zones == null) "" else runCatching {
                val ll = CitySurface.lonLat(h, x, y)
                zones.place(ll[0], ll[1])?.let { country(it) } ?: ""
            }.getOrDefault("")
        }

    /** Over the network, each frame while a face is alive; asks for the next frame itself. */
    fun draw(map: NetworkView, c: Canvas) {
        if (alive == 0) return
        val now = SystemClock.uptimeMillis()
        var k = 0
        for (i in 0 until alive) {
            val f = faces[i]
            if (now - f.born < LIFE_MS) { if (k != i) { faces[i] = faces[k]; faces[k] = f }; k++ }
        }
        alive = k
        if (alive == 0) return
        map.faceCamera(cam)
        val cx = cam[0]; val cy = cam[1]; val scale = cam[2]; val mercator = cam[3]
        val density = map.resources.displayMetrics.density
        val hw = map.width / 2f; val hh = map.height / 2f
        // a little larger the closer the view, from 14 dp at the platforms' zoom to 20 dp
        val size = (14f + 6f * ((map.zoomLevel() - 14f) / 3f).coerceIn(0f, 1f)) * density
        for (i in 0 until alive) {
            val f = faces[i]
            val u = (now - f.born).toFloat() / LIFE_MS
            if (u < 0f) continue
            val bmp = f.sprite ?: continue
            val out = ACROSS * (f.glyph?.halfPx(scale, density, mercator) ?: (LOOSE_OUT * density / ACROSS))
            val px = (f.x - cx) * scale + hw + f.nx * out
            val py = (cy - f.y) * scale + hh - f.ny * out
            val ease = 1 - (1 - u) * (1 - u)
            val alpha = min(1f, u * 8) * (if (u < .55f) 1f else 1 - (u - .55f) / .45f) * .95f
            val fx = px + f.sway * sin(u * PI.toFloat() * 2)
            val fy = py - RISE * density * ease - 4 * density
            val s = size * SPRITE_SCALE * (if (f.child) CHILD_SCALE else 1f)
            // the face sits 4 sprite units under the sprite's centre
            val top = fy - s / 2 - 4 * s / SPRITE
            dst.set(fx - s / 2, top, fx + s / 2, top + s)
            paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
            c.drawBitmap(bmp, null, dst, paint)
        }
        paint.alpha = 255
        map.animateFaces()
    }

    // ---- looks (web faces.ts pickLook, sprite; faceTones.ts) ---------------------------------
    internal class Look(val mood: Int, val expr: Int, val acc: Int, val child: Boolean, val hair: Int, val tint: Int, val skin: Int) {
        val key = "$mood|$expr|$acc|$hair${if (hair != 0) tint.toString() else ""}|$skin"
    }

    internal object Looks {
        // expressions, accessories, hair
        const val FLAT = 1; const val SLEEPY = 2; const val CRYING = 3; const val DEAD = 4
        const val BUCKET = 1; const val CAP = 2; const val SHADES = 3
        const val SHORT = 1; const val LONG = 2; const val PONYTAIL = 3; const val WAVY = 4
        private val EXPR_CHANCE = doubleArrayOf(0.0, .04, .04, .08, .03)
        private val ACC_CHANCE = doubleArrayOf(0.0, .04, .04, .03)
        private val HAIR_CHANCE = doubleArrayOf(0.0, .14, .12, .08, .1)
        private const val CHILD_CHANCE = .06

        // No red: a red face reads as angry (T-802). Tired is a drained yellow, sad is blue.
        private val LOOK = arrayOf(
            intArrayOf(0xffe2a0, 0xe2b45e, 0x74513c),
            intArrayOf(0xe4dcc0, 0xb3a77c, 0x5f5640),
            intArrayOf(0xb9d5f2, 0x6f9ed0, 0x2f4d78),
        )
        private val HAIR = arrayOf(intArrayOf(0x5a3d2b, 0x3a2519), intArrayOf(0x34303b, 0x1c1922), intArrayOf(0xb8602e, 0x8a4519), intArrayOf(0xd9a441, 0xa97c24))
        private val HATS = arrayOf(intArrayOf(0xa9d8b8, 0x5f9e76), intArrayOf(0xf4a983, 0xc9714d))
        /** T-1907: a skin-toned face keeps a hint of its mood, the tired greyer and the sad bluer. */
        private val MOOD_TO = intArrayOf(0, 0xcfc8a8, 0x78aef0)
        private val MOOD_T = floatArrayOf(0f, .35f, .45f)

        /** Skin tones, lightest first: fill, edge, ink (faceTones `SKIN`). */
        val SKIN = arrayOf(
            intArrayOf(0xf7dcc6, 0xd9b08f, 0x6b4a3a), intArrayOf(0xeccaa6, 0xc99e74, 0x5e402f),
            intArrayOf(0xd9ab80, 0xb0814f, 0x4e3424), intArrayOf(0xbf8b60, 0x93653d, 0x3f291b),
            intArrayOf(0x9a6a47, 0x714a2d, 0x2e1e14), intArrayOf(0x71503a, 0x4f3524, 0x24170f),
        )

        private class Profile(val skin: DoubleArray, val hair: DoubleArray, val wavy: Double = 1.0)
        private val P = mapOf(
            "eastAsia" to Profile(doubleArrayOf(3.0, 5.0, 2.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 9.0, 0.0, 0.0), .25),
            "southeastAsia" to Profile(doubleArrayOf(1.0, 3.0, 4.0, 2.0, .5, 0.0), doubleArrayOf(.5, 9.5, 0.0, 0.0), .4),
            "southAsia" to Profile(doubleArrayOf(0.0, .5, 2.5, 4.0, 2.5, .5), doubleArrayOf(.5, 9.5, 0.0, 0.0)),
            "middleEast" to Profile(doubleArrayOf(1.0, 3.5, 3.5, 1.5, .5, 0.0), doubleArrayOf(3.0, 6.8, 0.0, .2)),
            "northEurope" to Profile(doubleArrayOf(5.5, 3.5, .7, .2, .1, 0.0), doubleArrayOf(5.0, 1.2, .6, 3.2), 1.8),
            "southEurope" to Profile(doubleArrayOf(3.5, 4.5, 1.5, .3, .2, 0.0), doubleArrayOf(6.0, 3.0, .3, .7), 1.8),
            "africa" to Profile(doubleArrayOf(0.0, 0.0, .2, 1.3, 4.0, 4.5), doubleArrayOf(.2, 9.8, 0.0, 0.0), 1.8),
            "southAfrica" to Profile(doubleArrayOf(.8, .4, .6, 1.2, 3.5, 3.5), doubleArrayOf(1.2, 8.2, .1, .5), 1.8),
            "northAmerica" to Profile(doubleArrayOf(4.0, 2.0, 1.5, 1.0, 1.0, .5), doubleArrayOf(4.5, 3.5, .4, 1.6), 1.6),
            "latinAmerica" to Profile(doubleArrayOf(1.5, 3.0, 3.0, 1.5, .8, .2), doubleArrayOf(5.5, 4.0, .1, .4)),
            "brazil" to Profile(doubleArrayOf(2.0, 2.5, 2.0, 1.5, 1.2, .8), doubleArrayOf(5.0, 4.4, .1, .5), 1.5),
            "southernCone" to Profile(doubleArrayOf(3.5, 4.0, 1.5, .5, .3, .2), doubleArrayOf(6.0, 3.0, .2, .8), 1.5),
            "oceania" to Profile(doubleArrayOf(4.5, 2.5, 1.2, .8, .6, .4), doubleArrayOf(4.5, 2.5, .5, 2.5), 1.6),
        )
        private val COUNTRIES = mapOf(
            "eastAsia" to "JP KR KP CN TW HK MO MN",
            "southeastAsia" to "VN TH KH LA MM MY SG ID PH BN TL",
            "southAsia" to "IN PK BD LK NP BT MV",
            "middleEast" to "TR IR IQ SA AE QA KW BH OM YE JO LB SY IL PS EG LY TN DZ MA AF AZ AM GE KZ UZ TM KG TJ",
            "northEurope" to "GB IE DE NL BE LU DK SE NO FI IS PL CZ SK AT CH HU EE LV LT RU UA BY MD",
            "southEurope" to "FR ES PT IT GR HR SI RS BA ME MK AL XK BG RO CY MT AD MC SM",
            "africa" to "NG GH KE ET TZ UG RW CD CG CM CI SN ML BF NE TD SD SS SO AO ZM ZW MZ MW MG BJ TG GN SL LR GA BW NA LS SZ ER DJ GM GW MR BI CF GQ",
            "southAfrica" to "ZA",
            "northAmerica" to "US CA PR",
            "latinAmerica" to "MX GT HN SV NI CR PA CU DO HT JM TT CO VE EC PE BO PY",
            "brazil" to "BR",
            "southernCone" to "AR CL UY",
            "oceania" to "AU NZ",
        )
        private val OF: Map<String, Profile> = HashMap<String, Profile>().apply {
            for ((p, list) in COUNTRIES) for (cc in list.split(' ')) put(cc, P.getValue(p))
        }

        /** A draw from [weights] with [u] in [0, 1). */
        private fun drawFrom(weights: DoubleArray, u: Double): Int {
            var t = u * weights.sum()
            for (i in weights.indices) { t -= weights[i]; if (t < 0) return i }
            return weights.size - 1
        }

        /** Web pickLook: [country] the station's ISO code, "" for none (the mood's own colour). */
        fun pick(mood: Int, country: String, rand: () -> Double = Math::random): Look {
            var expr = 0; var acc = 0
            val re = rand(); val ra = rand()
            val exprs = if (mood == SAD) intArrayOf(CRYING, DEAD) else intArrayOf(FLAT, SLEEPY)
            var t = 0.0
            for (e in exprs) { t += EXPR_CHANCE[e]; if (expr == 0 && re < t) expr = e }
            t = 0.0
            for (a in BUCKET..SHADES) { t += ACC_CHANCE[a]; if (acc == 0 && ra < t) acc = a }
            if (acc == SHADES && (expr == DEAD || expr == SLEEPY || expr == CRYING)) acc = 0
            val child = rand() < CHILD_CHANCE
            var hair = 0
            val rh = rand(); val us = rand(); val uh = rand()
            val p = OF[country]
            val skin = if (p == null) -1 else drawFrom(p.skin, us)
            val tint = if (p == null) floor(uh * HAIR.size).toInt() else drawFrom(p.hair, uh)
            val wavy = p?.wavy ?: 1.0
            if (acc == 0 || acc == SHADES) {
                t = 0.0
                for (k in SHORT..WAVY) { t += HAIR_CHANCE[k] * (if (k == WAVY) wavy else 1.0); if (hair == 0 && rh < t) hair = k }
            }
            return Look(mood, expr, acc, child, hair, tint, skin)
        }

        /** `a` mixed towards `b` by `t`. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).roundToInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Fill, edge and ink of a look (web faceColours), as RGB. */
        fun colours(l: Look): IntArray {
            val s = SKIN.getOrNull(l.skin) ?: return LOOK[l.mood]
            if (l.mood == HAPPY) return s
            val to = MOOD_TO[l.mood]; val t = MOOD_T[l.mood]
            return intArrayOf(mix(s[0], to, t), mix(s[1], to, t * .6f), s[2])
        }

        private fun rgb(c: Int, alpha: Int = 255) = Color.argb(alpha, c shr 16 and 255, c shr 8 and 255, c and 255)
        private const val DEG = 180f / PI.toFloat()

        /** One face drawn into a [side]-px square, as web `sprite` draws it in 64 units: its centre
         *  32 across and 36 down, leaving room above for a hat. */
        fun sprite(l: Look, side: Int): Bitmap {
            val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val g = Canvas(bmp)
            g.scale(side / SPRITE, side / SPRITE)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val path = Path()
            val oval = RectF()
            val mx = SPRITE / 2; val my = SPRITE / 2 + 4
            val (f, e, ink) = colours(l).let { Triple(it[0], it[1], it[2]) }
            fun shape(fc: Int, ec: Int, w: Float = 2.4f) { fill.color = rgb(fc); g.drawPath(path, fill); line.color = rgb(ec); line.strokeWidth = w; g.drawPath(path, line) }
            fun arc(x: Float, y: Float, r: Float, a0: Float, a1: Float, p: Paint) { oval.set(x - r, y - r, x + r, y + r); g.drawArc(oval, a0 * DEG, (a1 - a0) * DEG, false, p) }
            fun ellipse(x: Float, y: Float, rx: Float, ry: Float, p: Paint) { oval.set(x - rx, y - ry, x + rx, y + ry); g.drawOval(oval, p) }
            val pi = PI.toFloat()
            val h = if (l.hair != 0) HAIR[l.tint % HAIR.size] else null
            // hair behind the head
            if (h != null) {
                path.rewind()
                when (l.hair) {
                    LONG -> {
                        path.moveTo(mx - 24, my + 22); path.lineTo(mx - 24, my - 2)
                        path.cubicTo(mx - 24, my - 32, mx + 24, my - 32, mx + 24, my - 2)
                        path.lineTo(mx + 24, my + 22); path.quadTo(mx + 18, my + 26, mx + 13, my + 19)
                        path.lineTo(mx - 13, my + 19); path.quadTo(mx - 18, my + 26, mx - 24, my + 22); path.close()
                    }
                    PONYTAIL -> {
                        path.moveTo(mx + 13, my - 12); path.cubicTo(mx + 22, my - 12, mx + 25, my - 2, mx + 22, my + 12)
                        path.quadTo(mx + 17, my + 8, mx + 12, my + 2); path.close()
                    }
                    WAVY -> {
                        path.moveTo(mx - 22, my + 18); path.quadTo(mx - 29, my + 12, mx - 24, my + 6); path.quadTo(mx - 29, my, mx - 24, my - 6)
                        path.cubicTo(mx - 26, my - 32, mx + 26, my - 32, mx + 24, my - 6)
                        path.quadTo(mx + 29, my, mx + 24, my + 6); path.quadTo(mx + 29, my + 12, mx + 22, my + 18)
                        path.lineTo(mx - 22, my + 18); path.close()
                    }
                }
                if (!path.isEmpty) shape(h[0], h[1])
            }
            // the head
            fill.color = rgb(f); g.drawCircle(mx, my, FACE_R, fill)
            line.color = rgb(e); line.strokeWidth = 3f; g.drawCircle(mx, my, FACE_R, line)
            // the hair over the forehead
            if (h != null) {
                path.rewind()
                path.moveTo(mx - 20, my - 3); path.cubicTo(mx - 24, my - 30, mx + 24, my - 30, mx + 20, my - 3)
                when (l.hair) {
                    WAVY -> {
                        path.quadTo(mx + 15, my - 14, mx + 10, my - 8); path.quadTo(mx + 5, my - 16, mx, my - 9)
                        path.quadTo(mx - 5, my - 16, mx - 10, my - 8); path.quadTo(mx - 15, my - 14, mx - 20, my - 3)
                    }
                    LONG -> {
                        path.quadTo(mx + 15, my - 9, mx + 3, my - 15); path.lineTo(mx - 3, my - 15); path.quadTo(mx - 15, my - 9, mx - 20, my - 3)
                    }
                    else -> {
                        path.quadTo(mx + 17, my - 10, mx + 9, my - 12); path.quadTo(mx + 4, my - 17, mx - 2, my - 12)
                        path.quadTo(mx - 8, my - 16, mx - 12, my - 10); path.quadTo(mx - 17, my - 9, mx - 20, my - 3)
                    }
                }
                path.close()
                shape(h[0], h[1])
                if (l.hair == LONG) g.drawLine(mx, my - 27, mx, my - 15, line)
            }
            fill.color = rgb(ink); line.color = rgb(ink); line.strokeWidth = 3f
            val shades = l.acc == SHADES
            if (l.expr != 0) when (l.expr) {
                FLAT -> {
                    if (!shades) for (x in floatArrayOf(-8f, 8f)) g.drawLine(mx + x - 3.5f, my - 4, mx + x + 3.5f, my - 4, line)
                    g.drawLine(mx - 7, my + 9, mx + 7, my + 9, line)
                }
                SLEEPY -> {
                    line.strokeWidth = 2.6f
                    for (x in floatArrayOf(-8f, 8f)) arc(mx + x, my - 5, 4f, pi * .15f, pi * .85f, line)
                    ellipse(mx, my + 9, 2.6f, 3.2f, fill)
                    if (l.acc == 0) {
                        line.strokeWidth = 2f
                        path.rewind(); path.moveTo(mx + 15, my - 22); path.lineTo(mx + 21, my - 22); path.lineTo(mx + 15, my - 16); path.lineTo(mx + 21, my - 16)
                        g.drawPath(path, line)
                    }
                }
                CRYING -> {
                    line.strokeWidth = 2.4f
                    g.drawLine(mx - 14, my - 9, mx - 5, my - 13, line); g.drawLine(mx + 14, my - 9, mx + 5, my - 13, line)
                    line.strokeWidth = 2.6f
                    for (x in floatArrayOf(-8f, 8f)) arc(mx + x, my - 1, 3.6f, pi * 1.15f, pi * 1.85f, line)
                    fill.color = rgb(0xeef7ff)
                    for (x in floatArrayOf(-9f, 9f)) {
                        path.rewind(); path.moveTo(mx + x - 1.8f, my); path.lineTo(mx + x - 2.4f, my + 14)
                        path.quadTo(mx + x, my + 17, mx + x + 2.4f, my + 14); path.lineTo(mx + x + 1.8f, my); path.close()
                        g.drawPath(path, fill)
                    }
                    fill.color = rgb(ink)
                    ellipse(mx, my + 10, 5f, 4f, fill)
                }
                DEAD -> {
                    line.strokeWidth = 2.6f
                    for (x in floatArrayOf(-8f, 8f)) {
                        g.drawLine(mx + x - 3.4f, my - 7.4f, mx + x + 3.4f, my - .6f, line)
                        g.drawLine(mx + x + 3.4f, my - 7.4f, mx + x - 3.4f, my - .6f, line)
                    }
                    fill.color = rgb(0xf29bab)
                    ellipse(mx + 3, my + 11.5f, 3.4f, 4f, fill)
                    path.rewind(); path.moveTo(mx - 7, my + 9); path.quadTo(mx - 3.5f, my + 6.5f, mx, my + 9); path.quadTo(mx + 3.5f, my + 11.5f, mx + 7, my + 9)
                    g.drawPath(path, line)
                }
            } else when (l.mood) {
                TIRED -> {
                    // heavy lids over half-closed eyes, bags under them, a flat mouth
                    if (!shades) {
                        for (dx in floatArrayOf(-8f, 8f)) {
                            oval.set(mx + dx - 3.6f, my - 3 - 3.6f, mx + dx + 3.6f, my - 3 + 3.6f)
                            g.drawArc(oval, 0f, 180f, false, fill)
                            line.strokeWidth = 2.6f
                            g.drawLine(mx + dx - 5.5f, my - 3, mx + dx + 5.5f, my - 3, line)
                        }
                        line.strokeWidth = 1.8f; line.color = rgb(e)
                        for (dx in floatArrayOf(-8f, 8f)) arc(mx + dx, my + 1, 5f, pi * .2f, pi * .8f, line)
                        line.color = rgb(ink)
                    }
                    line.strokeWidth = 3f
                    g.drawLine(mx - 6, my + 13, mx + 6, my + 13, line)
                }
                SAD -> {
                    // brows raised at the inner ends, round eyes, a small frown and a tear
                    if (!shades) {
                        line.strokeWidth = 2.4f
                        g.drawLine(mx - 14, my - 6, mx - 5, my - 11, line); g.drawLine(mx + 14, my - 6, mx + 5, my - 11, line)
                        for (dx in floatArrayOf(-8f, 8f)) g.drawCircle(mx + dx, my - 1, 2.8f, fill)
                    }
                    line.strokeWidth = 3f
                    arc(mx, my + 17, 7f, pi * 1.2f, pi * 1.8f, line)
                    fill.color = Color.WHITE
                    ellipse(mx + 11, my + 7, 2.4f, 3.4f, fill)
                }
                else -> {
                    if (!shades) for (dx in floatArrayOf(-8f, 8f)) g.drawCircle(mx + dx, my - 4, 2.8f, fill)
                    arc(mx, my + 3, 9f, pi * .15f, pi * .85f, line)
                    fill.color = rgb(0xf48282, 115)
                    for (dx in floatArrayOf(-14f, 14f)) g.drawCircle(mx + dx, my + 4, 3.6f, fill)
                }
            }
            when (l.acc) {
                SHADES -> {
                    fill.color = rgb(0x33313f); line.color = rgb(0x33313f); line.strokeWidth = 2f
                    for (x in floatArrayOf(-8f, 8f)) ellipse(mx + x, my - 4, 6.4f, 4.6f, fill)
                    g.drawLine(mx - 2, my - 5, mx + 2, my - 5, line)
                    g.drawLine(mx - 14, my - 5, mx - 19, my - 7, line); g.drawLine(mx + 14, my - 5, mx + 19, my - 7, line)
                    fill.color = Color.argb(179, 255, 255, 255)
                    for (x in floatArrayOf(-10f, 6f)) {
                        g.save(); g.rotate(-.5f * DEG, mx + x, my - 6); ellipse(mx + x, my - 6, 1.6f, 1.1f, fill); g.restore()
                    }
                }
                BUCKET -> {
                    val (hf, he) = HATS[0].let { it[0] to it[1] }
                    // a rounded crown over a wide drooping brim
                    path.rewind(); path.moveTo(mx - 15, my - 13); path.lineTo(mx - 12, my - 25); path.quadTo(mx, my - 30, mx + 12, my - 25)
                    path.lineTo(mx + 15, my - 13); path.close(); shape(hf, he)
                    path.rewind(); path.moveTo(mx - 23, my - 9); path.quadTo(mx, my - 19, mx + 23, my - 9); path.quadTo(mx, my - 13, mx - 23, my - 9)
                    path.close(); shape(hf, he)
                    line.color = Color.argb(140, 255, 255, 255)
                    path.rewind(); path.moveTo(mx - 13, my - 16.5f); path.quadTo(mx, my - 19.5f, mx + 13, my - 16.5f); g.drawPath(path, line)
                }
                CAP -> {
                    val (hf, he) = HATS[1].let { it[0] to it[1] }
                    // a dome hugging the head, a bill out to the right and a button on top
                    path.rewind(); path.moveTo(mx - 19, my - 10); path.cubicTo(mx - 19, my - 30, mx + 19, my - 30, mx + 19, my - 10)
                    path.quadTo(mx, my - 14, mx - 19, my - 10); path.close(); shape(hf, he)
                    path.rewind(); path.moveTo(mx + 12, my - 12); path.quadTo(mx + 26, my - 14, mx + 29, my - 9); path.quadTo(mx + 22, my - 7, mx + 14, my - 9)
                    path.close(); shape(hf, he)
                    fill.color = rgb(he); g.drawCircle(mx, my - 25, 2.2f, fill)
                }
            }
            return bmp
        }
        private const val FACE_R = 20f
    }
}
