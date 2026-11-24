// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Changing a station on the map, as the web's station view does it (StationInspector.tsx
 * `StationDetails`: the platform length stepper and the platform tracks, StationSides.tsx;
 * StationUpgrades.tsx: the upgrades and the building tool, buildingDraw.ts): the map zooms to the
 * station, the controls sit in a panel over the lower half, and every change is drawn on the map
 * before it is paid for.
 *
 * Each change is tried on a fork of the world (`Engine.forkCandidate`, then station_design.rs
 * `apply`), whose map is shown in place of the world's with the station building's outline drawn
 * over it, and whose price is the confirm button's. Bangun applies the same changes to the world
 * inside the host's panel edit (saved, one undo step, redrawn); Batal or Back puts the world's map
 * back. The building's corners are dragged on the map; the small handle in an edge's middle drags
 * out a new corner.
 */
internal object StationDesign {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun state(handle: Long, node: Int, at: String): String
    @JvmStatic external fun apply(handle: Long, node: Int, clock: Double, command: String): String

    private const val TERMINAL = 1
    private const val PASSING = 8
    private val worker = Executors.newSingleThreadExecutor()

    private class Session(
        val activity: Activity, val map: NetworkView, val handle: Long, val node: Int, val forkNode: Int,
        val clock: Double, val start: JSONObject, val transaction: PanelEdit, val changed: () -> Unit,
        val reopen: (Double) -> Unit,
    ) {
        val at: JSONArray = start.getJSONArray("at")
        var length = start.getInt("length")
        /** the platform tracks picked ("left", "both", "right", "one"), null: as built */
        var tracks: String? = null
        /** upgrades to add (true) or take away (false), by bit */
        val upgrades = LinkedHashMap<Int, Boolean>()
        /** the building outline being drawn, world frame; null: not drawing */
        var outline: MutableList<PointF>? = null
        var selected = -1
        var removeBuilding = false
        /** the fork's state after the changes (what the panel and the map show) */
        var shown: JSONObject = start
        var charge = 0.0
        var error: String? = null
        var busy = false
        var previewed = false
        var version = 0
        var drag = -1
        lateinit var panel: View
        lateinit var body: LinearLayout
        lateinit var scroll: ScrollView
        lateinit var status: android.widget.TextView
        lateinit var confirm: android.widget.Button
    }

    private var session: Session? = null
    val active get() = session != null

    /**
     * Opens the design panel for station [node] (the world's numbering). [building]: start with the
     * building tool, on the station's own outline or the rectangle offered round its platform.
     */
    fun start(activity: Activity, map: NetworkView, handle: Long, node: Int, clock: Double, transaction: PanelEdit,
              changed: () -> Unit, reopen: (Double) -> Unit, building: Boolean = false) {
        finish(true)
        val start = JSONObject(state(handle, node, "null"))
        if (start.has("error")) { Toast.makeText(activity, start.getString("error"), Toast.LENGTH_LONG).show(); return }
        val forkNode = map.stops.firstOrNull { it.id == node }?.packedId ?: node
        val s = Session(activity, map, handle, node, forkNode, clock, start, transaction, changed, reopen)
        session = s
        buildPanel(s)
        frame(s)
        if (building && canBuild(start)) beginOutline(s)
        render(s)
    }

    /** Back: leaves the design, the world's map back. */
    fun back(): Boolean {
        val s = session ?: return false
        finish(true)
        s.reopen(s.clock)
        return true
    }

    private fun canBuild(st: JSONObject) = !st.optBoolean("port") && !st.optBoolean("tram")

    private fun finish(restore: Boolean) {
        val s = session ?: return
        session = null
        s.version++
        (s.panel.parent as? ViewGroup)?.removeView(s.panel)
        if (restore && s.previewed) s.map.load(JSONObject(Engine.geometry(s.handle)), refit = false, keepTrainLoads = true)
        s.map.invalidate()
    }

    // ---------------------------------------------------------------- the map

    /** The station and its building fill the band above the panel (web: the map zooms to the station). */
    private fun frame(s: Session) {
        val x = s.at.getDouble(0).toFloat(); val y = s.at.getDouble(1).toFloat()
        val k = s.start.optDouble("groundScale", 1.0).toFloat()
        var reach = (s.start.getInt("length") / 2f + 40f) * k
        for (c in corners(s.start)) reach = maxOf(reach, hypot(c.x - x, c.y - y))
        s.map.post { s.map.showAboveDesign(x, y, reach * 1.2f) }
    }

    private fun corners(st: JSONObject, key: String = "building"): List<PointF> {
        val flat = (if (key == "building") st.optJSONObject("building")?.optJSONArray("corners") else st.optJSONArray(key)) ?: return emptyList()
        return List(flat.length() / 2) { PointF(flat.getDouble(2 * it).toFloat(), flat.getDouble(2 * it + 1).toFloat()) }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    /** Drawn over the map while designing: the building outline as it would stand, and its handles while drawing. */
    fun draw(map: NetworkView, c: Canvas) {
        val s = session ?: return
        if (s.map !== map) return
        val d = map.resources.displayMetrics.density
        val drawing = s.outline
        val pts = drawing ?: if (s.removeBuilding) emptyList() else corners(s.shown)
        if (pts.size >= 2) {
            val path = Path()
            pts.forEachIndexed { i, p -> val q = map.screenOf(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            path.close()
            fill.color = 0x556c4df6
            c.drawPath(path, fill)
            line.strokeWidth = 2.5f * d
            line.color = StudioDepot.TEAL
            line.pathEffect = if (drawing != null) DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f) else null
            c.drawPath(path, line)
            line.pathEffect = null
        }
        if (drawing == null) return
        for (i in drawing.indices) {
            val a = map.screenOf(drawing[i]); val b = map.screenOf(drawing[(i + 1) % drawing.size])
            fill.color = 0xffffffff.toInt(); c.drawCircle((a.x + b.x) / 2, (a.y + b.y) / 2, 6 * d, fill)
            line.strokeWidth = 2 * d; line.color = StudioDepot.TEAL; c.drawCircle((a.x + b.x) / 2, (a.y + b.y) / 2, 6 * d, line)
        }
        for ((i, p) in drawing.withIndex()) {
            val q = map.screenOf(p)
            fill.color = if (i == s.selected) StudioUi.GREEN else StudioDepot.TEAL
            c.drawCircle(q.x, q.y, 11 * d, fill)
            fill.color = 0xffffffff.toInt()
            c.drawCircle(q.x, q.y, 4 * d, fill)
        }
    }

    /** A finger on a corner drags it; on an edge's middle handle it drags out a new corner. The rest pans the map. */
    fun touch(map: NetworkView, e: MotionEvent): Boolean {
        val s = session ?: return false
        val pts = s.outline ?: return false
        if (s.map !== map) return false
        val d = map.resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val near = pts.indices.minByOrNull { val q = map.screenOf(pts[it]); hypot(q.x - e.x, q.y - e.y) }
                if (near != null && map.screenOf(pts[near]).let { hypot(it.x - e.x, it.y - e.y) } <= 26 * d) {
                    s.drag = near
                } else {
                    val edge = pts.indices.minByOrNull { mid(map, pts, it).let { m -> hypot(m.x - e.x, m.y - e.y) } } ?: return false
                    if (mid(map, pts, edge).let { hypot(it.x - e.x, it.y - e.y) } > 22 * d || pts.size >= 24) return false
                    pts.add(edge + 1, map.worldAt(e.x, e.y))
                    s.drag = edge + 1
                }
                s.selected = s.drag
                map.parent?.requestDisallowInterceptTouchEvent(true)
                map.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                map.invalidate()
                render(s)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (s.drag < 0) return false
                if (e.pointerCount > 1) return true
                pts[s.drag] = map.worldAt(e.x, e.y)
                map.invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return s.drag >= 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (s.drag < 0) return false
                s.drag = -1
                schedule(s)
                return true
            }
        }
        return s.drag >= 0
    }

    private fun mid(map: NetworkView, pts: List<PointF>, i: Int): PointF {
        val a = map.screenOf(pts[i]); val b = map.screenOf(pts[(i + 1) % pts.size])
        return PointF((a.x + b.x) / 2, (a.y + b.y) / 2)
    }

    // ---------------------------------------------------------------- the changes and their preview

    private fun ops(s: Session): JSONArray {
        val out = JSONArray()
        if (s.length != s.start.getInt("length")) out.put(JSONObject().put("op", "length").put("m", s.length))
        when (s.tracks) {
            "left" -> out.put(JSONObject().put("op", "way").put("heading", s.start.getDouble("left")))
            "right" -> out.put(JSONObject().put("op", "way").put("heading", s.start.getDouble("right")))
            "both" -> out.put(JSONObject().put("op", "tracks").put("one", false))
            "one" -> out.put(JSONObject().put("op", "tracks").put("one", true))
        }
        for ((bit, on) in s.upgrades) out.put(JSONObject().put("op", "upgrade").put("bit", bit).put("on", on))
        val o = s.outline
        when {
            s.removeBuilding -> out.put(JSONObject().put("op", "removeBuilding"))
            o != null && o.size >= 3 && !sameOutline(o, corners(s.start)) -> out.put(JSONObject().put("op", "building").put("corners",
                JSONArray().apply { o.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }))
        }
        return out
    }

    private fun sameOutline(a: List<PointF>, b: List<PointF>) =
        a.size == b.size && a.indices.all { hypot(a[it].x - b[it].x, a[it].y - b[it].y) < .01f }

    private fun request(s: Session) = JSONObject().put("at", s.at).put("ops", ops(s)).toString()

    private fun schedule(s: Session) {
        val v = ++s.version
        s.busy = true
        render(s)
        s.map.postDelayed({ if (session === s && s.version == v) preview(s, v) }, 220)
    }

    private fun preview(s: Session, v: Int) {
        if (ops(s).length() == 0) {
            if (s.previewed) s.map.load(JSONObject(Engine.geometry(s.handle)), refit = false, keepTrainLoads = true)
            s.previewed = false
            s.shown = s.start; s.charge = 0.0; s.error = null; s.busy = false
            render(s)
            return
        }
        val command = request(s)
        val candidate = Engine.forkCandidate(s.handle, s.clock, RoadTileStore.directory(s.activity).absolutePath)
        if (candidate == 0L) { s.busy = false; s.error = "Rancangan belum dapat dihitung."; render(s); return }
        // only this worker touches the fork until it is closed
        worker.execute {
            val result = runCatching {
                val r = JSONObject(apply(candidate, s.forkNode, s.clock, command))
                if (r.has("error")) Triple(r, null, null)
                else Triple(r, JSONObject(state(candidate, r.getInt("node"), s.at.toString())), Engine.geometry(candidate))
            }
            Engine.close(candidate)
            s.map.post {
                if (session !== s || s.version != v) return@post
                s.busy = false
                result.onSuccess { (r, st, geometry) ->
                    if (r.has("error")) { s.error = r.getString("error"); return@onSuccess }
                    s.error = null
                    s.charge = r.optDouble("charge", 0.0)
                    if (st != null && !st.has("error")) s.shown = st
                    if (geometry != null) { s.map.load(JSONObject(geometry), refit = false, keepTrainLoads = true); s.previewed = true }
                }.onFailure { s.error = "Rancangan belum dapat dihitung." }
                render(s)
                s.map.invalidate()
            }
        }
    }

    private fun commit(s: Session) {
        val command = request(s)
        val activity = s.activity
        val currency = StudioCurrency.current(activity)
        val name = s.start.optString("name")
        finish(false)
        s.transaction { now ->
            val r = JSONObject(apply(s.handle, s.node, now, command))
            if (r.has("error")) Toast.makeText(activity, r.getString("error"), Toast.LENGTH_LONG).show()
            else {
                val charge = r.optDouble("charge", 0.0)
                Toast.makeText(activity, if (charge > 0) "$name diubah seharga ${StudioFormat.money(charge * 1e6, currency)}." else "$name diubah.",
                    Toast.LENGTH_LONG).show()
            }
            // the world's map back, changed or not
            if (r.optBoolean("changed")) s.changed()
            else if (s.previewed) s.map.load(JSONObject(Engine.geometry(s.handle)), refit = false, keepTrainLoads = true)
            s.reopen(now)
        }
    }

    private fun beginOutline(s: Session) {
        val own = corners(s.shown)
        s.outline = (own.ifEmpty { corners(s.start, "offer") }).map { PointF(it.x, it.y) }.toMutableList()
        s.removeBuilding = false
        s.selected = -1
        schedule(s)
    }

    // ---------------------------------------------------------------- the panel

    private fun buildPanel(s: Session) {
        val a = s.activity
        fun dp(n: Int) = StudioUi.dp(a, n)
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.glass(a, 28, 0xfffbfaff.toInt())
            clipToOutline = true
            setPadding(dp(16), dp(8), dp(16), dp(12))
            isClickable = true // the map behind does not take the panel's touches
            elevation = dp(12).toFloat()
        }
        root.addView(View(a).apply { background = StudioUi.pill(a, 0x3822204a) },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.title(a, s.start.optString("name"), 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(StudioUi.text(a, "Rancang di peta", 12f, true, StudioUi.MUTED))
        root.addView(head)
        s.body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        s.scroll = ScrollView(a).apply { addView(s.body) }
        root.addView(s.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        s.status = StudioUi.text(a, "", 13f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(6), dp(4), dp(4)) }
        root.addView(s.status)
        val foot = LinearLayout(a)
        foot.addView(StudioUi.button(a, "Batal") { back() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        s.confirm = StudioUi.button(a, "Bangun", StudioUi.Tone.PRIMARY) { if (!s.busy && s.error == null && ops(s).length() > 0) commit(s) }
        foot.addView(s.confirm, LinearLayout.LayoutParams(0, dp(52), 1.4f).apply { marginStart = dp(6) })
        root.addView(foot)
        val height = (content.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels) * 46 / 100
        content.addView(root, FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        s.panel = root
    }

    private fun render(s: Session) {
        if (session !== s) return
        val a = s.activity
        fun dp(n: Int) = StudioUi.dp(a, n)
        val currency = StudioCurrency.current(a)
        val st = s.shown
        val y = s.scroll.scrollY
        val body = s.body
        body.removeAllViews()
        fun choice(label: String, on: Boolean, pending: Boolean, describe: String = label, click: () -> Unit) =
            StudioUi.button(a, label, if (on) StudioUi.Tone.BLUE else StudioUi.Tone.SURFACE) { click() }.apply {
                textSize = 13f; minHeight = dp(48); minimumHeight = dp(48)
                contentDescription = describe + (if (on) ", dipilih" else "") + (if (pending) ", belum dibangun" else "")
            }
        val port = st.optBoolean("port")
        if (!port) {
            // web StationDetails: the platform length in cars, stepped a car at a time
            body.addView(StudioUi.section(a, "Panjang stasiun"))
            val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            val min = s.start.getInt("min"); val max = s.start.getInt("max"); val step = s.start.getInt("step")
            fun step(d: Int) {
                val to = (s.length + d * step).coerceIn(min, max)
                if (to == s.length) return
                s.length = to
                schedule(s)
            }
            row.addView(StudioUi.button(a, "−") { step(-1) }.apply { contentDescription = "Perpendek stasiun"; isEnabled = s.length > min },
                LinearLayout.LayoutParams(dp(56), dp(48)))
            val cars = Math.round(s.length / 20.0)
            row.addView(StudioUi.text(a, "$cars gerbong · ${s.length} m", 16f, true,
                if (s.length != s.start.getInt("length")) StudioUi.BLUE else StudioUi.INK).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(StudioUi.button(a, "+") { step(1) }.apply { contentDescription = "Perpanjang stasiun"; isEnabled = s.length < max },
                LinearLayout.LayoutParams(dp(56), dp(48)))
            body.addView(row)
            // web StationSides (double track) or PlatformTracks (single track)
            body.addView(StudioUi.section(a, "Jenis stasiun"))
            val sides = LinearLayout(a)
            val one = st.optInt("one")
            fun pick(key: String) {
                val was = when { s.start.optInt("one") == 0 -> "both"; s.start.optBoolean("onLeft") -> "left"; s.start.optBoolean("onRight") -> "right"; else -> "one" }
                s.tracks = if (key == was) null else key
                // a one-track station has no terminal or passing tracks (web T-3332)
                if (key != "both") { s.upgrades.remove(TERMINAL); s.upgrades.remove(PASSING) }
                schedule(s)
            }
            fun way(w: String) = when (w) { "east" -> "arah timur"; "west" -> "arah barat"; "north" -> "arah utara"; else -> "arah selatan" }
            val free = "Gratis"
            val cost = s.start.optDouble("tracksCost", 0.0).let { if (it > 0) "Biaya ${StudioFormat.money(it * 1e6, currency)}" else free }
            if (s.start.optBoolean("double")) {
                val l = "Stasiun satu arah (${way(s.start.optString("leftWay"))})"
                val r = "Stasiun satu arah (${way(s.start.optString("rightWay"))})"
                sides.addView(choice("◧ ${way(s.start.optString("leftWay"))}", st.optBoolean("onLeft"), s.tracks == "left", "$l. $free") { pick("left") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                sides.addView(choice("Dua arah", one == 0, s.tracks == "both", "Stasiun dua arah. $cost") { pick("both") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                sides.addView(choice("${way(s.start.optString("rightWay"))} ◨", st.optBoolean("onRight"), s.tracks == "right", "$r. $free") { pick("right") },
                    LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                sides.addView(choice("Tanpa jalur persilangan", one != 0, s.tracks == "one") { pick("one") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                sides.addView(choice("Jalur persilangan", one == 0, s.tracks == "both", "Jalur persilangan. $cost") { pick("both") },
                    LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(sides)
            if (one == 0 && s.start.optInt("one") != 0) body.addView(StudioUi.text(a, cost, 12.5f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(4), 0, 0) })
        }
        // web StationUpgrades: each a toggle, its price; built ones go without money back
        val offered = st.optJSONArray("upgrades")
        if (offered != null && offered.length() > 0) {
            body.addView(StudioUi.section(a, "Peningkatan stasiun"))
            val was = HashMap<Int, Boolean>()
            s.start.optJSONArray("upgrades")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { o -> was[o.getInt("bit")] = o.getBoolean("on") } }
            for (i in 0 until offered.length()) {
                val o = offered.getJSONObject(i)
                val bit = o.getInt("bit")
                val name = StationInspector.UPGRADE_NAMES[bit] ?: continue
                val on = s.upgrades[bit] ?: o.getBoolean("on")
                val pending = s.upgrades.containsKey(bit)
                val price = StudioFormat.money(o.getDouble("price") * 1e6, currency)
                val tag = when {
                    on && !pending -> "Dibangun"
                    on -> price
                    pending -> "Dihapus"
                    else -> price
                }
                val chip = StudioUi.text(a, tag, 13f, true, if (on && !pending) StudioUi.GOOD else if (pending) StudioUi.WHITE else StudioUi.INK).apply {
                    background = StudioUi.pill(a, if (on && !pending) StudioUi.TINT_GREEN else if (pending) StudioUi.BLUE else StudioUi.TINT_GREY, 19)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val sub = (StationInspector.UPGRADE_DOES[bit] ?: "") + if (on && !pending) " Ketuk untuk menghapus; uang tidak kembali." else ""
                body.addView(StudioUi.row(a, null, 0, name, sub, chip) {
                    val target = !on
                    if (target == (was[bit] ?: false)) s.upgrades.remove(bit) else s.upgrades[bit] = target
                    schedule(s)
                }.apply { contentDescription = "$name, $tag" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        // web StationUpgrades BuildingButton and StationBuilding: the outline drawn on the map
        if (canBuild(s.start)) {
            body.addView(StudioUi.section(a, "Gedung stasiun"))
            val building = st.optJSONObject("building")
            val outline = s.outline
            when {
                outline != null -> {
                    body.addView(StudioUi.text(a, "${if (building == null && corners(s.start).isEmpty()) "Gedung stasiun baru" else "Bentuk gedung stasiun"}: ${outline.size} sudut. " +
                        "Geser sudut di peta. Tarik titik kecil di tengah sisi untuk menambah sudut.", 13f, true, StudioUi.MUTED).apply { setPadding(dp(4), 0, dp(4), dp(4)) })
                    building?.let { body.addView(StudioUi.text(a, "Luas ${Math.round(it.optDouble("area"))} m² · ${it.optInt("members")} peron di dalamnya",
                        13f, true, StudioUi.INK).apply { setPadding(dp(4), 0, dp(4), dp(4)) }) }
                    val tools = LinearLayout(a)
                    tools.addView(StudioUi.button(a, "Hapus sudut") {
                        val o = s.outline ?: return@button
                        if (s.selected in o.indices && o.size > 3) { o.removeAt(s.selected); s.selected = -1; s.map.invalidate(); schedule(s) }
                        else Toast.makeText(a, "Ketuk sudut di peta dulu. Bangunan perlu paling sedikit 3 sudut.", Toast.LENGTH_SHORT).show()
                    }.apply { isEnabled = s.selected >= 0 && outline.size > 3 }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
                    tools.addView(StudioUi.button(a, "Batalkan garis") { s.outline = null; s.selected = -1; s.map.invalidate(); schedule(s) },
                        LinearLayout.LayoutParams(0, dp(48), 1f))
                    body.addView(tools)
                }
                s.removeBuilding -> {
                    body.addView(StudioUi.text(a, "Gedung akan dihapus. Peron tetap beroperasi; biaya pembangunan tidak dikembalikan.", 13f, true, StudioUi.BAD))
                    body.addView(StudioUi.button(a, "Urungkan hapus") { s.removeBuilding = false; schedule(s) }, LinearLayout.LayoutParams(-1, dp(48)))
                }
                building != null -> {
                    body.addView(StudioUi.row(a, null, StudioUi.TINT_ORANGE, building.optString("name"),
                        "Luas ${Math.round(building.optDouble("area"))} m² · ${building.optInt("members")} peron"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    val tools = LinearLayout(a)
                    tools.addView(StudioUi.button(a, "Ubah garis") { beginOutline(s) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
                    tools.addView(StudioUi.button(a, "Hapus bangunan") { s.removeBuilding = true; s.outline = null; schedule(s) }.also { DemolitionPanel.danger(it) },
                        LinearLayout.LayoutParams(0, dp(48), 1f))
                    body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
                else -> body.addView(StudioUi.button(a, "Rancang gedung stasiun", icon = StudioUi.Icon.PLUS) { beginOutline(s) },
                    LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        s.scroll.post { s.scroll.scrollTo(0, y) }
        // the price of everything changed, as the fork paid it
        val changes = ops(s).length()
        s.status.setTextColor(if (s.error != null) StudioUi.BAD else StudioUi.MUTED)
        s.status.text = when {
            s.busy -> "Menghitung biaya…"
            s.error != null -> s.error
            changes == 0 -> "Belum ada perubahan. Ubah stasiun, lalu lihat hasilnya di peta."
            s.charge > 0 -> "Biaya ${StudioFormat.money(s.charge * 1e6, currency)} · terlihat di peta sebelum dibangun"
            else -> "Tanpa biaya · terlihat di peta sebelum disimpan"
        }
        s.confirm.isEnabled = !s.busy && s.error == null && changes > 0
        s.confirm.alpha = if (s.confirm.isEnabled) 1f else .5f
        s.confirm.text = if (s.charge > 0 && !s.busy && s.error == null) "Bangun · ${StudioFormat.money(s.charge * 1e6, currency)}" else if (changes > 0) "Simpan" else "Bangun"
    }
}
