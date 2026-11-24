// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject
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

    /** Fares less running trains and upkeep (web Cities.tsx `net`), millions of US$ a day. */
    internal fun net(m: JSONObject) = m.optDouble("fares", 0.0) - m.optDouble("running", 0.0) - m.optDouble("upkeep", 0.0)

    /** Web `STAR_SHARE`, `POINTS_PER_TRIP`, `POINTS_PER_KM` (game/points.ts). */
    private const val STAR_SHARE = 10
    private const val POINTS_PER_TRIP = 20
    private const val POINTS_PER_KM = 1

    /** Web bar colours (game/palette.ts MIX_MODE_COLOURS, `--none-grey`): train, walk, drive, no route. */
    private val MODE = intArrayOf(0xff1e3ce6.toInt(), 0xff1eaa3c.toInt(), 0xffe61e1e.toInt(), 0xffb6b6b6.toInt())

    /** The Cities tab's sort (web game/ui.ts `citySort`, Perjalanan from the most down by default). */
    private enum class Sort(val label: String, val tip: String, val first: Int) {
        NAME("Nama", "Urutkan menurut nama", 1),
        TRAIN("Angkutan umum", "Urutkan menurut pangsa perjalanan naik angkutan umum", -1),
        RIDES("Perjalanan", "Urutkan menurut perjalanan angkutan umum per hari", -1),
        MONEY("Laba", "Urutkan menurut laba per hari", -1),
        POINTS("Poin", "Urutkan menurut poin", -1),
    }
    private var sort = Sort.RIDES
    private var dir = -1

    /**
     * Web `splitTip` with the rides (game/splitTip.ts): "Perjalanan per hari di semua kota: Angkutan
     * umum 12,3% (5 rb), Jalan kaki …, Mobil …, total 40 rb perjalanan"; No route only where some
     * trips have none.
     */
    internal fun splitTip(where: String, row: JSONObject): String {
        val s = row.optJSONObject("split")
        val pct = { k: String -> s?.let { StudioFormat.pct1(it.optDouble(k, 0.0)) } ?: "–" }
        if (!row.has("trips")) {
            return "Perjalanan per hari $where: Angkutan umum ${pct("train")}, Jalan kaki ${pct("walk")}, Mobil ${pct("drive")}"
        }
        val n = { k: String -> StudioFormat.count(row.optDouble(k, 0.0)) }
        val none = (s?.optDouble("none", 0.0) ?: 0.0) > 0
        return "Perjalanan per hari $where: Angkutan umum ${pct("train")} (${n("rail")}), Jalan kaki ${pct("walk")} (${n("walk")}), " +
            "Mobil ${pct("drive")} (${n("drive")}), " + (if (none) "Tanpa rute ${pct("none")} (${n("none")}), " else "") +
            "total ${n("trips")} perjalanan"
    }

    /** Web `moneyTip` (Cities.tsx): the profit line, fares, running and upkeep, then new trains and construction when any. */
    internal fun moneyTip(name: String, m: JSONObject, currency: StudioCurrency): String {
        val usd = { k: String -> m.optDouble(k, 0.0) * 1e6 }
        val money = { v: Double -> StudioFormat.money(v, currency) }
        val profit = money(net(m) * 1e6)
        return buildList {
            add(if (m.optBoolean("yesterday")) "Laba kemarin di $name: $profit/hari" else "Laba hari ini sejauh ini di $name: $profit/hari")
            add("Tarif ${money(usd("fares"))}")
            add("Operasi kereta ${money(-usd("running"))}")
            add("Perawatan ${money(-usd("upkeep"))}")
            if (m.optDouble("trains", 0.0) != 0.0) add("Kereta baru ${money(-usd("trains"))}")
            if (m.optDouble("build", 0.0) != 0.0) add("Pembangunan ${money(-usd("build"))}")
        }.joinToString("\n")
    }

    /** The mode split as the Cities tab draws it: train, walk, drive and no route side by side. */
    private fun bar(activity: Activity, split: JSONObject?): View = LinearLayout(activity).apply {
        background = StudioUi.pill(activity, StudioUi.BORDER, 3)
        clipToOutline = true
        val parts = listOf("train", "walk", "drive", "none").map { split?.optDouble(it, 0.0)?.coerceAtLeast(0.0) ?: 0.0 }
        parts.forEachIndexed { i, v ->
            if (v > 0) addView(View(activity).apply { setBackgroundColor(MODE[i]) }, LinearLayout.LayoutParams(0, -1, v.toFloat()))
        }
        val rest = 100 - parts.sum()
        if (rest > 0) addView(View(activity), LinearLayout.LayoutParams(0, -1, rest.toFloat()))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

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
        val currency = StudioCurrency.current(activity)
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
        // web AllCitiesView.tsx, row for row
        val split = t.optJSONObject("split")
        val seen = t.has("rail")
        figure("Pangsa angkutan umum", split?.let { StudioFormat.pct1(it.optDouble("train")) } ?: "–",
            tip = splitTip("di semua kota", t))
        figure("Kota", StudioFormat.exact(t.optDouble("cities")))
        figure("Stasiun", StudioFormat.exact(t.optDouble("stations")))
        figure("Lini", StudioFormat.exact(t.optDouble("lines")))
        figure("Perjalanan angkutan umum", if (seen) StudioFormat.count(t.optDouble("rail")) + "/hari" else "–",
            tip = if (seen) StudioFormat.exact(t.optDouble("rail")) + " perjalanan angkutan umum per hari di semua kota" else null)
        val points = t.optDouble("points", 0.0)
        figure("Poin", StudioFormat.points(points), tip = StudioFormat.exact(points) + " poin di semua kota")
        val m = t.optJSONObject("money")
        if (m == null) figure("Laba", "–") else {
            val n = net(m) * 1e6
            figure("Laba", StudioFormat.money(n, currency) + "/hari",
                if (StudioFormat.moneyNegative(n, currency)) StudioUi.BAD else StudioUi.INK, moneyTip("semua kota", m, currency))
        }

        // each city's row (web Cities.tsx): its split bar, rides, profit, points and star
        val cities = data.optJSONArray("cities") ?: return
        if (cities.length() == 0) return
        body.addView(StudioUi.section(activity, "Per kota"), gap(10))
        val store = runCatching { StudioCatalog.get(activity) }.getOrNull()
        val rows = (0 until cities.length()).map { cities.getJSONObject(it) }
        val names = rows.associate { c -> c.getString("id").let { id -> id to runCatching { store!!.city(id).name }.getOrDefault(id.uppercase()) } }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val heads = WrapRow(activity)
        val collate = java.text.Collator.getInstance(Locale.forLanguageTag("id-ID")).apply { strength = java.text.Collator.PRIMARY }
        fun value(c: JSONObject): Double? = when (sort) {
            Sort.TRAIN -> c.optJSONObject("split")?.optDouble("train")
            Sort.RIDES -> if (c.has("rail")) c.optDouble("rail") else null
            Sort.MONEY -> c.optJSONObject("money")?.let(::net)
            Sort.POINTS -> c.optDouble("points", 0.0)
            Sort.NAME -> null
        }
        fun draw() {
            heads.removeAllViews()
            for (k in Sort.values()) {
                val on = k == sort
                heads.addView(StudioUi.chip(activity, k.label + if (on) (if (dir < 0) " ↓" else " ↑") else "",
                    if (on) StudioUi.INK else StudioUi.TINT_GREY, if (on) StudioUi.WHITE else StudioUi.INK).apply {
                    contentDescription = k.tip + if (on) (if (dir < 0) ", dari terbesar" else ", dari terkecil") else ""
                    isClickable = true
                    setOnClickListener {
                        if (sort == k) dir = -dir else { sort = k; dir = k.first }
                        draw()
                    }
                })
            }
            list.removeAllViews()
            // a city with no figure yet sorts last whichever way the column runs; ties by name
            val sorted = rows.sortedWith { a, b ->
                val x = value(a); val y = value(b)
                val byValue = when {
                    sort == Sort.NAME -> 0
                    x == null || y == null -> if (x == y) 0 else if (x == null) 1 else -1
                    else -> (x.compareTo(y)) * dir
                }
                if (byValue != 0) byValue else collate.compare(names[a.getString("id")], names[b.getString("id")]) * (if (sort == Sort.NAME) dir else 1)
            }
            for (c in sorted) list.addView(cityRow(activity, c, names.getValue(c.getString("id")), currency, dialog, onCity), gap())
        }
        body.addView(heads, gap(4))
        body.addView(list)
        draw()
    }

    private fun cityRow(activity: Activity, c: JSONObject, name: String, currency: StudioCurrency, dialog: Dialog, onCity: ((String) -> Unit)?): View {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val id = c.getString("id")
        val seen = c.has("rail")
        val share = c.optJSONObject("split")?.let { StudioFormat.pct1(it.optDouble("train")) } ?: "–"
        val m = c.optJSONObject("money")
        val profit = m?.let { net(it) * 1e6 }
        val star = !c.isNull("star") && c.has("star")
        val stations = c.optInt("stations")
        val figures = listOf(
            "Perjalanan" to (if (seen) StudioFormat.count(c.optDouble("rail")) else "–"),
            "Laba" to (profit?.let { StudioFormat.money(it, currency) } ?: "–"),
            "Poin" to StudioFormat.points(c.optDouble("points", 0.0)),
        )
        val trailing = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(StudioUi.text(activity, share, 15f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(activity); gravity = Gravity.END })
            addView(bar(activity, c.optJSONObject("split")), LinearLayout.LayoutParams(dp(56), dp(8)).apply { topMargin = dp(3) })
        }
        val sub = figures.joinToString(" · ") { (k, v) -> "$k $v" }
        val row = StudioUi.row(activity, StudioUi.Icon.PIN, StudioUi.TINT_PURPLE, if (star) "$name ★" else name, sub, trailing,
            onCity?.let { go -> { dialog.dismiss(); go(id) } })
        // the web's hovers, read out instead
        row.contentDescription = buildList {
            add(name)
            add("$stations stasiun")
            if (star) add("Bintang diraih saat pangsa angkutan umum di $name mencapai $STAR_SHARE%")
            add(splitTip("di $name", c))
            if (seen) add(StudioFormat.exact(c.optDouble("rail")) + " perjalanan angkutan umum per hari di $name")
            m?.let { add(moneyTip(name, it, currency)) }
            add(StudioFormat.exact(c.optDouble("points", 0.0)) + " poin di $name")
            add("Dapat $POINTS_PER_TRIP poin per perjalanan dan $POINTS_PER_KM poin per km yang ditempuh di hari kerja")
        }.joinToString(". ")
        if (profit != null && StudioFormat.moneyNegative(profit, currency)) {
            // the web's red profit (`neg`), on the profit figure alone
            val label = (row.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? android.widget.TextView
            val shown = figures[1].let { "${it.first} ${it.second}" }
            val at = sub.indexOf(shown)
            if (label != null && at >= 0) label.text = android.text.SpannableString(sub).apply {
                setSpan(android.text.style.ForegroundColorSpan(StudioUi.BAD), at, at + shown.length, 0)
            }
        }
        return row
    }
}
