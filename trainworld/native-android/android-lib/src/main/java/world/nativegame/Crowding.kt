// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * The running simulation's crowding report (src/crowding.rs), one answer read by every panel that
 * shows crowding as the web does: the schedule's crowding dots per demand level and their peak
 * (web schedule.tsx `LevelName`, game/today.ts `crowdLevel`), the line's average load, trains that
 * left full and riders left on the platform in the past 24 hours (`PastDay`), the Issues list's
 * "Line X padat" rows (ui/TroubleList.tsx), and each stretch's average and peak load for the
 * Traffic view and the track panel (map/trackHover.ts).
 *
 * Asked again at most every [EVERY_MS] (the Issues list asks each game hour and after edits).
 */
internal object Crowding {
    /** Set by the activity: asks the running simulation (null answer without one). */
    var ask: (((String?) -> Unit) -> Unit)? = null
    var report: JSONObject? = null
        private set
    private var lines = emptyMap<Int, JSONObject>()
    /** crowded stretches, line * 4096 + stretch: [line, stretch, load, hour, colour] */
    private var stretches = emptyMap<Int, JSONArray>()
    private var asking = false
    private var askedAt = 0L
    private const val EVERY_MS = 15_000L
    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    fun whole(v: Double): String = count.format(Math.round(v))

    /** Ask again unless asked within [EVERY_MS] ([force]: unless an answer is on its way). */
    fun refresh(force: Boolean = false, landed: () -> Unit = {}) {
        val wall = SystemClock.elapsedRealtime()
        if (asking || (!force && wall - askedAt < EVERY_MS)) return
        val read = ask ?: return
        asking = true; askedAt = wall
        read { text ->
            asking = false
            val r = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            report = r
            lines = r?.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.optInt("id") } } ?: emptyMap()
            stretches = r?.optJSONArray("crowded")?.let { a ->
                (0 until a.length()).map { a.getJSONArray(it) }.associateBy { it.optInt(0) * 4096 + it.optInt(1) }
            } ?: emptyMap()
            landed()
        }
    }

    fun line(id: Int): JSONObject? = lines[id]
    /** The crowded stretch `k` of line `line` (run 0's numbering): [line, k, load, hour, colour], or null. */
    fun crowded(line: Int, k: Int): JSONArray? = stretches[line * 4096 + k]

    /** Web `loadText`: "132%". */
    fun loadText(share: Double) = "${Math.round(share * 100)}%"
    /** Web `clockTime` of a whole hour: "08:00". */
    private fun hour(h: Int) = String.format(Locale.ROOT, "%02d:00", h)
    private fun unit(vehicle: String) = when (vehicle) { "tram" -> "trem"; "bus" -> "bus"; "boat" -> "kapal"; else -> "kereta" }

    /** Web trackHover `avgLoad` and `peakLoad` for line `line`'s stretch `k`: "Muatan rata-rata 85% · Muatan puncak 132% pukul 08:00". */
    fun stretchText(line: Int, k: Int): String? {
        if (k < 0) return null
        val parts = mutableListOf<String>()
        line(line)?.optJSONArray("stretches")?.takeIf { k < it.length() && !it.isNull(k) }?.let {
            parts.add("Muatan rata-rata ${Math.round(it.getDouble(k) * 100)}%")
        }
        crowded(line, k)?.let { parts.add("Muatan puncak ${loadText(it.getDouble(2))} pukul ${hour(it.getInt(3))}") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Web `crowdLevel` for demand level `level` (0 high, 1 medium, 2 low) of line `id`, or null. */
    fun level(id: Int, level: Int): JSONObject? = line(id)?.optJSONArray("levels")?.optJSONObject(level)

    /** The crowding dot (web `.crowd-dot`): the fill graded over the stretches, a ring at the fullest. */
    fun dot(context: Context, c: JSONObject): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val r = minOf(width, height) / 2f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * d
            paint.color = c.optInt("ring") or 0xff000000.toInt()
            canvas.drawCircle(width / 2f, height / 2f, r - .8f * d, paint)
            paint.style = Paint.Style.FILL
            paint.color = c.optInt("fill") or 0xff000000.toInt()
            canvas.drawCircle(width / 2f, height / 2f, r - 3f * d, paint)
        }
    }.apply { contentDescription = tip(c) }

    /** The dot's hover as text: "Muatan puncak 132%", then each period's load where the level has several. */
    fun tip(c: JSONObject): String {
        val t = c.optJSONArray("tip") ?: return ""
        return (0 until t.length()).joinToString("\n") { t.getString(it) }
    }

    /** The bar of the stretches' crowding under the dot's hover (web `tipStrip`). */
    fun strip(context: Context, c: JSONObject): View? {
        val s = c.optJSONArray("strip")?.takeIf { it.length() > 0 } ?: return null
        return object : View(context) {
            private val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat() / s.length()
                for (i in 0 until s.length()) {
                    paint.color = s.getInt(i) or 0xff000000.toInt()
                    canvas.drawRect(i * w, 0f, (i + 1) * w + .5f, height.toFloat(), paint)
                }
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }

    /**
     * Web schedule.tsx `PastDay`'s crowding rows for line `id`: the average load, its vehicles that
     * left full and (not for a bus) riders left behind, in the past 24 hours; null with no report.
     */
    fun pastDay(host: Activity, id: Int): View? {
        val l = line(id) ?: return null
        val past = l.optJSONObject("past")
        val average = if (l.isNull("average")) null else l.optDouble("average")
        if (past == null && average == null) return null
        fun dp(n: Int) = StudioUi.dp(host, n)
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 14)
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        box.addView(StudioUi.section(host, "24 jam terakhir"))
        fun row(label: String, value: String, note: String, bad: Boolean = false) {
            val r = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36); contentDescription = "$label $value. $note" }
            r.addView(StudioUi.text(host, label, 13.5f, true, StudioUi.INK), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(StudioUi.text(host, value, 14f, true, if (bad) StudioUi.BAD else StudioUi.INK).apply { typeface = StudioUi.heavy(host) })
            box.addView(r)
        }
        val vehicle = l.optString("vehicle", "train")
        val u = unit(vehicle)
        if (average != null) row("Muatan rata-rata", "${Math.round(average * 100)}%", "Muatan rata-rata hari ini di seluruh lini")
        if (past != null) {
            val full = past.optDouble("full"); val trains = past.optDouble("trains")
            val left = past.optDouble("left"); val riders = past.optDouble("riders")
            if (full > 0) row(when (vehicle) { "tram" -> "Trem berangkat penuh"; "bus" -> "Bus berangkat penuh"; "boat" -> "Kapal berangkat penuh"; else -> "Kereta berangkat penuh" },
                "${whole(full)}/${whole(trains)}", "${whole(full)}/${whole(trains)} $u berangkat penuh.")
            if (vehicle != "bus") row(when (vehicle) { "boat" -> "Penumpang tertinggal di dermaga"; "tram" -> "Penumpang tertinggal di perhentian"; else -> "Penumpang tertinggal di peron" },
                "${whole(left)}/${whole(riders + left)}", "${whole(left)}/${whole(riders + left)} penumpang tertinggal.", left >= 1)
        }
        return box
    }

    /**
     * [pastDay]'s rows as one small line for the schedule editor: "24 jam · Muatan rata-rata 48% ·
     * Tertinggal di peron 0/570.974", riders left behind in red; null with no report.
     */
    fun pastDayLine(host: Activity, id: Int): View? {
        val l = line(id) ?: return null
        val past = l.optJSONObject("past")
        val average = if (l.isNull("average")) null else l.optDouble("average")
        if (past == null && average == null) return null
        val vehicle = l.optString("vehicle", "train")
        val u = unit(vehicle)
        val text = android.text.SpannableStringBuilder("24 jam terakhir")
        val spoken = mutableListOf("24 jam terakhir")
        fun part(label: String, value: String, note: String, bad: Boolean = false) {
            text.append("  ·  ").append(label).append(" ")
            val start = text.length
            text.append(value)
            text.setSpan(android.text.style.ForegroundColorSpan(if (bad) StudioUi.BAD else StudioUi.INK), start, text.length, 0)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length, 0)
            spoken.add(note)
        }
        if (average != null) part("Muatan rata-rata", "${Math.round(average * 100)}%", "Muatan rata-rata ${Math.round(average * 100)}%")
        if (past != null) {
            val full = past.optDouble("full"); val trains = past.optDouble("trains")
            val left = past.optDouble("left"); val riders = past.optDouble("riders")
            if (full > 0) part("Berangkat penuh", "${whole(full)}/${whole(trains)}", "${whole(full)}/${whole(trains)} $u berangkat penuh")
            if (vehicle != "bus") part(when (vehicle) { "boat" -> "Tertinggal di dermaga"; "tram" -> "Tertinggal di perhentian"; else -> "Tertinggal di peron" },
                "${whole(left)}/${whole(riders + left)}", "${whole(left)}/${whole(riders + left)} penumpang tertinggal", left >= 1)
        }
        return StudioUi.text(host, "", 12f, true, StudioUi.MUTED).apply {
            setText(text)
            contentDescription = spoken.joinToString(". ")
        }
    }

    /** The Issues list's crowded lines (web TroubleList tiers 3 and 5), from the last report. */
    fun troubles(): List<JSONObject> = report?.optJSONArray("troubles")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    /** Web TroubleList's crowding row: "{name} padat" and its hover, the last line what to try. */
    fun describe(item: JSONObject): Pair<String, String> {
        val v = item.optString("vehicle", "train")
        val u = unit(v)
        val full = whole(item.optDouble("full")); val trains = whole(item.optDouble("trains"))
        val fix = "Coba: " + when (v) { "bus" -> "jalankan lebih banyak bus"; "boat" -> "jalankan lebih banyak kapal"; else -> "jalankan lebih banyak $u, atau yang lebih panjang" }
        val at = when (v) { "train" -> "di peron"; "boat" -> "di dermaga"; else -> "di perhentian" }
        val what = if (item.optInt("tier") == 3)
            "${whole(item.optDouble("left"))} dari ${whole(item.optDouble("wanted"))} penumpang yang mau naik $u dalam 24 jam terakhir tertinggal $at karena sudah penuh\n$full/$trains $u berangkat penuh"
        else "$full/$trains $u berangkat penuh dalam 24 jam terakhir"
        return "${item.optString("name")} padat" to "$what\n$fix"
    }
}
