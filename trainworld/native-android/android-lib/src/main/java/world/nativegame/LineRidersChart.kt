// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * The line panel's rider graph: the web Traffic view's stretches (LineInspector `stretchLooks`,
 * map/trackHover.ts `dailyRiders`, the forecast day's riders on each stretch between two stops) drawn
 * along the line, the riders towards its last stop above the axis and those towards its first below,
 * in the line's colour. A tap or drag on a stretch reads its riders (web's stretch hover), as does
 * TalkBack, stepping from stretch to stretch. Filled from the same simulation answer as the stop rows
 * (`train_riders::line`'s `stretches`); "Menghitung…" until the session has it.
 */
internal object LineRidersChart {
    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun n(v: Double) = if (v < .5) "0" else count.format(Math.round(v))

    /** The graph's card, and what fills it from a `lineRiders` answer (null: the session gave up). */
    class Chart(val view: View, val fill: (JSONObject?) -> Unit)

    fun create(host: Activity, route: ServiceStops, color: Int): Chart {
        fun dp(v: Int) = StudioUi.dp(host, v)
        val names = route.names
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 14)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val head = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.section(host, "Penumpang per ruas"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(StudioUi.text(host, "per hari", 12f, true, StudioUi.MUTED))
        card.addView(head)
        val status = StudioUi.text(host, "Menghitung…", 13f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(6), 0, dp(6)) }
        card.addView(status)

        var out = DoubleArray(0)
        var back = DoubleArray(0)
        var periods: JSONArray? = null
        var picked = -1
        val readout = StudioUi.text(host, "", 13f, true, StudioUi.INK).apply {
            setPadding(dp(4), dp(6), 0, 0)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun busiest(dir: String, k: Int): String? {
            val ps = periods ?: return null
            var best = -1.0; var name = ""
            for (p in 0 until ps.length()) {
                val row = ps.optJSONObject(p) ?: continue
                val v = row.optJSONArray(dir)?.optDouble(k, 0.0) ?: 0.0
                if (v > best) { best = v; name = row.optString("name") }
            }
            return if (best > .5) "$name ${n(best)}" else null
        }
        fun describe(k: Int): String {
            val a = names.getOrElse(k) { "" }
            val b = names.getOrElse(k + 1) { "" }
            val up = busiest("out", k)?.let { " (tersibuk $it)" }.orEmpty()
            val down = busiest("back", k)?.let { " (tersibuk $it)" }.orEmpty()
            return "$a ke $b: ${n(out[k])} penumpang per hari$up\n$b ke $a: ${n(back[k])} penumpang per hari$down"
        }

        val plot = object : View(host) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(11).toFloat()
                this.color = StudioUi.MUTED
                typeface = StudioUi.face(host, true)
            }
            val box = RectF()
            val left get() = dp(8).toFloat()
            val right get() = width - dp(8).toFloat()
            val top get() = dp(18).toFloat()
            val bottom get() = height - dp(22).toFloat()
            val axis get() = (top + bottom) / 2

            fun stretchAt(x: Float): Int {
                val m = out.size
                if (m == 0) return -1
                return ((x - left) / ((right - left) / m)).toInt().coerceIn(0, m - 1)
            }

            fun pick(k: Int, speak: Boolean) {
                if (k < 0 || k == picked) return
                picked = k
                readout.text = describe(k)
                readout.visibility = View.VISIBLE
                contentDescription = summary()
                invalidate()
                if (speak) announceForAccessibility(describe(k))
            }

            fun summary(): String {
                if (out.isEmpty()) return "Grafik penumpang per ruas: menghitung"
                val most = out.indices.maxByOrNull { out[it] + back[it] } ?: 0
                val here = if (picked >= 0) " Dipilih: ${describe(picked).replace('\n', ' ')}." else ""
                return "Grafik penumpang per ruas, ${out.size} ruas. Tersibuk ${names.getOrElse(most) { "" }} – ${names.getOrElse(most + 1) { "" }}, " +
                    "${n(out[most] + back[most])} penumpang per hari kedua arah.$here Geser ke atas atau ke bawah untuk pindah ruas."
            }

            override fun onDraw(canvas: Canvas) {
                val m = out.size
                if (m == 0) return
                val most = maxOf(1.0, out.maxOrNull() ?: 0.0, back.maxOrNull() ?: 0.0)
                val half = axis - top
                val step = (right - left) / m
                val gap = minOf(dp(2).toFloat(), step * .15f)
                for (k in 0 until m) {
                    val x0 = left + k * step + gap / 2
                    val x1 = left + (k + 1) * step - gap / 2
                    if (k == picked) {
                        pen.color = 0x1f22204a
                        box.set(left + k * step, top - dp(4), left + (k + 1) * step, bottom + dp(4))
                        canvas.drawRoundRect(box, dp(4).toFloat(), dp(4).toFloat(), pen)
                    }
                    pen.color = color
                    box.set(x0, axis - (out[k] / most * half).toFloat(), x1, axis)
                    canvas.drawRect(box, pen)
                    pen.color = (color and 0x00ffffff) or 0x80000000.toInt()
                    box.set(x0, axis, x1, axis + (back[k] / most * half).toFloat())
                    canvas.drawRect(box, pen)
                }
                pen.color = StudioUi.INK
                canvas.drawRect(left, axis - dp(1) / 2f, right, axis + dp(1) / 2f, pen)
                // the stops on the axis, the ends bigger
                for (k in 0..m) {
                    val x = left + k * step
                    val end = k == 0 || k == m
                    if (!end && step < dp(6)) continue
                    pen.color = StudioUi.WHITE
                    canvas.drawCircle(x, axis, dp(if (end) 4 else 2).toFloat(), pen)
                    pen.color = StudioUi.INK
                    pen.style = Paint.Style.STROKE
                    pen.strokeWidth = dp(1).toFloat()
                    canvas.drawCircle(x, axis, dp(if (end) 4 else 2).toFloat(), pen)
                    pen.style = Paint.Style.FILL
                }
                label.textAlign = Paint.Align.LEFT
                canvas.drawText("${n(most)} ↑ ke ${names.lastOrNull().orEmpty()}", left, top - dp(5), label)
                canvas.drawText("↓ ke ${names.firstOrNull().orEmpty()}", left, height - dp(6).toFloat(), label)
                if (picked >= 0) {
                    label.textAlign = Paint.Align.RIGHT
                    canvas.drawText("${names.getOrElse(picked) { "" }} – ${names.getOrElse(picked + 1) { "" }}", right, height - dp(6).toFloat(), label)
                }
            }

            private val drag = StudioUi.SidewaysDrag()
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (out.isEmpty()) return false
                drag.track(this, e)
                pick(stretchAt(e.x), false)
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }

            override fun performClick(): Boolean { super.performClick(); return true }

            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                if (out.isNotEmpty()) {
                    info.isScrollable = true
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                val m = out.size
                if (m > 0 && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { pick(if (picked < 0) 0 else minOf(m - 1, picked + 1), true); return true }
                if (m > 0 && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { pick(if (picked < 0) 0 else maxOf(0, picked - 1), true); return true }
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            visibility = View.GONE
            isFocusable = true
            contentDescription = "Grafik penumpang per ruas: menghitung"
        }
        card.addView(plot, LinearLayout.LayoutParams(-1, dp(168)))
        card.addView(readout)

        fun doubles(a: JSONArray?) = DoubleArray(a?.length() ?: 0) { a!!.optDouble(it, 0.0).coerceAtLeast(0.0) }
        val fill: (JSONObject?) -> Unit = { r ->
            val s = r?.takeIf { it.optBoolean("forecast") }?.optJSONObject("stretches")
            val o = doubles(s?.optJSONArray("out"))
            val b = doubles(s?.optJSONArray("back"))
            if (s != null && o.isNotEmpty() && o.size == b.size && o.size == names.size - 1) {
                out = o; back = b; periods = s.optJSONArray("periods")
                status.visibility = View.GONE
                plot.visibility = View.VISIBLE
                if (picked >= o.size) picked = -1
                if (picked >= 0) readout.text = describe(picked)
                else {
                    readout.text = "Ketuk atau geser grafik untuk melihat penumpang tiap ruas."
                    readout.visibility = View.VISIBLE
                }
                plot.invalidate()
                // the summary after the arrays are in
                plot.contentDescription = run {
                    val most = o.indices.maxByOrNull { o[it] + b[it] } ?: 0
                    "Grafik penumpang per ruas, ${o.size} ruas. Tersibuk ${names.getOrElse(most) { "" }} – ${names.getOrElse(most + 1) { "" }}, " +
                        "${n(o[most] + b[most])} penumpang per hari kedua arah. Geser ke atas atau ke bawah untuk pindah ruas."
                }
            } else if (r == null || r.optBoolean("forecast") || out.isEmpty()) {
                status.text = if (r == null || r.optBoolean("forecast")) "Data penumpang muncul setelah simulasi berjalan." else "Menghitung…"
            }
        }
        return Chart(card, fill)
    }
}
