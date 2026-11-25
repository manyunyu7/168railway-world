// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web `DefaultHeadways` (ui/inspector/schedule.tsx, game/newHeadway.ts): the headways every new line
 * of this line's kind starts with, kept in the save header's `newHeadway` (src/service/defaults.rs),
 * with "Jadikan bawaan" and "Pakai bawaan". Also web `AverageSpeed` for the line panel.
 */
internal object ScheduleDefaults {
    @JvmStatic private external fun query(handle: Long, id: Int): String
    @JvmStatic private external fun makeDefault(handle: Long, id: Int, now: Double): String
    @JvmStatic private external fun useDefault(handle: Long, id: Int, now: Double): String

    const val LABEL = "Selang bawaan"
    /** Web `KIND_TITLE` (`schedule.defaultTitle*`). */
    private val titles = mapOf("rail" to "Selang untuk line biasa baru", "tram" to "Selang untuk line trem baru", "fast" to "Selang untuk line kecepatan tinggi baru")

    /** Web `headwayText` joined as `tList`: "5 mnt, 10 mnt, dan 20 mnt". */
    private fun headways(h: JSONArray): String {
        val parts = (0 until h.length()).map { val s = h.optDouble(it, 0.0); if (s > 0) "${ScheduleEditor.minutes(s)} mnt" else "tidak ada" }
        return if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + ", dan " + parts.last()
    }

    /**
     * The defaults block for the schedule editor, null for a bus or ferry line (they have none). Both
     * buttons edit the line itself, as the web's do, inside [transaction]; [done] gets the line's
     * fresh inspection on success.
     */
    fun section(host: Activity, handle: Long, id: Int, transaction: PanelEdit, done: (JSONObject) -> Unit): View? {
        if (handle == 0L) return null
        val info = runCatching { JSONObject(query(handle, id)) }.getOrNull() ?: return null
        val kind = info.optString("kind").takeIf { !info.isNull("kind") && it.isNotEmpty() } ?: return null
        val now = info.optJSONArray("default")
        val same = info.optBoolean("same")
        fun dp(n: Int) = StudioUi.dp(host, n)
        // compact: title and headways on one wrapped line, the two actions as small chips below
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 14)
            setPadding(dp(10), dp(6), dp(8), dp(0))
        }
        val title = titles[kind] ?: titles.getValue("rail")
        val value = if (now != null) headways(now) else "Belum ada bawaan"
        box.addView(StudioUi.text(host, "", 12.5f, true, StudioUi.MUTED).apply {
            text = android.text.SpannableStringBuilder(title).apply {
                setSpan(android.text.style.ForegroundColorSpan(StudioUi.INK), 0, length, 0)
                append("  ·  ").append(value)
            }
            contentDescription = "$title: $value"
        })
        fun run(label: String, call: (Double) -> String) {
            transaction { clock ->
                val r = JSONObject(call(clock))
                if (r.has("error")) {
                    WorldSound.knock()
                    Toast.makeText(host, r.getString("error"), Toast.LENGTH_LONG).show()
                } else {
                    ServicePanel.editLabel = label
                    done(r)
                }
            }
        }
        val buttons = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(StudioUi.choiceChip(host, "Jadikan bawaan", false) { run(LABEL) { makeDefault(handle, id, it) } }.apply {
            isEnabled = !same
            contentDescription = "Jadikan selang line ini bawaan untuk line baru sejenis"
        }, LinearLayout.LayoutParams(-2, dp(48)))
        buttons.addView(StudioUi.choiceChip(host, "Pakai bawaan", false) { run(LABEL) { useDefault(handle, id, it) } }.apply {
            isEnabled = now != null && !same
            contentDescription = "Pakai selang bawaan untuk line ini"
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        box.addView(buttons)
        return box
    }

    /** Web `AverageSpeed`: a row for the line panel, null when the line has no time or length yet. */
    fun averageSpeedRow(host: Activity, service: JSONObject): View? {
        if (service.isNull("averageSpeed")) return null
        val kmh = service.optDouble("averageSpeed", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: return null
        val value = "${Math.round(kmh)} km/jam"
        return StudioUi.row(host, null, 0, "Kecepatan rata-rata", null, StudioUi.text(host, value, 14f, true, StudioUi.INK)).apply {
            contentDescription = "Kecepatan rata-rata $value"
        }
    }
}
