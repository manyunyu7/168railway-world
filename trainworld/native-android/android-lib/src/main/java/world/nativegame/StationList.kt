// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.PointF
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.cosh
import kotlin.math.hypot

/**
 * Web StationsTab (ui/StationsTab.tsx): every station with its boardings in the past 24 hours
 * (game/today.ts `stationsToday`, here line_summary.rs `stations`), sorted by Penumpang (most
 * first, the web's default), Jarak from the middle of the map (nearest first, the distance shown
 * as the web's Near column does only then) or Nama; a tap on a sort turns its order round. A tap
 * on a row opens the station and brings the map to it. Replaces the "Pilih stasiun" chooser, so
 * its search box stays. The figures come while the simulation runs and are asked again every two
 * seconds, as the line list's.
 *
 * Not ported: the web's complexes as one row (each station is its own row, as the web's "apart"
 * switch shows them), the lines' bullets on each row, renaming and hand ordering.
 */
internal object StationList {
    private enum class Key(val label: String, val first: Int) { RIDERS("Penumpang", -1), NEAR("Jarak", 1), NAME("Nama", 1) }
    private var key = Key.RIDERS
    private var dir = -1

    private const val FIRST = 100
    private const val MORE = 200
    private const val EARTH_M = 6371008.8f

    fun show(
        activity: Activity,
        stations: List<NetworkView.Station>,
        centre: PointF,
        summary: (((String?) -> Unit) -> Unit),
        open: (Int) -> Unit,
    ) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        // ground metres from the middle of the map (the plane's units shrink by cosh(y/R), as showStationAboveSheet's)
        val near = stations.associate { s ->
            s.id to (hypot((s.x - centre.x).toDouble(), (s.y - centre.y).toDouble()) / cosh(((s.y + centre.y) / 2f / EARTH_M).toDouble()))
        }
        val total = stations.groupingBy { it.name }.eachCount()
        StudioUi.sheet(activity, "Stasiun") { column, dialog ->
            var riders: Map<Int, Double>? = null
            var limit = FIRST
            val headline = StudioUi.text(activity, "", 13f, true, StudioUi.MUTED).apply { setPadding(dp(2), 0, 0, dp(6)) }
            column.addView(headline)
            val query = EditText(activity).apply {
                setSingleLine()
                hint = "Cari stasiun"
                StudioUi.field(this)
                setCompoundDrawablesRelative(StudioUi.glyph(activity, StudioUi.Icon.SEARCH, StudioUi.MUTED), null, null, null)
                compoundDrawablePadding = dp(8)
            }
            column.addView(query, LinearLayout.LayoutParams(-1, dp(48)))
            val sorts = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            column.addView(sorts, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            val chips = mutableListOf<Pair<Key, TextView>>()

            fun render() {
                chips.forEach { (k, v) ->
                    val on = k == key
                    v.text = k.label + if (on) (if (dir < 0) " ↓" else " ↑") else ""
                    v.background = StudioUi.pill(activity, if (on) StudioUi.INK else 0x1422204a, 16)
                    v.setTextColor(if (on) StudioUi.WHITE else StudioUi.INK)
                    v.contentDescription = "Urutkan menurut ${k.label.lowercase()}" + if (on) (if (dir < 0) ", menurun" else ", menaik") else ""
                }
                val figures = riders
                headline.text = "${StudioFormat.count(stations.size.toDouble())} stasiun · " +
                    if (figures != null) "penumpang = jumlah naik dalam 24 jam terakhir" else "angka penumpang muncul saat simulasi berjalan"
                val q = query.text.toString().trim()
                val byName = compareBy<NetworkView.Station, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                val order = when (key) {
                    Key.NAME -> if (dir > 0) byName else byName.reversed()
                    Key.RIDERS -> compareBy<NetworkView.Station> { dir * (figures?.get(it.id) ?: 0.0) }.then(byName)
                    Key.NEAR -> compareBy<NetworkView.Station> { dir * (near[it.id] ?: 0.0) }.then(byName)
                }
                val found = stations.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.sortedWith(order)
                list.removeAllViews()
                found.take(limit).forEach { s ->
                    val n = figures?.get(s.id)
                    val m = near[s.id] ?: 0.0
                    val twin = (total[s.name] ?: 1) > 1
                    list.addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(48)
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        background = StudioDepot.touch(activity, 0xd9ffffff.toInt(), 12)
                        isClickable = true; isFocusable = true
                        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        names.addView(StudioUi.text(activity, s.name, 14f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        // stations of one name say how far each is, so they can be told apart
                        if (twin || key == Key.NEAR) names.addView(StudioUi.text(activity, StudioFormat.km(m) + " dari tengah peta", 11.5f, false, StudioUi.MUTED))
                        addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                        addView(StudioUi.text(activity, if (n != null) StudioFormat.count(n) else "—", 14f, false, StudioUi.INK).apply {
                            typeface = StudioUi.heavy(activity); gravity = Gravity.END; maxLines = 1
                        })
                        contentDescription = s.name + ", " + (if (n != null) "${StudioFormat.count(n)} naik dalam 24 jam" else "penumpang belum dihitung") +
                            ", ${StudioFormat.km(m)} dari tengah peta, buka stasiun"
                        setOnClickListener { dialog.dismiss(); open(s.id) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }
                if (found.isEmpty()) list.addView(StudioUi.text(activity, if (q.isEmpty()) "Belum ada stasiun." else "Tidak ada stasiun bernama “$q”.", 13f, true, StudioUi.MUTED).apply {
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                })
                else if (found.size > limit) list.addView(StudioUi.button(activity, "Tampilkan ${minOf(MORE, found.size - limit)} lagi (${found.size - limit} tersisa)") {
                    limit += MORE; render()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }

            Key.values().forEach { k ->
                val chip = StudioUi.text(activity, k.label, 12.5f, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    isClickable = true
                    setOnClickListener {
                        if (key == k) dir = -dir else { key = k; dir = k.first }
                        render()
                    }
                }
                chips += k to chip
                // 40 dp drawn, 48 dp to touch (the bar's height gives the rest)
                sorts.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) })
            }
            query.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { limit = FIRST; render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            render()
            // web useToday: asked again while the list shows
            val again = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    summary { text ->
                        if (!dialog.isShowing) return@summary
                        val fresh = text?.let { parse(it) }
                        // while the riders are worked out again the answer comes back empty: keep the last
                        if (fresh != null && (fresh.isNotEmpty() || riders == null)) {
                            val changed = fresh != riders
                            riders = fresh
                            if (changed) render()
                        }
                        list.postDelayed(this, 2000)
                    }
                }
            }
            list.post(again)
        }
    }

    /** line_summary.rs's `stations`: [node, boardings] pairs. */
    private fun parse(text: String): Map<Int, Double>? = runCatching {
        val a = JSONObject(text).optJSONArray("stations") ?: return null
        buildMap { for (i in 0 until a.length()) a.optJSONArray(i)?.let { put(it.getInt(0), it.getDouble(1)) } }
    }.getOrNull()
}
