// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * The station inspector's riders (web StationDetails' lines with `BoardingsToday` and
 * `PurposeSplit`): each line's boardings here in the past 24 hours, their total, the purposes of
 * the riders boarding here, and where riders from here go and riders here come from. Filled once
 * the simulation answers (train_riders.rs `station`); hidden while it has no riders.
 */
internal object StationRiders {
    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun pct(v: Double) = "${Math.round(v * 100)}%"

    /** Web `SegBar` split-bar: one segment per purpose in its colour. */
    private class SplitBar(activity: Activity, val parts: List<Pair<Double, Int>>) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            var x = 0f
            for ((share, color) in parts) {
                val w = (share * width).toFloat()
                paint.color = color or 0xff000000.toInt()
                canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
                x += w
            }
        }
    }

    fun section(activity: Activity, node: Int, read: ((Int, (String?) -> Unit) -> Unit)?): View? {
        read ?: return null
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = StudioUi.card(activity, 22)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        read(node) { text ->
            val r = text?.let(::JSONObject) ?: return@read
            fill(activity, box, r)
        }
        return box
    }

    private fun fill(activity: Activity, box: LinearLayout, r: JSONObject) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        fun rows(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getJSONObject(it) }
        fun row(label: String, value: String, color: Int? = null, bold: Boolean = false) {
            val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40) }
            if (color != null) line.addView(View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.BLACK or color)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
            line.addView(StudioUi.text(activity, label, 14f, true, if (bold || color != null) StudioUi.INK else StudioUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(StudioUi.text(activity, value, if (bold) 17f else 15f, true, StudioUi.INK).apply {
                if (bold) typeface = StudioUi.heavy(activity)
            })
            box.addView(line)
        }
        val lines = rows(r.optJSONArray("lines"))
        val purposes = rows(r.optJSONArray("purposes"))
        if (lines.isEmpty()) return
        box.removeAllViews()
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.section(activity, "Penumpang"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(StudioUi.text(activity, "naik 24 jam terakhir", 12f, true, StudioUi.MUTED))
        box.addView(head)
        for (l in lines) row(l.optString("name"), if (l.isNull("boardings")) "" else count.format(Math.round(l.optDouble("boardings"))), l.optInt("color"))
        row("Naik hari ini", if (r.isNull("boardings")) "" else count.format(Math.round(r.optDouble("boardings"))), bold = true)
        if (purposes.isNotEmpty()) {
            val words = purposes.joinToString("; ") { "${it.optString("name")}: ${pct(it.optDouble("share"))} dari naik" }
            box.addView(SplitBar(activity, purposes.map { it.optDouble("share") to it.optInt("color") }).apply {
                contentDescription = "Per tujuan: $words"
            }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6) })
            box.addView(StudioUi.text(activity, purposes.joinToString(" · ") { "${it.optString("name")} ${pct(it.optDouble("share"))}" },
                12.5f, true, StudioUi.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }
        fun ends(title: String, a: JSONArray?) {
            val list = rows(a)
            if (list.isEmpty()) return
            box.addView(StudioUi.section(activity, title).apply { setPadding(0, dp(10), 0, 0) })
            for (e in list) row(e.optString("name"), pct(e.optDouble("share")))
        }
        ends("Penumpang dari sini turun di", r.optJSONArray("to"))
        ends("Penumpang ke sini naik dari", r.optJSONArray("from"))
        box.visibility = View.VISIBLE
    }
}
