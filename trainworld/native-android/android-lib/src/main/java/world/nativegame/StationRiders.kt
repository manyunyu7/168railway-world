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
 * The riders card at the top of the station inspector: riders a day getting on, getting off and
 * changing lines at the station (its complex), from the simulation's forecast of an average day
 * (train_riders.rs `station` `forecast`, `transfers_at`), with the boardings counted in the past 24
 * hours (web `BoardingsToday`), then each line's boardings here and the purposes of the riders
 * boarding (web StationDetails' lines and `PurposeSplit`).
 *
 * The card is always there. Until the simulation answers it says it is counting (or that Putar
 * starts the count) and asks again every few seconds while it is on screen, so the figures fill in
 * by themselves once the forecast is ready; after that it keeps the 24-hour count fresh.
 */
internal object StationRiders {
    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun pct(v: Double) = "${Math.round(v * 100)}%"
    private fun whole(v: Double) = count.format(Math.round(v))
    private const val WAIT_MS = 1500L
    private const val FRESH_MS = 5000L

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

    /**
     * [read] asks the simulation for the station's riders (null before they are in); [simulating]
     * says whether a simulation is open or being prepared, so the card can say Putar starts it.
     */
    fun section(activity: Activity, node: Int, read: ((Int, (String?) -> Unit) -> Unit)?, simulating: (() -> Boolean)?): View {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(activity, 22)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        var filled = false
        var shown: String? = null
        fun waiting() {
            box.removeAllViews()
            box.addView(StudioUi.section(activity, "Penumpang per hari"))
            val busy = simulating?.invoke() != false
            box.addView(StudioUi.text(activity,
                if (busy) "Menghitung penumpang…" else "Tekan Putar agar simulasi menghitung penumpang stasiun ini.",
                14f, true, StudioUi.MUTED).apply { setPadding(0, dp(6), 0, dp(2)) })
            if (busy) box.addView(android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(StudioUi.BLUE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) })
        }
        waiting()
        if (read == null) return box
        val ask = object : Runnable {
            override fun run() {
                read(node) { text ->
                    if (!box.isAttachedToWindow && filled) return@read
                    val r = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (r != null) { if (text != shown) { shown = text; fill(activity, box, r) }; filled = true }
                    else if (!filled) waiting()
                    // ask again while the card is on screen: first until the forecast is in, then for the 24-hour count
                    box.postDelayed({ if (box.isAttachedToWindow) run() }, if (filled) FRESH_MS else WAIT_MS)
                }
            }
        }
        // the first answer may arrive before the sheet is shown; later ones only while it is
        box.post { ask.run() }
        return box
    }

    private fun fill(activity: Activity, box: LinearLayout, r: JSONObject) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        fun rows(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getJSONObject(it) }
        val lines = rows(r.optJSONArray("lines"))
        val purposes = rows(r.optJSONArray("purposes"))
        val forecast = r.optJSONObject("forecast")
        val transfers = r.optDouble("transfers", 0.0).takeIf { it.isFinite() } ?: 0.0
        box.removeAllViews()
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(StudioUi.section(activity, "Penumpang per hari"), LinearLayout.LayoutParams(0, -2, 1f))
        if (forecast != null) head.addView(StudioUi.text(activity, "perkiraan hari biasa", 12f, true, StudioUi.MUTED))
        box.addView(head)
        if (lines.isEmpty()) {
            box.addView(StudioUi.text(activity, "Belum ada line yang berhenti di sini.", 14f, true, StudioUi.MUTED)
                .apply { setPadding(0, dp(6), 0, 0) })
            return
        }
        // the three figures side by side: on, off and (when there are any) changes between lines
        val tiles = LinearLayout(activity).apply { setPadding(0, dp(4), 0, dp(2)) }
        fun tile(label: String, value: String, tint: Int) {
            val t = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.pill(activity, tint, 12)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                contentDescription = "$label $value penumpang per hari"
            }
            t.addView(StudioUi.text(activity, value, 16f, true, StudioUi.INK).apply { typeface = StudioUi.heavy(activity); maxLines = 1 })
            t.addView(StudioUi.text(activity, label, 11.5f, true, StudioUi.MUTED).apply { maxLines = 1 })
            tiles.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        val none = "—"
        tile("Naik", forecast?.let { whole(it.optDouble("on")) } ?: none, StudioUi.TINT_BLUE)
        tile("Turun", forecast?.let { whole(it.optDouble("off")) } ?: none, StudioUi.TINT_GREEN)
        if (transfers >= .5) tile("Pindah line", whole(transfers), StudioUi.TINT_PURPLE)
        box.addView(tiles)
        if (forecast == null) box.addView(StudioUi.text(activity, "Perkiraan harian belum tersedia untuk line di sini.", 12.5f, true, StudioUi.MUTED))
        fun row(label: String, value: String, color: Int? = null, bold: Boolean = false) {
            val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(32) }
            if (color != null) line.addView(View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.BLACK or color)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
            line.addView(StudioUi.text(activity, label, 13f, true, if (bold || color != null) StudioUi.INK else StudioUi.MUTED).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(StudioUi.text(activity, value, if (bold) 15f else 13f, true, StudioUi.INK).apply {
                if (bold) typeface = StudioUi.heavy(activity)
            })
            box.addView(line)
        }
        // web BoardingsToday: counted in the past 24 hours of play, blank before the first count
        row("Naik 24 jam terakhir", if (r.isNull("boardings")) "Belum terhitung" else whole(r.optDouble("boardings")), bold = true)
        if (lines.size > 1 || !r.isNull("boardings"))
            for (l in lines) row(l.optString("name"), if (l.isNull("boardings")) "" else whole(l.optDouble("boardings")), l.optInt("color"))
        if (purposes.isNotEmpty()) {
            val words = purposes.joinToString("; ") { "${it.optString("name")}: ${pct(it.optDouble("share"))} dari naik" }
            box.addView(SplitBar(activity, purposes.map { it.optDouble("share") to it.optInt("color") }).apply {
                contentDescription = "Per tujuan: $words"
            }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6) })
            box.addView(StudioUi.text(activity, purposes.joinToString(" · ") { "${it.optString("name")} ${pct(it.optDouble("share"))}" },
                12.5f, true, StudioUi.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }
    }
}
