// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * The line panel's stops (web LineInspector stop rows): per stop the boardings in the past 24 hours
 * (web `StopToday`), and from the simulation's forecast the riders getting on and off there, the riders
 * on the stretch to the next stop (web Traffic view `dailyRiders`) and where the riders boarding there
 * get off most. With the panel editable, a stop between the ends can be passed (web `dropStop`) and a
 * station the line passes between two stops becomes a stop (web `stopPassed`, the "dilewati" rows).
 * Edits go through the panel's transaction, as its other edits do, and reopen the panel.
 * The loop edits the web gives a long press on a stop marker (map/stopMarkers.ts `LOOP_TIERS`): close
 * the line into a loop, open it, and turn its end round a loop of track or take that turn off again.
 */
internal object LineStops {
    @JvmStatic private external fun read(handle: Long, id: Int): String
    @JvmStatic private external fun skip(handle: Long, id: Int, index: Int, now: Double): String
    @JvmStatic private external fun stop(handle: Long, id: Int, node: Int, now: Double): String
    /** Web `addGaps` for one gap: the stations passed between stop `gap` and the next made stops. */
    @JvmStatic private external fun stopGap(handle: Long, id: Int, gap: Int, now: Double): String
    /** Web `tools.loopStop`: `action` close, open, turn or unturn at station `node`. */
    @JvmStatic external fun stopAll(handle: Long, id: Int, now: Double): String
    @JvmStatic private external fun loop(handle: Long, id: Int, action: String, node: Int, now: Double): String

    /** The running simulation's stop rows for a line (SimulationClock.lineRiders); null without one. */
    var riders: ((Int, (String?) -> Unit) -> Unit)? = null

    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun n(v: Double) = count.format(Math.round(v))
    /** The undo entry's label for a stop edit. */
    const val LABEL = "Perhentian line"
    private const val VISIBLE = 8
    private const val TRIES = 20
    private const val RETRY_MS = 2000L

    /** One stop-edit to run inside the panel's transaction: the engine's answer at game time `now`. */
    fun interface Edit { fun run(now: Double): JSONObject }

    fun strip(host: Activity, handle: Long, id: Int, route: ServiceStops, color: Int, editable: Boolean,
              onRiders: ((JSONObject?) -> Unit)?,
              apply: (success: (JSONObject) -> String, edit: Edit) -> Unit): View {
        fun dp(v: Int) = StudioUi.dp(host, v)
        val edits = runCatching { JSONObject(read(handle, id)) }.getOrNull()
        val skippable = edits?.optJSONArray("skip")
        val passed = edits?.optJSONArray("passed") ?: JSONArray()
        val count = route.names.size
        val strip = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 16)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val head = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.section(host, "Perhentian"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(StudioUi.text(host, "naik hari ini", 12f, true, StudioUi.MUTED))
        strip.addView(head)
        val status = StudioUi.text(host, "Menghitung penumpang…", 12.5f, true, StudioUi.MUTED).apply {
            setPadding(0, dp(2), 0, dp(4))
            visibility = if (riders != null) View.VISIBLE else View.GONE
        }
        strip.addView(status)
        val nodes = edits?.optJSONArray("nodes")?.takeIf { it.length() == count }
        val loopInfo = edits?.optJSONObject("loop")
        val looped = loopInfo?.optBoolean("closed") == true
        val turnAt = loopInfo?.optJSONArray("turn")?.let { t -> (0 until t.length()).map { t.optInt(it) }.toSet() } ?: emptySet()
        val unturnAt = loopInfo?.takeIf { !it.isNull("unturn") }?.optInt("unturn", -1) ?: -1
        fun loopEdit(action: String, node: Int, said: String) =
            apply({ _ -> said }) { now -> JSONObject(loop(handle, id, action, node, now)) }

        // the line through the rows, a stop's dot (bigger at the ends), or a passed station's small grey one
        fun rail(index: Int, between: Boolean): View = object : View(host) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = dp(19).toFloat()
                pen.style = Paint.Style.FILL
                pen.color = color
                val top = if (!between && index == 0) cy else 0f
                val bottom = if (!between && index == count - 1) cy else height.toFloat()
                canvas.drawRect(cx - dp(3), top, cx + dp(3), bottom, pen)
                if (between) {
                    pen.color = 0xffb8b4c8.toInt()
                    canvas.drawCircle(cx, cy, dp(4).toFloat(), pen)
                    return
                }
                val terminal = index == 0 || index == count - 1
                val r = dp(if (terminal) 8 else 6).toFloat()
                pen.color = StudioUi.WHITE
                canvas.drawCircle(cx, cy, r, pen)
                pen.style = Paint.Style.STROKE
                pen.strokeWidth = dp(if (terminal) 4 else 3).toFloat()
                pen.color = StudioUi.INK
                canvas.drawCircle(cx, cy, r - pen.strokeWidth / 2, pen)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun action(label: String, description: String, click: () -> Unit) = StudioUi.text(host, label, 12.5f, true, StudioUi.BLUE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            minWidth = dp(48)
            setPadding(dp(10), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { click() }
        }

        val todays = arrayOfNulls<TextView>(count)
        val details = arrayOfNulls<TextView>(count)
        // One line per stop (name + riders today); the naik/turun detail opens when the row is tapped.
        fun stopRow(index: Int): View = LinearLayout(host).apply {
            val terminal = index == 0 || index == count - 1
            minimumHeight = dp(40)
            addView(rail(index, false), LinearLayout.LayoutParams(dp(22), -1))
            val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(9), 0, dp(7)) }
            body.addView(StudioUi.text(host, route.numbered(index), 14f, terminal, StudioUi.INK).apply {
                if (terminal) typeface = StudioUi.heavy(host)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val more = StudioUi.text(host, "", 12f, false, StudioUi.MUTED).apply { visibility = View.GONE; setPadding(0, dp(2), 0, 0) }
            details[index] = more
            body.addView(more)
            addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            isClickable = true
            setOnClickListener { if (!more.text.isNullOrEmpty()) more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            val today = StudioUi.text(host, "", 13f, true, StudioUi.INK).apply { setPadding(dp(6), dp(10), 0, 0) }
            todays[index] = today
            addView(today, LinearLayout.LayoutParams(-2, -2))
            val node = nodes?.optInt(index, -1) ?: -1
            val firstVisit = nodes != null && (0 until index).none { nodes.optInt(it) == node }
            if (editable && firstVisit && node in turnAt) {
                val name = route.names[index]
                addView(action("Buat putaran", "Buat putaran di $name: ujung line berputar di rel putaran lalu kembali") {
                    loopEdit("turn", node, "Line kini berputar di rel putaran setelah $name.")
                }, LinearLayout.LayoutParams(-2, -2))
            } else if (editable && firstVisit && node == unturnAt) {
                val name = route.names[index]
                addView(action("Hapus putaran", "Hapus putaran di $name") {
                    loopEdit("unturn", node, "Putaran setelah $name dihapus.")
                }, LinearLayout.LayoutParams(-2, -2))
            }
            if (editable && skippable?.optBoolean(index) == true) {
                val name = route.names[index]
                addView(action("Lewati", "Lewati $name: line tidak lagi berhenti di sini") {
                    apply({ r -> "Line tidak lagi berhenti di ${r.optString("name", name)}." }) { now -> JSONObject(skip(handle, id, index, now)) }
                }, LinearLayout.LayoutParams(-2, -2))
            }
        }
        // Web `afterStop` (T-646): after stop `gap` one row "+ N stasiun dilewati"; tapped, it becomes
        // "Tambah N stasiun" / "Batal" with the stations listed under it, the other gaps' rows hidden
        // meanwhile, as the web's `pending`. Only while the line can be edited (web: its stops edited).
        val gaps = sortedMapOf<Int, MutableList<Pair<Int, String>>>()
        for (k in 0 until passed.length()) {
            val p = passed.optJSONArray(k) ?: continue
            gaps.getOrPut(p.optInt(0)) { mutableListOf() }.add(p.optInt(1) to p.optString(2))
        }
        val gapViews = mutableMapOf<Int, LinearLayout>()
        var pending = -1
        fun stations(n: Int) = "$n stasiun"
        fun renderGaps() {
            for ((gap, box) in gapViews) {
                box.removeAllViews()
                val here = gaps[gap] ?: continue
                val names = here.map { it.second }
                fun line(content: View) = LinearLayout(host).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(rail(0, true), LinearLayout.LayoutParams(dp(24), -1))
                    addView(content, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                }
                if (pending == gap) {
                    val bar = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
                    bar.addView(action("Tambah ${stations(here.size)}", "Tambah ${names.joinToString(", ")} sebagai perhentian") {
                        pending = -1
                        apply({ r ->
                            val added = r.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: names
                            "Line kini berhenti di ${added.joinToString(", ")}."
                        }) { now -> JSONObject(stopGap(handle, id, gap, now)) }
                    }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
                    bar.addView(action("Batal", "Batal menambah perhentian") { pending = -1; renderGaps() }, LinearLayout.LayoutParams(-2, -2))
                    box.addView(line(bar))
                    names.forEach { name ->
                        box.addView(line(StudioUi.text(host, "+ $name", 13.5f, true, StudioUi.INK).apply { setPadding(0, dp(6), 0, dp(6)) }))
                    }
                } else if (pending < 0) {
                    box.addView(line(action("+ ${stations(here.size)} dilewati", "${stations(here.size)} dilewati: ${names.joinToString(", ")}. Ketuk untuk menambah sebagai perhentian") {
                        pending = gap; renderGaps()
                    }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(0, 0, dp(4), 0) }))
                }
            }
        }
        fun group(index: Int): List<View> {
            val rows = mutableListOf(stopRow(index))
            if (editable && index + 1 < count && gaps.containsKey(index)) {
                val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                gapViews[index] = box
                rows.add(box)
            }
            return rows
        }
        if (looped || (editable && loopInfo?.optBoolean("close") == true && nodes != null)) {
            val bar = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            bar.addView(StudioUi.text(host, if (looped) "Putaran tertutup · kereta kembali ke ${route.names.firstOrNull().orEmpty()}" else "Line terbuka",
                12.5f, true, if (looped) StudioUi.INK else StudioUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            val first = nodes?.optInt(0, -1) ?: -1
            if (editable && first >= 0) {
                if (looped) bar.addView(action("Buka putaran", "Buka putaran: line berakhir lagi di ujungnya") {
                    loopEdit("open", first, "Putaran dibuka.")
                }, LinearLayout.LayoutParams(-2, -2))
                else bar.addView(action("Tutup putaran", "Tutup putaran: dari perhentian terakhir line kembali ke ${route.names.firstOrNull().orEmpty()}") {
                    loopEdit("close", first, "Putaran ditutup: line kembali ke ${route.names.firstOrNull().orEmpty()}.")
                }, LinearLayout.LayoutParams(-2, -2))
            }
            strip.addView(bar)
        }
        (0 until minOf(VISIBLE, count)).forEach { i -> group(i).forEach { strip.addView(it) } }
        val rest = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        (VISIBLE until count).forEach { i -> group(i).forEach { rest.addView(it) } }
        strip.addView(rest)
        renderGaps()
        if (route.unnamedCount > 0) strip.addView(StudioUi.text(host,
            "Pemberhentian yang belum bernama ditampilkan menurut urutannya di rute.", 12f, true, StudioUi.MUTED))
        if (count > VISIBLE) {
            val show = "+${count - VISIBLE} ${route.stopType} lagi · lihat urutan"
            lateinit var toggle: TextView
            toggle = StudioUi.text(host, show, 13f, true, StudioUi.BLUE).apply {
                setPadding(dp(30), 0, 0, 0)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    val expand = rest.visibility != View.VISIBLE
                    rest.visibility = if (expand) View.VISIBLE else View.GONE
                    toggle.text = if (expand) "Tutup urutan ${route.stopType}" else show
                }
            }
            strip.addView(toggle)
        }

        fun fill(r: JSONObject) {
            val rows = r.optJSONArray("stops") ?: return
            if (rows.length() != count) return
            for (k in 0 until count) {
                val row = rows.optJSONObject(k) ?: continue
                todays[k]?.text = if (row.isNull("today")) "" else n(row.optDouble("today"))
                if (!row.has("on")) continue
                val parts = mutableListOf("naik ${n(row.optDouble("on"))}", "turun ${n(row.optDouble("off"))}")
                if (!row.isNull("next")) parts.add("${n(row.optDouble("next"))} di ruas berikutnya")
                var text = parts.joinToString(" · ")
                val to = row.optJSONArray("to")
                if (to != null && to.length() > 0) {
                    text += "\nKebanyakan turun di " + (0 until to.length()).joinToString(", ") {
                        val t = to.getJSONObject(it)
                        "${t.optString("name")} (${Math.round(t.optDouble("share") * 100)}%)"
                    }
                }
                details[k]?.text = text
            }
            if (r.optBoolean("forecast")) {
                status.text = "Angka per baris: perkiraan penumpang per hari."
                status.visibility = View.VISIBLE
            } else status.visibility = View.GONE
        }
        // The session may still be warming up: ask again until it answers or the panel closes.
        val ask = riders
        if (ask != null) {
            var closed = false
            strip.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { closed = true }
            })
            val main = Handler(Looper.getMainLooper())
            fun load(attempt: Int) {
                if (closed) return
                ask(id) { text ->
                    val r = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                    when {
                        closed -> {}
                        r != null && (r.optBoolean("forecast") || attempt >= TRIES) -> { fill(r); onRiders?.invoke(r.takeIf { it.optBoolean("forecast") }) }
                        attempt < TRIES -> {
                            r?.let(::fill)
                            status.text = "Menghitung penumpang…"
                            status.visibility = View.VISIBLE
                            main.postDelayed({ load(attempt + 1) }, RETRY_MS)
                        }
                        else -> { status.text = "Data penumpang muncul setelah simulasi berjalan."; onRiders?.invoke(null) }
                    }
                }
            }
            load(0)
        }
        return strip
    }

    /** The panel's half of an edit: run it in `transaction`, say what changed, and give the label for undo. */
    fun toast(host: Activity, r: JSONObject, success: (JSONObject) -> String): Boolean {
        if (r.has("error")) {
            WorldSound.refused(host, r.optString("error"))
            return false
        }
        val charge = r.optDouble("charge", 0.0)
        val cost = if (charge > 0) " Biaya ${StudioFormat.money(charge * 1e6, StudioCurrency.current(host))}." else ""
        Toast.makeText(host, success(r) + cost, Toast.LENGTH_LONG).show()
        return true
    }
}
