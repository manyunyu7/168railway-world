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
 */
internal object LineStops {
    @JvmStatic private external fun read(handle: Long, id: Int): String
    @JvmStatic private external fun skip(handle: Long, id: Int, index: Int, now: Double): String
    @JvmStatic private external fun stop(handle: Long, id: Int, node: Int, now: Double): String

    /** The running simulation's stop rows for a line (SimulationClock.lineRiders); null without one. */
    var riders: ((Int, (String?) -> Unit) -> Unit)? = null

    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun n(v: Double) = count.format(Math.round(v))
    /** The undo entry's label for a stop edit. */
    const val LABEL = "Perhentian lini"
    private const val VISIBLE = 8
    private const val TRIES = 20
    private const val RETRY_MS = 2000L

    /** One stop-edit to run inside the panel's transaction: the engine's answer at game time `now`. */
    fun interface Edit { fun run(now: Double): JSONObject }

    fun strip(host: Activity, handle: Long, id: Int, route: ServiceStops, color: Int, editable: Boolean,
              apply: (success: (JSONObject) -> String, edit: Edit) -> Unit): View {
        fun dp(v: Int) = StudioUi.dp(host, v)
        val edits = runCatching { JSONObject(read(handle, id)) }.getOrNull()
        val skippable = edits?.optJSONArray("skip")
        val passed = edits?.optJSONArray("passed") ?: JSONArray()
        val count = route.names.size
        val strip = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 22)
            setPadding(dp(12), dp(10), dp(12), dp(10))
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

        // the line through the rows, a stop's dot (bigger at the ends), or a passed station's small grey one
        fun rail(index: Int, between: Boolean): View = object : View(host) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = dp(17).toFloat()
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
                val r = dp(if (terminal) 9 else 6).toFloat()
                pen.color = StudioUi.WHITE
                canvas.drawCircle(cx, cy, r, pen)
                pen.style = Paint.Style.STROKE
                pen.strokeWidth = dp(if (terminal) 4 else 3).toFloat()
                pen.color = StudioUi.INK
                canvas.drawCircle(cx, cy, r - pen.strokeWidth / 2, pen)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun action(label: String, description: String, click: () -> Unit) = StudioUi.text(host, label, 13f, true, StudioUi.BLUE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(48)
            setPadding(dp(10), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { click() }
        }

        val todays = arrayOfNulls<TextView>(count)
        val details = arrayOfNulls<TextView>(count)
        fun stopRow(index: Int): View = LinearLayout(host).apply {
            val terminal = index == 0 || index == count - 1
            addView(rail(index, false), LinearLayout.LayoutParams(dp(24), -1))
            val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(6)) }
            body.addView(StudioUi.text(host, route.numbered(index), if (terminal) 15f else 14f, terminal, StudioUi.INK).apply {
                if (terminal) typeface = StudioUi.heavy(host)
            })
            val more = StudioUi.text(host, "", 12f, true, StudioUi.MUTED).apply { visibility = View.GONE; setPadding(0, dp(2), 0, 0) }
            details[index] = more
            body.addView(more)
            addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val today = StudioUi.text(host, "", 14f, true, StudioUi.INK).apply { setPadding(dp(6), dp(8), 0, 0) }
            todays[index] = today
            addView(today, LinearLayout.LayoutParams(-2, -2))
            if (editable && skippable?.optBoolean(index) == true) {
                val name = route.names[index]
                addView(action("Lewati", "Lewati $name: lini tidak lagi berhenti di sini") {
                    apply({ r -> "Lini tidak lagi berhenti di ${r.optString("name", name)}." }) { now -> JSONObject(skip(handle, id, index, now)) }
                }, LinearLayout.LayoutParams(-2, -2))
            }
        }
        fun passedRow(node: Int, name: String): View = LinearLayout(host).apply {
            addView(rail(0, true), LinearLayout.LayoutParams(dp(24), -1))
            addView(StudioUi.text(host, "$name · dilewati", 13f, true, StudioUi.MUTED).apply {
                alpha = .8f
                setPadding(0, dp(8), 0, dp(8))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            if (editable) addView(action("+ Berhenti di sini", "Berhenti di $name") {
                apply({ r -> "Lini kini berhenti di ${r.optString("name", name)}." }) { now -> JSONObject(stop(handle, id, node, now)) }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        fun group(index: Int): List<View> {
            val rows = mutableListOf(stopRow(index))
            if (index + 1 < count) for (k in 0 until passed.length()) {
                val p = passed.optJSONArray(k) ?: continue
                if (p.optInt(0) == index) rows.add(passedRow(p.optInt(1), p.optString(2)))
            }
            return rows
        }
        (0 until minOf(VISIBLE, count)).forEach { i -> group(i).forEach { strip.addView(it) } }
        val rest = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        (VISIBLE until count).forEach { i -> group(i).forEach { rest.addView(it) } }
        strip.addView(rest)
        if (route.unnamedCount > 0) strip.addView(StudioUi.text(host,
            "Pemberhentian yang belum bernama ditampilkan menurut urutannya di rute.", 12f, true, StudioUi.MUTED))
        if (count > VISIBLE) {
            val show = "+${count - VISIBLE} ${route.stopType} lagi · lihat urutan"
            lateinit var toggle: TextView
            toggle = StudioUi.text(host, show, 13f, true, StudioUi.BLUE).apply {
                setPadding(dp(34), dp(6), 0, dp(2))
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
                details[k]?.apply { this.text = text; visibility = View.VISIBLE }
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
                        r != null && (r.optBoolean("forecast") || attempt >= TRIES) -> fill(r)
                        attempt < TRIES -> {
                            r?.let(::fill)
                            status.text = "Menghitung penumpang…"
                            status.visibility = View.VISIBLE
                            main.postDelayed({ load(attempt + 1) }, RETRY_MS)
                        }
                        else -> status.text = "Data penumpang muncul setelah simulasi berjalan."
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
            Toast.makeText(host, r.optString("error"), Toast.LENGTH_LONG).show()
            return false
        }
        val charge = r.optDouble("charge", 0.0)
        val cost = if (charge > 0) " Biaya ${StudioFormat.money(charge * 1e6, StudioCurrency.current(host))}." else ""
        Toast.makeText(host, success(r) + cost, Toast.LENGTH_LONG).show()
        return true
    }
}
