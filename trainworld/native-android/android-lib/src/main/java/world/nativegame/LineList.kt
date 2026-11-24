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

    private enum class Key(val label: String, val first: Int) { RIDERS("Penumpang", -1), MONEY("Laba", -1), TRAINS("Armada", -1), NEW("Terbaru", -1), NAME("Nama", 1) }
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
        bar.addView(StudioUi.text(host, "Urutkan", 12f, true, StudioUi.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        Key.values().forEach { k ->
            val chip = StudioUi.text(host, k.label, 12.5f, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                setOnClickListener {
                    if (key == k) dir = -dir else { key = k; dir = k.first }
                    paint(); changed()
                }
            }
            chips += k to chip
            // 40 dp drawn, 48 dp to touch (the row's padding gives the rest)
            bar.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6); topMargin = dp(4); bottomMargin = dp(4) })
        }
        paint()
        val view = StudioUi.text(host, if (table) "Kartu" else "Tabel", 12.5f, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(12), 0, dp(12), 0); isClickable = true
            background = StudioUi.pill(host, 0x1422204a, 16)
            setCompoundDrawables(StudioUi.glyph(host, StudioUi.Icon.LAYERS, StudioUi.INK), null, null, null); compoundDrawablePadding = dp(4)
            contentDescription = "Ganti tampilan daftar"
            setOnClickListener { table = !table; text = if (table) "Kartu" else "Tabel"; changed() }
        }
        val wrap = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(android.widget.HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(bar) }, LinearLayout.LayoutParams(0, -2, 1f))
        wrap.addView(view, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(6) })
        return wrap
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

    /** Total profit of every running line (web MoneyToday summed), null without figures. */
    val totalProfit: Double? get() = if (figures == null) null else byId.values.sumOf { it.optJSONObject("money")?.optDouble("profit") ?: 0.0 }

    private fun profitInk(s: Sum) = when {
        !s.money || figures == null -> StudioUi.MUTED
        StudioFormat.moneyNegative(s.profit * 1e6, currency) -> StudioUi.BAD
        else -> StudioUi.GOOD
    }

    /** The profit, large, on the right of a row: what the player reads first (web: the Laba column). */
    private fun profitBlock(s: Sum, small: Boolean): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        val known = figures != null && s.money
        addView(StudioUi.text(host, if (known) usd(s.profit) else "—", if (small) 14f else 16.5f, color = profitInk(s)).apply {
            typeface = StudioUi.heavy(host); maxLines = 1; gravity = Gravity.END
        })
        addView(StudioUi.text(host, if (!s.running) "" else "laba/hari", 10.5f, true, StudioUi.MUTED).apply { gravity = Gravity.END })
    }

    /** Riders, fares and cost in three short columns, with a bar of how much of the fares is left as profit. */
    private fun figureRow(s: Sum, small: Boolean): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        if (!s.running) {
            row.addView(StudioUi.text(host, if (s.status == "broken") "Rusak · perbaiki relnya" else "Belum jalan", 12.5f, true, StudioUi.MUTED))
            return row
        }
        val known = figures != null
        val cols = listOf(
            "penumpang" to (if (known && s.riders != null) StudioFormat.count(s.riders!!) else "—"),
            "pendapatan" to (if (known && s.money) usd(s.fares) else "—"),
            "biaya" to (if (known && s.money) usd(s.cost) else "—"),
        )
        val line = LinearLayout(host)
        cols.forEach { (caption, value) ->
            val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            col.addView(StudioUi.text(host, value, if (small) 12.5f else 13.5f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(host); maxLines = 1 })
            col.addView(StudioUi.text(host, caption, 10.5f, true, StudioUi.MUTED).apply { maxLines = 1 })
            line.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.addView(line)
        if (known && s.money && s.fares > 0) {
            val margin = (s.profit / s.fares).toFloat()
            row.addView(StudioProgress.Bar(host, if (margin >= 0) StudioUi.GOOD else StudioUi.BAD, 0x1422204a).apply {
                fraction = kotlin.math.abs(margin).coerceIn(0f, 1f)
                contentDescription = "Laba ${(margin * 100).toInt()}% dari pendapatan"
            }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
        }
        row.contentDescription = if (!known) "Angka muncul saat simulasi berjalan" else cols.joinToString(", ") { "${it.second} ${it.first}" } +
            if (s.money) ", laba ${usd(s.profit)} per hari" else ""
        return row
    }

    private fun statusLine(s: Sum, count: Int) = listOfNotNull(
        if (figures != null && s.running) "${s.trains.toInt()} armada" else null,
        if (count > 1) "$count layanan" else null,
        when { s.running -> null; s.status == "broken" -> "Rusak"; else -> "Belum beroperasi" },
    ).joinToString(" · ").ifEmpty { "Beroperasi" }

    private fun card(groupKey: String, group: List<JSONObject>, title: String): View {
        val first = group.first()
        val color = ServicePanel.color(looks[first.getInt("id")])
        val s = sum(group)
        val many = group.size > 1
        val isOpen = many && groupKey in open
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(host, 18, 0xf2ffffff.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "$title, ${statusLine(s, group.size)}" + if (many) (if (isOpen) ", ketuk untuk menutup" else ", ketuk untuk membuka layanannya") else ", buka rincian"
            setOnClickListener {
                if (many) { if (!open.add(groupKey)) open.remove(groupKey); refresh() }
                else pick(first.getInt("id"))
            }
        }
        head.addView(StudioUi.badge(host, ServicePanel.code(title), color, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
        val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        body.addView(StudioUi.text(host, title, 15f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(host); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        body.addView(StudioUi.text(host, statusLine(s, group.size) + if (many) (if (isOpen) "  ▾" else "  ▸") else "", 12f, true, StudioUi.MUTED).apply { maxLines = 1 })
        head.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
        head.addView(profitBlock(s, false))
        card.addView(head)
        card.addView(figureRow(s, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (isOpen) group.forEachIndexed { i, route ->
            val id = route.getInt("id")
            val stops = looks[id]?.let { ServiceStops(it).endpoints }.orEmpty()
            val one = sum(listOf(route))
            val child = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioDepot.touch(host, 0x0c22204a, 14)
                setPadding(dp(10), dp(8), dp(10), dp(10))
                minimumHeight = dp(48)
                isClickable = true
                val name = route.getString("name").let { if (it == title) "Layanan ${i + 1}" else it }
                contentDescription = "$name${if (stops.isNotEmpty()) ", $stops" else ""}, buka rincian"
                setOnClickListener { pick(id) }
                val top = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
                val names = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                names.addView(StudioUi.text(host, name, 13.5f, true, StudioUi.INK).apply { maxLines = 1 })
                if (stops.isNotEmpty()) names.addView(StudioUi.text(host, stops, 11.5f, true, StudioUi.MUTED).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                top.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                top.addView(profitBlock(one, true))
                addView(top)
                addView(figureRow(one, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            card.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
                // a line's id grows with each line built: newest first, ↑ turns it to oldest first
                Key.NEW -> g.maxOf { it.getInt("id") }.toDouble()
                Key.NAME -> 0.0
            }
        }
        val byName = compareBy<Triple<String, List<JSONObject>, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.third }
        val order = if (key == Key.NAME) (if (dir > 0) byName else byName.reversed())
            else compareBy<Triple<String, List<JSONObject>, String>> { dir * value(it.second) }.then(byName)
        val sorted = groups.sortedWith(order)
        if (table) { tableView(sorted.map { (k, g, name) -> Triple(k, if (key == Key.NAME) g else g.sortedBy { dir * value(listOf(it)) }, name) }); return }
        sorted.forEach { (k, g, name) ->
            val members = if (key == Key.NAME) g else g.sortedBy { dir * value(listOf(it)) }
            rows.addView(card(k, members, name), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    /** Compact table (owner: "kasih opsi table biar compact"): one 44 dp row a line, columns sortable. */
    var table: Boolean
        get() = host.getSharedPreferences("world-native-lines", 0).getBoolean("table", false)
        set(v) { host.getSharedPreferences("world-native-lines", 0).edit().putBoolean("table", v).apply() }

    private fun tableView(groups: List<Triple<String, List<JSONObject>, String>>) {
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; background = StudioUi.card(host, 16, 0xf2ffffff.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val weights = floatArrayOf(2.6f, 1.15f, 1.15f, 1.15f)
        fun cell(row: LinearLayout, i: Int, text: String, ink: Int, bold: Boolean, end: Boolean) {
            row.addView(StudioUi.text(host, text, if (bold) 12f else 12.5f, bold, ink).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = (if (end) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                if (!bold) typeface = StudioUi.heavy(host)
            }, LinearLayout.LayoutParams(0, -1, weights[i]).apply { if (i > 0) marginStart = dp(4) })
        }
        val head = LinearLayout(host).apply { minimumHeight = dp(36); gravity = Gravity.CENTER_VERTICAL }
        val heads = listOf("Line" to Key.NAME, "Penumpang" to Key.RIDERS, "Laba/hari" to Key.MONEY, "Armada" to Key.TRAINS)
        heads.forEachIndexed { i, (label, k) ->
            val on = key == k
            cell(head, i, label + if (on) (if (dir < 0) " ↓" else " ↑") else "", if (on) StudioUi.INK else StudioUi.MUTED, true, i > 0)
            head.getChildAt(i).apply {
                isClickable = true; minimumHeight = dp(44)
                setOnClickListener { if (key == k) dir = -dir else { key = k; dir = k.first }; refresh() }
            }
        }
        box.addView(head)
        groups.forEachIndexed { n, (k, g, name) ->
            val s = sum(g)
            val first = g.first()
            val row = LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44); isClickable = true
                if (n % 2 == 0) background = StudioUi.pill(host, 0x0a22204a, 10)
                setOnClickListener { if (g.size > 1) { if (!open.add(k)) open.remove(k); refresh() } else pick(first.getInt("id")) }
                contentDescription = "$name, ${s.riders?.let { StudioFormat.count(it) } ?: "—"} penumpang, laba ${if (s.money) usd(s.profit) else "—"}"
            }
            val nameCell = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            nameCell.addView(StudioUi.badge(host, ServicePanel.code(name), ServicePanel.color(looks[first.getInt("id")]), 22), LinearLayout.LayoutParams(dp(22), dp(22)))
            nameCell.addView(StudioUi.text(host, name + if (g.size > 1) " (${g.size})" else "", 12.5f, true, StudioUi.INK).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            row.addView(nameCell, LinearLayout.LayoutParams(0, -1, weights[0]))
            val known = figures != null
            cell(row, 1, if (!s.running) (if (s.status == "broken") "Rusak" else "—") else if (known && s.riders != null) StudioFormat.count(s.riders!!) else "—", StudioUi.INK, false, true)
            cell(row, 2, if (known && s.money) usd(s.profit) else "—", profitInk(s), false, true)
            cell(row, 3, if (known && s.running) s.trains.toInt().toString() else "—", StudioUi.INK, false, true)
            box.addView(row)
            if (g.size > 1 && k in open) g.forEachIndexed { i, route ->
                val one = sum(listOf(route))
                val sub = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40); isClickable = true; setOnClickListener { pick(route.getInt("id")) } }
                val label = route.getString("name").let { if (it == name) "Layanan ${i + 1}" else it }
                cell(sub, 0, "   ↳ $label", StudioUi.MUTED, true, false)
                cell(sub, 1, if (known && one.riders != null) StudioFormat.count(one.riders!!) else "—", StudioUi.INK, false, true)
                cell(sub, 2, if (known && one.money) usd(one.profit) else "—", profitInk(one), false, true)
                cell(sub, 3, if (known && one.running) one.trains.toInt().toString() else "—", StudioUi.INK, false, true)
                box.addView(sub)
            }
        }
        rows.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
}
