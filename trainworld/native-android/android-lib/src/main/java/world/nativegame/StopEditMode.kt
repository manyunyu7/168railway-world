// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.hypot

/**
 * A line's stops edited on the map, as the web's "Ubah perhentian" (ui/inspector/LineInspector.tsx
 * `toggleEditStops`, game/ui.ts `stopEditing`, map/stopMarkers.ts, map/tools.ts `toggle`/`loopStop`).
 * The line's panel closes; the line is lit over a washed-out map and every station carries a marker in
 * its tier from the engine (`TrackApi::stop_tiers`): a stop is a disc in the line's colour, a station
 * on the route a hollow grey ring, one joined to the route by track a smaller grey one (dashed where
 * trains would turn back), one with no track to the line the palest. A tap adds the station or takes it
 * out; a hold closes, opens or turns a loop (T-3109). Each step is one undo; "Selesai" reopens the panel.
 */
internal object StopEditMode {
    @JvmStatic private external fun state(handle: Long, id: Int): String
    @JvmStatic private external fun toggle(handle: Long, id: Int, node: Int, now: Double): String
    @JvmStatic private external fun loop(handle: Long, id: Int, action: String, node: Int, now: Double): String

    /** web stopMarkers.ts SIZE: marker diameter by zoom (here dp) */
    private val SIZE = arrayOf(9f to 9f, 12f to 14f, 14f to 20f, 16f to 24f)
    private const val NEAR_SCALE = .8f
    private const val FAR_SCALE = .65f
    private const val LONG_PRESS_MS = 500L
    private const val PRESS_SLOP_DP = 8f
    private const val REASON_MS = 4000L
    private const val BUSY_MS = 4000L
    private val STOP_TIERS = setOf("on", "close", "open", "turn", "unturn")
    private val LOOP_TIERS = setOf("close", "open", "turn", "unturn")
    private val RANK = mapOf("on" to 3, "close" to 3, "open" to 3, "turn" to 3, "unturn" to 3, "route" to 2, "reroute" to 1, "reverse" to 1, "cut" to 0)
    /** web i18n toolNotes.lineHint (touch) */
    private const val HINT = "Ketuk lingkaran untuk menambah atau menghapus perhentian. Tahan salah satunya untuk memutar balik ke sana."

    /** Set by the world screen: its map, its current world (an undo swaps it) and an undo of the last edit. */
    private var map: NetworkView? = null
    private var handle: () -> Long = { 0L }
    private var undo: ((() -> Unit) -> Unit)? = null
    fun attach(map: NetworkView, handle: () -> Long, undo: (done: () -> Unit) -> Unit) {
        finish()
        this.map = map; this.handle = handle; this.undo = undo
    }
    /** The line panel offers the button only where the map can take it. */
    val available get() = map != null
    fun currentHandle() = handle()

    private class Press(val node: Int, val x: Float, val y: Float, val timer: Runnable)
    private class Tag(val lines: List<String>, val x: Float, val y: Float, val bad: Boolean, val until: Long)

    private class Session(
        val activity: Activity, val map: NetworkView, val id: Int, val name: String, val color: Int,
        val transaction: PanelEdit, val changed: (String) -> Unit, val reopen: () -> Unit,
    ) {
        var tiers = HashMap<Int, String>()
        var revAt = HashMap<Int, String>()
        var stops = listOf<Int>()
        var closed = false
        var tram = false
        var seen = 0L
        var steps = 0
        var busySince = 0L
        var press: Press? = null
        var swallow = false
        var tag: Tag? = null
        lateinit var panel: View
        lateinit var status: TextView
        lateinit var undoButton: Button
    }

    private var session: Session? = null
    val active get() = session != null

    /** "Ubah perhentian" in line `id`'s panel: the panel is closed already; `reopen` opens it again. */
    fun start(activity: Activity, id: Int, name: String, color: Int, transaction: PanelEdit, changed: (String) -> Unit, reopen: () -> Unit) {
        val map = map ?: return
        finish()
        val s = Session(activity, map, id, name, color, transaction, changed, reopen)
        if (!read(s)) { Toast.makeText(activity, "Perhentian line ini tidak bisa diubah di peta.", Toast.LENGTH_LONG).show(); reopen(); return }
        session = s
        bar(s)
        map.routeOf(id)?.let { xy ->
            if (xy.size >= 4) {
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (i in xy.indices step 2) { x0 = minOf(x0, xy[i]); x1 = maxOf(x1, xy[i]); y0 = minOf(y0, xy[i + 1]); y1 = maxOf(y1, xy[i + 1]) }
                map.post { map.showBounds(floatArrayOf(x0, y0, x1, y1)) }
            }
        }
        map.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { if (session === s) finish(); v.removeOnAttachStateChangeListener(this) }
        })
        map.invalidate()
    }

    /** Back leaves the mode for the line's panel, as "Selesai". */
    fun back(): Boolean {
        val s = session ?: return false
        done(s)
        return true
    }

    private fun done(s: Session) {
        finish()
        s.reopen()
    }

    private fun finish() {
        val s = session ?: return
        session = null
        s.press?.let { s.map.removeCallbacks(it.timer) }
        (s.panel.parent as? ViewGroup)?.removeView(s.panel)
        s.map.invalidate()
    }

    /** The engine's tiers for the line, from the world now open; false when the line cannot be edited. */
    private fun read(s: Session): Boolean {
        val h = handle()
        s.seen = h
        if (h == 0L) return false
        val r = runCatching { JSONObject(state(h, s.id)) }.getOrNull() ?: return false
        val stops = r.optJSONArray("stops") ?: return false
        s.stops = List(stops.length()) { stops.getInt(it) }
        s.closed = r.optBoolean("closed")
        s.tram = r.optBoolean("tram")
        val tiers = HashMap<Int, String>(); val rev = HashMap<Int, String>()
        val rows = r.optJSONArray("tiers")
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows!!.getJSONArray(i)
            val node = row.getInt(0)
            tiers[node] = row.getString(1)
            if (row.length() > 2) rev[node] = row.getString(2)
        }
        s.tiers = tiers; s.revAt = rev
        return true
    }

    private fun tierOf(s: Session, node: Int) = s.tiers[node] ?: "cut"

    // ---------------------------------------------------------------- the bar

    private fun bar(s: Session) {
        val a = s.activity
        fun dp(n: Int) = StudioUi.dp(a, n)
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.glass(a, 24, 0xfffbfaff.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            elevation = dp(12).toFloat()
        }
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.badge(a, ServicePanel.code(s.name), s.color, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        words.addView(StudioUi.text(a, s.name, 15f, true, StudioUi.INK).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        words.addView(StudioUi.text(a, "Ubah perhentian", 12f, true, StudioUi.MUTED))
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(head)
        s.status = StudioUi.text(a, HINT, 13f, true, StudioUi.MUTED).apply { setPadding(dp(2), dp(6), dp(2), dp(6)) }
        root.addView(s.status)
        val foot = LinearLayout(a)
        s.undoButton = StudioUi.button(a, "Urungkan", icon = StudioUi.Icon.UNDO) { undoStep(s) }
        foot.addView(s.undoButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        foot.addView(StudioUi.button(a, "Selesai", StudioUi.Tone.PRIMARY) { if (session === s) done(s) },
            LinearLayout.LayoutParams(0, dp(52), 1.4f).apply { marginStart = dp(6) })
        root.addView(foot)
        content.addView(root, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        s.panel = root
        renderBar(s)
    }

    private fun renderBar(s: Session) {
        s.undoButton.isEnabled = s.steps > 0
        s.undoButton.alpha = if (s.steps > 0) 1f else .45f
    }

    private fun say(s: Session, text: String, bad: Boolean = false) {
        s.status.text = text
        s.status.setTextColor(if (bad) StudioUi.BAD else StudioUi.MUTED)
    }

    private fun undoStep(s: Session) {
        val run = undo ?: return
        if (s.steps <= 0 || busy(s)) return
        s.busySince = SystemClock.uptimeMillis()
        run {
            s.busySince = 0L
            if (session !== s) return@run
            s.steps--
            if (!read(s)) { done(s); return@run }
            say(s, HINT)
            renderBar(s)
            s.map.invalidate()
        }
    }

    private fun busy(s: Session) = s.busySince != 0L && SystemClock.uptimeMillis() - s.busySince < BUSY_MS

    // ---------------------------------------------------------------- the map

    private fun sizeAt(zoom: Float): Float {
        if (zoom <= SIZE[0].first) return SIZE[0].second
        for (i in 1 until SIZE.size) if (zoom <= SIZE[i].first) {
            val (z0, s0) = SIZE[i - 1]; val (z1, s1) = SIZE[i]
            return s0 + (s1 - s0) * (zoom - z0) / (z1 - z0)
        }
        return SIZE.last().second
    }

    private class Mark(val node: Int, val name: String, val x: Float, val y: Float, val tier: String, val d: Float)

    /** The markers on screen now, drawn in this order (stops last, on top). */
    private fun marks(s: Session, map: NetworkView): List<Mark> {
        val d = map.resources.displayMetrics.density
        val size = sizeAt(map.zoomLevel()) * d
        val pad = size
        val out = ArrayList<Mark>()
        for (st in map.stops) {
            if (st.id < 0) continue
            val tier = s.tiers[st.id] ?: continue
            val q = map.screenOf(PointF(st.x + (st.platform?.midX ?: 0f), st.y + (st.platform?.midY ?: 0f)))
            if (q.x < -pad || q.y < -pad || q.x > map.width + pad || q.y > map.height + pad) continue
            val stop = tier in STOP_TIERS
            out.add(Mark(st.id, st.name, q.x, q.y, tier, if (stop) size else if (tier == "route") size * NEAR_SCALE else size * FAR_SCALE))
        }
        out.sortBy { RANK[it.tier] ?: 0 }
        return out
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Over the map while editing: the others washed out, the line lit, the markers and a reason's tag. */
    fun draw(map: NetworkView, c: Canvas) {
        val s = session ?: return
        if (s.map !== map) return
        if (handle() != s.seen && !read(s)) { map.post { if (session === s) done(s) }; return }
        val d = map.resources.displayMetrics.density
        // web STOP_EDIT_DIM: the other lines dimmed, this one on top
        c.drawColor(0x8cffffff.toInt())
        map.routeOf(s.id)?.let { xy ->
            if (xy.size < 4) return@let
            val path = Path()
            for (i in xy.indices step 2) { val q = map.screenOf(PointF(xy[i], xy[i + 1])); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            route.color = 0xffffffff.toInt(); route.strokeWidth = 8 * d; c.drawPath(path, route)
            route.color = s.color; route.strokeWidth = 5 * d; c.drawPath(path, route)
        }
        val pressed = s.press?.node
        for (m in marks(s, map)) {
            val r = m.d / 2
            ring.pathEffect = null
            when (m.tier) {
                in STOP_TIERS -> {
                    if (m.node == pressed) { fill.color = s.color; fill.alpha = 90; c.drawCircle(m.x, m.y, r + 7 * d, fill) }
                    fill.color = 0xff2b2b2b.toInt(); c.drawCircle(m.x, m.y, r + d, fill)
                    fill.color = 0xffffffff.toInt(); c.drawCircle(m.x, m.y, r, fill)
                    fill.color = s.color; c.drawCircle(m.x, m.y, r - 2 * d, fill)
                    continue
                }
                "route" -> { fill.color = 0xffffffff.toInt(); ring.color = 0xff7d7872.toInt() }
                "reroute" -> { fill.color = 0xcce6e3df.toInt(); ring.color = 0xcca8a39d.toInt() }
                "reverse" -> { fill.color = 0xd9e6e3df.toInt(); ring.color = 0xd9a8a39d.toInt(); ring.pathEffect = DashPathEffect(floatArrayOf(2.5f * d, 2f * d), 0f) }
                else -> { fill.color = 0x99d6d3cf.toInt(); ring.color = 0x99b9b5b0.toInt() }
            }
            if (m.node == pressed) { val glow = fill.color; fill.color = s.color; fill.alpha = 90; c.drawCircle(m.x, m.y, r + 7 * d, fill); fill.color = glow }
            c.drawCircle(m.x, m.y, r, fill)
            ring.strokeWidth = 2 * d
            c.drawCircle(m.x, m.y, r - d, ring)
        }
        ring.pathEffect = null
        val tag = s.tag ?: return
        if (SystemClock.uptimeMillis() > tag.until) { s.tag = null; return }
        words.textSize = 13 * d
        words.typeface = StudioUi.face(map.context, true)
        val w = tag.lines.maxOf { words.measureText(it) } + 20 * d
        val h = tag.lines.size * 18 * d + 10 * d
        val x = (tag.x + 14 * d).coerceAtMost(map.width - w - 8 * d).coerceAtLeast(8 * d)
        val y = (tag.y + 12 * d).coerceAtMost(map.height - h - 8 * d)
        fill.color = if (tag.bad) 0xf2fff0f0.toInt() else 0xf2ffffff.toInt()
        c.drawRoundRect(RectF(x, y, x + w, y + h), 8 * d, 8 * d, fill)
        ring.strokeWidth = d; ring.color = if (tag.bad) StudioUi.BAD else 0xff2b2b2b.toInt()
        c.drawRoundRect(RectF(x, y, x + w, y + h), 8 * d, 8 * d, ring)
        tag.lines.forEachIndexed { i, line ->
            words.color = if (i == 0) StudioUi.INK else if (tag.bad) StudioUi.BAD else StudioUi.MUTED
            c.drawText(line, x + 10 * d, y + 5 * d + (i + 1) * 18 * d - 4 * d, words)
        }
        map.postInvalidateDelayed(tag.until - SystemClock.uptimeMillis() + 16)
    }

    /** web `pick`: the marker under a finger, preferring stops, then the ones that can be added, then the nearest; a finger's reach. */
    private fun pick(s: Session, map: NetworkView, x: Float, y: Float): Mark? {
        val reach = 24 * map.resources.displayMetrics.density
        var best: Mark? = null; var bestRank = -1; var bestD = Float.MAX_VALUE
        for (m in marks(s, map)) {
            val d = hypot(m.x - x, m.y - y)
            if (d > maxOf(m.d / 2 + 2, reach)) continue
            val rank = RANK[m.tier] ?: 0
            if (rank > bestRank || (rank == bestRank && d < bestD)) { best = m; bestRank = rank; bestD = d }
        }
        return best
    }

    /**
     * A finger on the map while editing: a tap on a marker toggles its stop, a hold does its loop edit;
     * everything else (panning, pinching) goes on to the map. True when this took the touch.
     */
    fun touch(map: NetworkView, e: MotionEvent): Boolean {
        val s = session ?: return false
        if (s.map !== map) return false
        val d = map.resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.swallow = false
                endPress(s)
                val m = pick(s, map, e.x, e.y) ?: return false
                val timer = Runnable {
                    if (s.press?.node != m.node) return@Runnable
                    s.press = null
                    s.swallow = true
                    map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    loopAt(s, m, e.x, e.y)
                    map.invalidate()
                }
                s.press = Press(m.node, e.x, e.y, timer)
                map.postDelayed(timer, LONG_PRESS_MS)
                map.invalidate()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val p = s.press ?: return s.swallow
                if (e.pointerCount > 1 || hypot(e.x - p.x, e.y - p.y) > PRESS_SLOP_DP * d) endPress(s)
                return s.swallow
            }
            MotionEvent.ACTION_POINTER_DOWN -> { endPress(s); return s.swallow }
            MotionEvent.ACTION_UP -> {
                if (s.swallow) { s.swallow = false; return true }
                val p = s.press ?: return false
                endPress(s)
                val m = pick(s, map, p.x, p.y)?.takeIf { it.node == p.node } ?: return false
                activate(s, m)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { endPress(s); s.swallow = false }
        }
        return false
    }

    private fun endPress(s: Session) {
        val p = s.press ?: return
        s.map.removeCallbacks(p.timer)
        s.press = null
        s.map.invalidate()
    }

    /** A tap that hit no marker (web lineClick with nothing there): the hint. True: the map does nothing else. */
    fun tapAway(map: NetworkView): Boolean {
        val s = session ?: return false
        if (s.map !== map) return false
        say(s, HINT)
        return true
    }

    private fun reason(s: Session, m: Mark, lines: List<String>) {
        s.tag = Tag(lines, m.x, m.y, true, SystemClock.uptimeMillis() + REASON_MS)
        s.map.invalidate()
    }

    /** web `activate`: a station with no track to the line says so; any other has its stop added or taken out. */
    private fun activate(s: Session, m: Mark) {
        s.tag = null
        if (m.tier == "cut") {
            reason(s, m, listOf("${m.name.ifEmpty { "Stasiun itu" }} tidak ada di rel yang bisa dicapai lini ini.", "Gambar rel dari lini ke sana dulu."))
            return
        }
        edit(s, m) { h, now -> JSONObject(toggle(h, s.id, m.node, now)) }
    }

    /** web `loopAt`: a hold closes, opens or turns a loop where the tier says it can, or says there is none. */
    private fun loopAt(s: Session, m: Mark, x: Float, y: Float) {
        s.tag = null
        val action = when {
            m.tier in LOOP_TIERS -> m.tier
            m.tier == "on" && s.closed -> "open" // T-3118: any stop of a loop opens it there
            else -> null
        }
        if (action == null) {
            s.tag = Tag(listOf(m.name.ifEmpty { "Stasiun" }, "Tidak bisa membuat putaran di sini"), x, y, true, SystemClock.uptimeMillis() + REASON_MS)
            s.map.invalidate()
            return
        }
        edit(s, m) { h, now -> JSONObject(loop(h, s.id, action, m.node, now)) }
    }

    /** One step through the world screen's transaction (one undo entry), the map and the markers after it. */
    private fun edit(s: Session, m: Mark, run: (Long, Double) -> JSONObject) {
        if (busy(s)) return
        s.busySince = SystemClock.uptimeMillis()
        s.transaction { now ->
            s.busySince = 0L
            if (session !== s) return@transaction
            val r = runCatching { run(handle(), now) }.getOrElse { JSONObject().put("error", "Perhentian itu tidak bisa diubah. Coba lagi.") }
            if (r.has("error")) {
                // web `say(describeIssues(...), true)`
                Toast.makeText(s.activity, r.optString("error"), Toast.LENGTH_LONG).show()
                say(s, r.optString("error"), bad = true)
            } else {
                ServicePanel.editLabel = LineStops.LABEL
                s.changed(s.name)
                s.steps++
                val charge = r.optDouble("charge", 0.0)
                val cost = if (charge > 0) " · biaya ${StudioFormat.money(charge * 1e6, StudioCurrency.current(s.activity))}" else ""
                val what = when {
                    r.has("added") -> if (r.optBoolean("added")) "${m.name} ditambahkan" else "${m.name} dihapus"
                    else -> "Putaran ${m.name} diubah"
                }
                say(s, "$what · ${r.optInt("stops")} perhentian$cost")
            }
            if (!read(s)) { done(s); return@transaction }
            renderBar(s)
            s.map.invalidate()
        }
    }
}
