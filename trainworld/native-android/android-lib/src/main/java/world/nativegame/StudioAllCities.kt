// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The all cities view (web ui/inspector/AllCitiesView.tsx): the sums over every city with a built
 * station, as a city view lays out its figures, with the home and go-to buttons; below them each
 * city's row (web ui/Cities.tsx). The figures come from `summary` (src/all_cities.rs).
 */
object StudioAllCities {
    /**
     * The view's figures as JSON (src/all_cities.rs): `cities`, `totals`, `bounds` (west, south,
     * east, north of the built stations), `home`; `{"error"}` on failure. `manifest`: what
     * `NativeRidership.networkLayers` takes, or empty for stations, lines and money only. Shares
     * the forecast cache with the network layers; off the UI thread.
     */
    @JvmStatic external fun summary(save: ByteArray, manifest: String): String

    /** Web `WORLD_HOME`: the game opens on the all cities view. */
    const val WORLD_HOME = "world"

    private val executor = Executors.newSingleThreadExecutor()
    private val locale: Locale = Locale.forLanguageTag("id-ID")

    /** Web `count`: a count a day, short. */
    internal fun count(v: Double): String {
        val f = NumberFormat.getNumberInstance(locale)
        return when {
            v >= 1e9 -> f.apply { maximumFractionDigits = 2 }.format(v / 1e9) + " M"
            v >= 1e6 -> f.apply { maximumFractionDigits = 2 }.format(v / 1e6) + " jt"
            v >= 1e4 -> f.apply { maximumFractionDigits = 1 }.format(v / 1e3) + " rb"
            else -> f.apply { maximumFractionDigits = 0 }.format(v)
        }
    }
    /** Web `pct1`: a share in percent to one decimal. */
    internal fun pct1(v: Double) = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(v) + "%"
    private fun exact(v: Int) = NumberFormat.getIntegerInstance(locale).format(v)
    /** Fares less running trains and upkeep (web Cities.tsx `net`), millions a day. */
    internal fun net(m: JSONObject) = m.optDouble("fares", 0.0) - m.optDouble("running", 0.0) - m.optDouble("upkeep", 0.0)

    /**
     * Open the view. `save` and `manifest` are read on a background thread. `onLocate` gets the
     * built stations' bounds (every station's while none is built), `onCity` a tapped city's id,
     * `onHome` "world" to make the all cities view home or null to clear it; no home button
     * without it.
     */
    fun show(
        activity: Activity,
        save: () -> ByteArray,
        manifest: () -> String?,
        onLocate: ((DoubleArray) -> Unit)? = null,
        onCity: ((String) -> Unit)? = null,
        onHome: ((String?) -> Unit)? = null,
    ): Dialog {
        lateinit var body: LinearLayout
        val dialog = StudioUi.sheet(activity, "Semua kota") { column, _ ->
            body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(StudioUi.BLUE)
            }, LinearLayout.LayoutParams(-1, StudioUi.dp(activity, 40)))
            body.addView(StudioUi.text(activity, "Menjumlahkan semua kota…", 15f, true, StudioUi.MUTED).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            column.addView(body)
        }
        val task = executor.submit {
            val result = runCatching { JSONObject(summary(save(), manifest().orEmpty())) }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || !dialog.isShowing) return@runOnUiThread
                body.removeAllViews()
                val data = result.getOrNull()
                if (data == null || data.has("error")) {
                    val error = data?.optString("error") ?: result.exceptionOrNull()?.localizedMessage.orEmpty()
                    body.addView(StudioUi.row(activity, StudioUi.Icon.HELP, StudioUi.TINT_YELLOW, "Ringkasan belum tersedia", error.ifBlank { null }))
                    return@runOnUiThread
                }
                fill(activity, body, data, dialog, onLocate, onCity, onHome)
                body.announceForAccessibility("Ringkasan semua kota selesai dihitung")
            }
        }
        dialog.setOnDismissListener { task.cancel(false) }
        return dialog
    }

    private fun fill(
        activity: Activity, body: LinearLayout, data: JSONObject, dialog: Dialog,
        onLocate: ((DoubleArray) -> Unit)?, onCity: ((String) -> Unit)?, onHome: ((String?) -> Unit)?,
    ) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        fun gap(top: Int = 6) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        val money = StudioCurrency.current(activity)
        val t = data.getJSONObject("totals")
        val bounds = data.optJSONArray("bounds")
        // the title's buttons: home and go to every city
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (onHome != null) {
            var on = data.optString("home") == WORLD_HOME
            lateinit var home: android.widget.Button
            fun paint() {
                home.text = if (on) "Beranda ✓" else "Jadikan beranda"
                StudioUi.style(home, if (on) StudioUi.Tone.PRIMARY else StudioUi.Tone.SURFACE)
                home.setTextColor(if (on) StudioUi.WHITE else StudioUi.INK)
                home.contentDescription = if (on) "Beranda: kamu mulai dari tampilan semua kota setiap kali membuka game ini"
                    else "Jadikan beranda: mulai dari tampilan semua kota setiap kali kamu membuka game ini"
            }
            home = StudioUi.button(activity, "") {
                on = !on
                paint()
                onHome(if (on) WORLD_HOME else null)
            }
            paint()
            actions.addView(home, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (onLocate != null && bounds != null && bounds.length() == 4) {
            actions.addView(StudioUi.iconButton(activity, "Ke semua kota", StudioUi.Icon.CENTER) {
                dialog.dismiss()
                onLocate(DoubleArray(4) { bounds.getDouble(it) })
            }, LinearLayout.LayoutParams(dp(56), -2).apply { marginStart = dp(8) })
        }
        if (actions.childCount > 0) body.addView(actions, gap(2))

        fun figure(label: String, value: String, ink: Int = StudioUi.INK, tip: String? = null) {
            val v = StudioUi.text(activity, value, 15f, color = ink).apply { typeface = StudioUi.heavy(activity) }
            body.addView(StudioUi.row(activity, null, StudioUi.TINT_GREY, label, tip, v), gap())
        }
        val split = t.optJSONObject("split")
        val rail = t.optDouble("rail", Double.NaN)
        figure("Pangsa angkutan umum", split?.let { pct1(it.optDouble("train")) } ?: "–",
            tip = split?.let { s ->
                "Kereta ${pct1(s.optDouble("train"))} · jalan kaki ${pct1(s.optDouble("walk"))} · kendaraan ${pct1(s.optDouble("drive"))}" +
                    (s.optDouble("none", 0.0).takeIf { it >= .05 }?.let { " · tanpa akses ${pct1(it)}" } ?: "")
            })
        figure("Kota", exact(t.optInt("cities")))
        figure("Stasiun", exact(t.optInt("stations")))
        figure("Lini", exact(t.optInt("lines")))
        figure("Perjalanan angkutan umum", if (rail.isNaN()) "–" else count(rail) + "/hari",
            tip = if (rail.isNaN()) null else NumberFormat.getIntegerInstance(locale).format(rail) + " perjalanan angkutan umum per hari di semua kota")
        val points = t.optDouble("points", 0.0)
        figure("Poin", if (points > 0) count(points) else "–",
            tip = if (points > 0) NumberFormat.getIntegerInstance(locale).format(points) + " poin di semua kota" else null)
        val m = t.optJSONObject("money")
        if (m == null) figure("Laba", "–") else {
            val n = net(m)
            val tip = buildList {
                add((if (m.optBoolean("yesterday")) "Laba kemarin" else "Laba hari ini sejauh ini") + " di semua kota")
                add("Tarif ${money.compact(m.optDouble("fares"))} · operasi ${money.compact(-m.optDouble("running"))} · perawatan ${money.compact(-m.optDouble("upkeep"))}")
                if (m.optDouble("trains") != 0.0) add("Kereta baru ${money.compact(-m.optDouble("trains"))}")
                if (m.optDouble("build") != 0.0) add("Pembangunan ${money.compact(-m.optDouble("build"))}")
            }.joinToString("\n")
            figure("Laba", money.compact(n) + "/hari", if (n < 0) StudioUi.BAD else StudioUi.INK, tip)
        }

        // each city's row (web Cities.tsx), the most stations first
        val cities = data.optJSONArray("cities") ?: return
        if (cities.length() == 0) return
        body.addView(StudioUi.section(activity, "Per kota"), gap(10))
        val store = runCatching { StudioCatalog.get(activity) }.getOrNull()
        val rows = (0 until cities.length()).map { cities.getJSONObject(it) }.sortedByDescending { it.optInt("stations") }
        for (c in rows) {
            val id = c.getString("id")
            val name = runCatching { store!!.city(id).name }.getOrDefault(id.uppercase())
            val share = c.optJSONObject("split")?.let { pct1(it.optDouble("train")) } ?: "–"
            val profit = c.optJSONObject("money")?.let { money.compact(net(it)) + "/hari" } ?: "–"
            val sub = "${exact(c.optInt("stations"))} stasiun · ${exact(c.optInt("lines"))} lini · laba $profit"
            val trailing = StudioUi.text(activity, share, 15f, color = StudioUi.INK).apply {
                typeface = StudioUi.heavy(activity)
                contentDescription = "Pangsa angkutan umum $share"
            }
            body.addView(StudioUi.row(activity, StudioUi.Icon.PIN, StudioUi.TINT_PURPLE, name, sub, trailing,
                onCity?.let { go -> { dialog.dismiss(); go(id) } }), gap())
        }
    }
}
