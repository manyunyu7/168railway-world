// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.NumberFormat
import java.util.Locale

/**
 * Paid parking and adverts, 168Railway's own (not in the web; extras.rs). While a world is open
 * they ride in its header, so undo, edit candidates and the clock carry them; on disk they sit in
 * `<save>.extras.json` beside the save, which stays as the web reads it.
 */
internal object WorldExtras {
    @JvmStatic external fun station(handle: Long, node: Int, clock: Double): String
    @JvmStatic external fun line(handle: Long, id: Int, clock: Double): String
    @JvmStatic external fun setPark(handle: Long, node: Int, fee: Double, size: Int, clock: Double): String
    @JvmStatic external fun setAd(handle: Long, trains: Boolean, id: Int, on: Boolean, clock: Double): String
    @JvmStatic external fun attach(handle: Long, text: String): Boolean
    @JvmStatic external fun split(bytes: ByteArray): ByteArray?

    private val ID = Locale.forLanguageTag("id-ID")

    fun file(save: File) = File(save.path + ".extras.json")

    /** The save as written to disk (without the extras), the extras written beside it or removed. */
    fun persist(save: File, bytes: ByteArray): ByteArray {
        val packed = split(bytes) ?: return bytes
        val n = ByteBuffer.wrap(packed, 0, 4).int
        val text = String(packed, 4, n, Charsets.UTF_8)
        val target = android.util.AtomicFile(file(save))
        if (n == 0) target.delete() else {
            val out = target.startWrite()
            try { out.write(text.toByteArray()); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
        }
        return packed.copyOfRange(4 + n, packed.size)
    }
    /** Puts the extras kept beside `save` into the opened world `handle`. */
    fun load(handle: Long, save: File) {
        val text = runCatching { android.util.AtomicFile(file(save)).readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return
        attach(handle, text)
    }
    fun copy(from: File, to: File) {
        val source = file(from)
        if (source.isFile) source.copyTo(file(to), overwrite = true)
    }
    fun delete(save: File) { android.util.AtomicFile(file(save)).delete() }

    private fun rp(v: Double) = "Rp " + NumberFormat.getIntegerInstance(ID).format(Math.round(v))
    private fun count(v: Double) = NumberFormat.getIntegerInstance(ID).format(Math.round(v))
    private fun money(host: Activity, millions: Double) = StudioFormat.money(millions * 1e6, StudioCurrency.current(host))
    private fun card(host: Activity) = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        background = StudioUi.card(host, 14)
        val p = StudioUi.dp(host, 12); setPadding(p + StudioUi.dp(host, 2), p, p, p)
    }
    private fun note(host: Activity, value: String) = StudioUi.text(host, value, 12f, true, StudioUi.MUTED).apply {
        setPadding(0, StudioUi.dp(host, 4), 0, 0)
    }
    private fun answer(host: Activity, r: JSONObject): Boolean {
        if (r.has("error")) { WorldSound.refused(host, r.getString("error")); return false }
        return true
    }

    /**
     * The station inspector's Parkir (with a car park) and Iklan stasiun sections, refreshed in
     * place after each change; every change goes through `transaction` and `changed` (undo).
     */
    fun stationSection(host: Activity, handle: Long, node: Int, time: Double, transaction: PanelEdit, changed: () -> Unit): View {
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        fun dp(n: Int) = StudioUi.dp(host, n)
        fun fill(clock: Double) {
            box.removeAllViews()
            val s = JSONObject(station(handle, node, clock))
            if (s.has("error")) return
            val p = s.getJSONObject("parking")
            val ad = s.getJSONObject("ad")
            val forecast = s.optBoolean("forecast")
            fun edit(apply: (Double) -> JSONObject) = transaction { now ->
                if (answer(host, apply(now))) changed()
                fill(now)
            }
            if (p.optBoolean("has")) {
                box.addView(StudioUi.section(host, "Parkir"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                val c = card(host)
                val slots = p.getDouble("slots")
                val size = p.getInt("size")
                val sizes = p.getJSONArray("sizes")
                val name = sizes.getJSONObject(size).getString("name")
                if (p.has("cars")) {
                    val cars = p.getDouble("cars"); val used = p.getDouble("used")
                    c.addView(StudioUi.text(host, "${count(used)} / ${count(slots)} slot terisi · $name", 14f, true))
                    c.addView(StudioProgress.Bar(host, if (cars > slots) StudioUi.RED else 0xff2fb36d.toInt(), StudioUi.TINT_GREY).apply {
                        fraction = (used / slots).toFloat()
                    }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })
                    if (cars > slots) c.addView(note(host, "Penuh: ${count(cars - slots)} mobil lagi ingin parkir di sini."))
                } else c.addView(StudioUi.text(host, "Kapasitas ${count(slots)} slot · $name", 14f, true))
                // fee stepper, Rp 0 to Rp 20.000 a day
                val fee = p.getDouble("fee"); val step = p.getDouble("feeStep"); val max = p.getDouble("feeMax")
                val row = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(StudioUi.text(host, "Tarif per hari", 14f, true, StudioUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(StudioUi.iconButton(host, "Turunkan tarif parkir", StudioUi.Icon.MINUS) {
                    if (fee > 0) edit { now -> JSONObject(setPark(handle, node, maxOf(0.0, fee - step), size, now)) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(StudioUi.text(host, rp(fee), 14f).apply {
                    typeface = StudioUi.heavy(host); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(92), -2))
                row.addView(StudioUi.iconButton(host, "Naikkan tarif parkir", StudioUi.Icon.PLUS) {
                    if (fee < max) edit { now -> JSONObject(setPark(handle, node, minOf(max, fee + step), size, now)) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                val income = if (p.has("incomeDay")) "Pemasukan ${money(host, p.getDouble("incomeDay"))}/hari · " else ""
                c.addView(note(host, income + "perawatan ${money(host, p.getDouble("upkeepDay"))}/hari"))
                val bonus = p.optDouble("bonus", Double.NaN).takeIf { !it.isNaN() }
                    ?: (p.getDouble("bonusFree") * (1 - fee / max * 0.6))
                c.addView(note(host, String.format(ID, "Datang dari jauh terasa %.1f menit lebih singkat (gratis: %.0f menit). Tarif makin mahal, makin sedikit yang naik di sini.",
                    bonus, p.getDouble("bonusFree"))))
                if (!forecast) c.addView(note(host, "Putar simulasi untuk melihat jumlah mobil dan pemasukan."))
                c.addView(StudioUi.button(host, "Ubah kapasitas") {
                    val names = Array(sizes.length()) { i ->
                        val z = sizes.getJSONObject(i)
                        val extra = z.getDouble("build") - sizes.getJSONObject(size).getDouble("build")
                        val cost = if (i == size) "terpasang" else if (extra > 0) "bangun ${money(host, extra)}" else "tanpa pengembalian dana"
                        "${z.getString("name")} · ${count(z.getDouble("slots"))} slot · $cost · rawat ${money(host, z.getDouble("upkeep"))}/hari"
                    }
                    StudioDialogBuilder(host).setTitle("Kapasitas parkir")
                        .setSingleChoiceItems(names, size) { d, i ->
                            d.dismiss()
                            if (i != size) edit { now -> JSONObject(setPark(handle, node, fee, i, now)) }
                        }
                        .setNegativeButton("Batal", null).show()
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
                box.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            if (s.optBoolean("built")) {
                box.addView(StudioUi.section(host, "Iklan stasiun"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                box.addView(adCard(host, ad, "stasiun", "naik dan turun", "people", forecast) { on ->
                    edit { now -> JSONObject(setAd(handle, false, node, on, now)) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        fill(time)
        return box
    }

    /** The line panel's Iklan kereta card (tab Ringkasan). */
    fun lineSection(host: Activity, handle: Long, id: Int, time: Double, editable: Boolean, transaction: PanelEdit, changed: () -> Unit): View {
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        fun fill(clock: Double) {
            box.removeAllViews()
            val ad = JSONObject(line(handle, id, clock))
            if (ad.has("error")) return
            box.addView(StudioUi.section(host, "Iklan kereta"))
            box.addView(adCard(host, ad, "kereta", "penumpang", "riders", ad.has("riders"), if (!editable) null else { on ->
                transaction { now ->
                    if (answer(host, JSONObject(setAd(handle, true, id, on, now)))) changed()
                    fill(now)
                }
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(host, 6) })
        }
        fill(time)
        return box
    }

    private fun adCard(host: Activity, ad: JSONObject, where: String, who: String, key: String, forecast: Boolean, set: ((Boolean) -> Unit)?): View {
        fun dp(n: Int) = StudioUi.dp(host, n)
        val c = card(host)
        val active = ad.optBoolean("active"); val signed = ad.optBoolean("signed")
        val days = ad.getDouble("days").toInt(); val price = money(host, ad.getDouble("price"))
        c.addView(StudioUi.text(host, when {
            active -> "Kontrak berjalan · sisa ${ad.getDouble("daysLeft").toInt()} hari"
            signed -> "Kontrak habis"
            else -> "Belum ada iklan"
        }, 15f, true, if (active) StudioUi.GOOD else StudioUi.INK))
        val income = if (ad.has("incomeDay")) money(host, ad.getDouble("incomeDay")) else null
        c.addView(note(host, (if (income != null) "Pemasukan $income/hari dari ${count(ad.optDouble(key))} $who. " else if (!forecast) "Putar simulasi untuk melihat perkiraan pemasukan. " else "") +
            "Kontrak $days hari, biaya pasang $price. Iklan sedikit mengurangi kenyamanan: sekitar 1% penumpang ${if (where == "stasiun") "di stasiun ini" else "line ini"} beralih."))
        if (set != null) {
            val row = LinearLayout(host)
            fun ask(title: String, on: Boolean) {
                StudioDialogBuilder(host).setTitle(title)
                    .setMessage(if (on) "Bayar $price untuk $days hari iklan ${where}${if (income != null) ", perkiraan pemasukan $income/hari" else ""}."
                        else "Iklan dicopot sekarang. Biaya kontrak tidak kembali.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton(if (on) "Bayar" else "Hentikan") { _, _ -> set(on) }.show()
            }
            if (active) row.addView(StudioUi.button(host, "Hentikan") { ask("Hentikan iklan?", false) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            row.addView(StudioUi.button(host, if (signed) "Perpanjang" else "Pasang iklan", StudioUi.Tone.PRIMARY) {
                ask(if (signed) "Perpanjang iklan?" else "Pasang iklan $where?", true)
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (active) marginStart = dp(5) })
            c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        return c
    }
}
