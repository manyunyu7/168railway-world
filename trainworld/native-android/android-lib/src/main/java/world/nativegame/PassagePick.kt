// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * A new passage, as the web makes one (game/links.ts, StationUpgrades `PassageButton`, ToolNotes):
 * the station inspector's Lorong starts it, the next station tapped on the map is joined to the one
 * it started from (paid at once between two built stations, else built with them), a refused pick
 * (the same station, one complex already, past 1 km) says why and keeps picking, and Berhenti on the
 * note stops. The passages touching a station are listed in its inspector with Hapus (web
 * `StationLinks`). Link and unlink go through `StationInspector.structure`, inside the inspector's
 * panel edit, so the host saves, records the undo step and redraws the map as for any station change.
 */
internal object PassagePick {
    private class Pick(val activity: Activity, val handle: Long, val from: Int, val name: String,
                       val transaction: PanelEdit, val changed: () -> Unit, val reopen: (Double) -> Unit, val note: View)
    private var pick: Pick? = null

    val picking get() = pick != null

    /** Web `passageTip`: what a passage does, its reach and its price range. */
    fun tip(activity: Activity): String {
        val currency = StudioCurrency.current(activity)
        return "Menggabungkan dua stasiun yang berjarak hingga 1 km jadi satu kompleks supaya orang bisa pindah. " +
            "Biaya ${currency.compact(10.0)}–${currency.compact(100.0)} tergantung panjangnya."
    }

    fun start(activity: Activity, handle: Long, from: Int, name: String, transaction: PanelEdit,
              changed: () -> Unit, reopen: (Double) -> Unit) {
        stop()
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val note = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.pill(activity, StudioUi.INK, 24)
            setPadding(dp(16), dp(6), dp(6), dp(6))
            addView(StudioUi.text(activity, "Lorong baru dari $name", 14f, true, StudioUi.WHITE).apply { maxLines = 2 },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(StudioUi.button(activity, "Berhenti") { stop() }.apply { minHeight = dp(48); minimumHeight = dp(48) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            contentDescription = "Lorong baru dari $name. Ketuk stasiun lain dalam jarak 1 km untuk lorong."
        }
        content.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(12), dp(64), dp(12), 0)
        })
        pick = Pick(activity, handle, from, name, transaction, changed, reopen, note)
        Toast.makeText(activity, "Ketuk stasiun lain dalam jarak 1 km untuk lorong.", Toast.LENGTH_LONG).show()
    }

    fun stop() {
        val p = pick ?: return
        pick = null
        (p.note.parent as? ViewGroup)?.removeView(p.note)
    }

    /** A station tapped on the map while picking: true when the tap was taken for the passage. */
    fun tapped(station: Int): Boolean {
        val p = pick ?: return false
        if (p.activity.isFinishing) { stop(); return false }
        p.transaction { now ->
            val r = JSONObject(StationInspector.structure(p.handle, p.from, now,
                JSONObject().put("action", "link").put("id", station).toString()))
            if (r.has("error")) {
                // web: a refused pick says why and keeps picking
                Toast.makeText(p.activity, r.getString("error"), Toast.LENGTH_LONG).show()
                return@transaction
            }
            stop()
            val charge = r.optDouble("charge", 0.0)
            val a = r.optString("a"); val b = r.optString("b")
            Toast.makeText(p.activity, if (charge > 0) "Lorong antara $a dan $b dibangun dengan biaya ${StudioFormat.money(charge * 1e6, StudioCurrency.current(p.activity))}."
                else "Lorong antara $a dan $b ditambahkan. Lorong ini dibangun bersama stasiunnya.", Toast.LENGTH_LONG).show()
            if (r.optBoolean("changed")) p.changed()
            // web: the station the passage started from stays selected
            p.reopen(now)
        }
        return true
    }

    /** Web `StationLinks`: the passages touching the station (or any member of its complex), each
     *  with its length (and price) and Hapus. Null when there are none. */
    fun section(activity: Activity, handle: Long, rows: JSONArray?, members: Int, node: Int,
                transaction: PanelEdit, changed: () -> Unit, reopen: (Double) -> Unit): LinearLayout? {
        if (rows == null || rows.length() == 0) return null
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val currency = StudioCurrency.current(activity)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(StudioUi.section(activity, "Lorong"))
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val a = r.getInt("a"); val b = r.getInt("b")
            val aName = r.getString("aName"); val bName = r.getString("bName")
            val m = r.getDouble("metres")
            val built = r.getBoolean("built")
            val title = if (members > 1) "$aName dan $bName" else if (a == node) bName else aName
            val length = if (m >= 1000) String.format(java.util.Locale.forLanguageTag("id-ID"), "%.1f km", m / 1000) else "${Math.round(m)} m"
            val price = StudioFormat.money(r.getDouble("price") * 1e6, currency)
            // web linkTip: "{distance} garis lurus, {price}" (… "setelah kedua stasiun dibangun")
            val subtitle = "$length garis lurus, $price" + if (built) "" else " setelah kedua stasiun dibangun"
            val remove = StudioUi.button(activity, "Hapus") {
                transaction { now ->
                    val res = JSONObject(StationInspector.structure(handle, a, now, JSONObject().put("action", "unlink").put("id", b).toString()))
                    if (res.has("error")) Toast.makeText(activity, res.getString("error"), Toast.LENGTH_LONG).show()
                    else {
                        Toast.makeText(activity, "Lorong antara $aName dan $bName dihapus.", Toast.LENGTH_LONG).show()
                        if (res.optBoolean("changed")) changed()
                    }
                    reopen(now)
                }
            }.apply {
                minHeight = dp(48); minimumHeight = dp(48)
                contentDescription = "Hapus lorong $title" + if (built) ". Uang tidak kembali" else ""
            }
            box.addView(StudioUi.row(activity, StudioUi.Icon.ROUTE, StudioUi.TINT_PURPLE, title,
                subtitle + if (built) ". Uang tidak kembali" else "", remove), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        return box
    }
}
