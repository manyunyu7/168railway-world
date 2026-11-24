package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Web LinesTab's rows (ui/LinesTab.tsx): each line, or group of lines, with its riders in the past
 * 24 hours, its fares for the whole day, its running cost and upkeep, and its expected profit today
 * (`MoneyToday`, red at a loss); a group opens with its chevron to show its lines, each with its own
 * figures (`GroupFigures` adds them up). Sorted like the web's column heads, riders first.
 */
internal class LineList(
    private val host: Activity,
    private val currency: StudioCurrency,
    private val looks: Map<Int, JSONObject?>,
    private val pick: (Int) -> Unit,
) {
    val rows = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    /** line_summary.rs's answer, null while there is none (no running simulation). */
    var figures: JSONObject? = null
        set(value) {
            field = value
            byId = buildMap {
                val ls = value?.optJSONArray("lines") ?: return@buildMap
                for (i in 0 until ls.length()) ls.optJSONObject(i)?.let { put(it.optInt("id"), it) }
            }
        }
    private var byId: Map<Int, JSONObject> = emptyMap()
    val totalRiders: Double? get() = figures?.optDouble("riders")?.takeIf { it.isFinite() }

    private enum class Key(val label: String, val first: Int) { RIDERS("Penumpang", -1), MONEY("Laba", -1), TRAINS("Armada", -1), NAME("Nama", 1) }
    private var key = Key.RIDERS
    private var dir = -1
    private val open = HashSet<String>()

    private fun dp(n: Int) = StudioUi.dp(host, n)

    /** Web SortHead: a tap sorts by the column, again turns the order round. */
    fun sorts(changed: () -> Unit): View {
        val bar = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        val chips = mutableListOf<Pair<Key, TextView>>()
        fun paint() = chips.forEach { (k, v) ->
            val on = k == key
            v.text = k.label + if (on) (if (dir < 0) " ↓" else " ↑") else ""
            v.background = StudioUi.pill(host, if (on) StudioUi.INK else 0x1422204a, 16)
            v.setTextColor(if (on) StudioUi.WHITE else StudioUi.INK)
            v.contentDescription = "Urutkan menurut ${k.label.lowercase()}" + if (on) (if (dir < 0) ", menurun" else ", menaik") else ""
        }
        bar.addView(StudioUi.text(host, "Urutkan", 12.5f, true, StudioUi.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        Key.values().forEach { k ->
            val chip = StudioUi.text(host, k.label, 13f, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                setOnClickListener {
                    if (key == k) dir = -dir else { key = k; dir = k.first }
                    paint(); changed()
                }
            }
            chips += k to chip
            bar.addView(chip, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) })
        }
        paint()
        return android.widget.HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    /** A line's or group's figures: riders, fares, running and upkeep, profit; nulls where none. */
    private class Sum(var riders: Double? = null, var fares: Double = 0.0, var cost: Double = 0.0, var profit: Double = 0.0, var money: Boolean = false, var trains: Double = 0.0, var running: Boolean = false, var status: String = "planned")

    private fun sum(group: List<JSONObject>): Sum {
        val out = Sum()
        for (item in group) {
            val f = byId[item.getInt("id")]
            val status = f?.optString("status") ?: if (item.optBoolean("running")) "running" else "planned"
            if (status != "running") { if (status == "broken" && !out.running) out.status = "broken"; continue }
            out.running = true; out.status = "running"
            if (f == null) continue
            if (!f.isNull("riders")) out.riders = (out.riders ?: 0.0) + f.optDouble("riders")
            out.trains += f.optDouble("trains", 0.0)
            val m = f.optJSONObject("money") ?: continue
            out.money = true
            out.fares += m.optDouble("fares")
            out.cost += m.optDouble("running") + m.optDouble("upkeep")
            out.profit += m.optDouble("profit")
        }
        return out
    }

    private fun usd(v: Double) = StudioFormat.money(v * 1e6, currency)

    /** Four columns: riders, fares, running cost with upkeep, profit (red at a loss). */
    private fun figureRow(s: Sum, small: Boolean): View {
        val row = LinearLayout(host)
        if (!s.running) {
            row.addView(StudioUi.text(host, if (s.status == "broken") "Rusak" else "Belum jalan", 12.5f, true, StudioUi.MUTED))
            return row
        }
        val known = figures != null
        val cols = listOf(
            Triple("Penumpang", if (s.riders != null) StudioFormat.count(s.riders!!) else "—", StudioUi.INK),
            Triple("Pendapatan", if (s.money) usd(s.fares) else "—", StudioUi.INK),
            Triple("Operasional", if (s.money) usd(-s.cost) else "—", StudioUi.INK),
            Triple("Laba", if (s.money) usd(s.profit) else "—", if (s.money && StudioFormat.moneyNegative(s.profit * 1e6, currency)) StudioUi.BAD else if (s.money) StudioUi.GOOD else StudioUi.INK),
        )
        cols.forEach { (caption, value, ink) ->
            val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            col.addView(StudioUi.text(host, caption, if (small) 10.5f else 11f, true, StudioUi.MUTED).apply { maxLines = 1 })
            col.addView(StudioUi.text(host, if (known) value else "—", if (small) 13f else 14f, color = ink).apply {
                typeface = StudioUi.heavy(host); maxLines = 1
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.contentDescription = if (!known) "Angka muncul saat simulasi berjalan" else cols.joinToString(", ") { "${it.first} ${it.second}" }
        return row
    }

    private fun statusLine(s: Sum, count: Int) = (if (count > 1) "$count layanan · " else "") + when {
        s.running -> "Beroperasi" + if (figures != null) " · ${s.trains.toInt()} armada sekarang" else ""
        s.status == "broken" -> "Rusak"
        else -> "Belum beroperasi"
    }

    private fun card(groupKey: String, group: List<JSONObject>, title: String): View {
        val first = group.first()
        val color = ServicePanel.color(looks[first.getInt("id")])
        val s = sum(group)
        val many = group.size > 1
        val isOpen = many && groupKey in open
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 18, 0xf2ffffff.toInt())
            setPadding(dp(8), dp(8), dp(12), dp(10))
        }
        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "$title, ${statusLine(s, group.size)}" + if (many) (if (isOpen) ", ketuk untuk menutup" else ", ketuk untuk membuka layanannya") else ""
            setOnClickListener {
                if (many) { if (!open.add(groupKey)) open.remove(groupKey); refresh() }
                else pick(first.getInt("id"))
            }
        }
        if (many) head.addView(StudioUi.text(host, if (isOpen) "▾" else "▸", 20f, true, StudioUi.INK).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        head.addView(StudioUi.badge(host, ServicePanel.code(title), color, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (!many) marginStart = dp(4) })
        val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        body.addView(StudioUi.text(host, title, 15f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(host); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        body.addView(StudioUi.text(host, statusLine(s, group.size), 12.5f, true, if (s.running) StudioUi.GOOD else StudioUi.MUTED))
        head.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        card.addView(head)
        card.addView(figureRow(s, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); marginStart = dp(4) })
        if (isOpen) group.forEachIndexed { i, route ->
            val id = route.getInt("id")
            val stops = looks[id]?.let { ServiceStops(it).endpoints }.orEmpty()
            val one = sum(listOf(route))
            val child = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioDepot.touch(host, 0x0f22204a, 14)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                minimumHeight = dp(48)
                isClickable = true
                val name = route.getString("name").let { if (it == title) "Layanan ${i + 1}" else it }
                contentDescription = "$name${if (stops.isNotEmpty()) ", $stops" else ""}, buka rincian"
                setOnClickListener { pick(id) }
                addView(StudioUi.text(host, name + if (stops.isNotEmpty()) " · $stops" else "", 13.5f, true, StudioUi.INK).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(figureRow(one, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            card.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); marginStart = dp(28) })
        }
        return card
    }

    private var last: Triple<List<JSONObject>, Boolean, () -> Unit>? = null
    private fun refresh() { last?.let { (a, b, c) -> show(a, b, c) } }

    /** Rows for `found`; services in a group the player made (web line groups) or sharing a name
     * (both directions, variants) form one row. */
    fun show(found: List<JSONObject>, any: Boolean, clear: () -> Unit) {
        last = Triple(found, any, clear)
        rows.removeAllViews()
        if (found.isEmpty() && any) {
            rows.addView(StudioUi.text(host, "Line tidak ditemukan.", 15f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(12), 0, dp(6)) })
            rows.addView(StudioUi.button(host, "Hapus pencarian") { clear() })
            return
        }
        fun groupOf(it: JSONObject) = it.optInt("group").let { g -> if (g > 0) "g:$g" else "n:" + it.getString("name").trim().lowercase() }
        val groups = found.groupBy(::groupOf).map { (k, g) ->
            val name = g.first().optString("groupName").takeIf { k.startsWith("g:") && it.isNotBlank() } ?: g.first().getString("name")
            Triple(k, g, name)
        }
        fun value(g: List<JSONObject>): Double {
            val s = sum(g)
            return when (key) {
                Key.RIDERS -> s.riders ?: 0.0
                Key.MONEY -> if (s.money) s.profit else 0.0
                Key.TRAINS -> s.trains
                Key.NAME -> 0.0
            }
        }
        val byName = compareBy<Triple<String, List<JSONObject>, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.third }
        val order = if (key == Key.NAME) (if (dir > 0) byName else byName.reversed())
            else compareBy<Triple<String, List<JSONObject>, String>> { dir * value(it.second) }.then(byName)
        groups.sortedWith(order).forEach { (k, g, name) ->
            val members = if (key == Key.NAME) g else g.sortedBy { dir * value(listOf(it)) }
            rows.addView(card(k, members, name), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }
}
