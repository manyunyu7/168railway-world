// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The money tab's history (web ui/MoneyHistory.tsx): revenue (blue) against expenses (red: running
 * trains, upkeep and loan payments, one-off costs a paler bar on top), then rides, per game hour over
 * the past 24 ("1h"), per day over the past 30 ("30h"), or every day kept ("Semua", a week or more to
 * a column past 90). A tap on a column says its figures, as the web's hover.
 */
internal object FinanceHistory {
    /** Web `MoneyHistory` span, kept while the game is open. */
    private var span = "all"
    private const val MAX_COLS = 90
    private val REVENUE = 0xff3a78c9.toInt()
    private val EXPENSE = 0xffc8423b.toInt()
    private val ONE_OFF = 0xfff1cdca.toInt()

    class Col(val label: String, val fares: Double, val running: Double, val upkeep: Double, val loan: Double,
              val trains: Double, val build: Double, val rides: Double, val perDay: Boolean) {
        val costs get() = running + upkeep + loan
        val oneOff get() = trains + build
    }

    private fun rows(a: JSONArray?, key: String) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.filter { it.has(key) }

    /** Web `binDays`: days as they are while they fit in 90 columns, else by week (from Monday, game day 1 a Monday), then by runs of weeks; figures a day's average. */
    private fun columns(state: JSONObject): List<Col> {
        val hours = span == "1d"
        val list = if (hours) rows(state.optJSONArray("hours"), "hour") else rows(state.optJSONArray("ledger"), "day")
        if (list.isEmpty()) return emptyList()
        val key = if (hours) "hour" else "day"
        val newest = list.last().getInt(key)
        val keep = when (span) { "1d" -> 24; "30d" -> 30; else -> Int.MAX_VALUE }
        val shown = list.filter { it.getInt(key) > newest - keep }
        val shift = state.optDouble("dayShift", 0.0) / 3600
        fun col(e: JSONObject, label: String, n: Int = 1) = Col(label, e.optDouble("fares") / n, e.optDouble("running") / n, e.optDouble("upkeep") / n,
            e.optDouble("loan") / n, e.optDouble("trains") / n, e.optDouble("build") / n, e.optDouble("rides") / n, n > 1)
        if (hours) return shown.map { e ->
            val at = e.getInt("hour") + shift
            val h = Math.floorMod(Math.floor(at).toLong(), 24L)
            col(e, "Hari ${Math.floorDiv(Math.floor(at).toLong(), 24L) + 1}, %02d:00".format(h))
        }
        val first = shown.first().getInt("day"); val last = shown.last().getInt("day")
        if (span != "all" || last - first + 1 <= MAX_COLS) return shown.map { col(it, "Hari ${it.getInt("day")}") }
        // weeks from Monday (web weekdayOf: day 1 is a Monday), several to a column past 90 weeks
        val weekOf = { d: Int -> Math.floorDiv(d - 1, 7) }
        val weeks = weekOf(last) - weekOf(first) + 1
        val per = (weeks + MAX_COLS - 1) / MAX_COLS
        val byDay = shown.associateBy { it.getInt("day") }
        return (0 until (weeks + per - 1) / per).map { b ->
            val from = maxOf(first, (weekOf(first) + b * per) * 7 + 1)
            val to = minOf(last + 1, (weekOf(first) + (b + 1) * per) * 7 + 1)
            val sum = JSONObject()
            for (k in listOf("fares", "running", "upkeep", "loan", "trains", "build", "rides")) {
                sum.put(k, (from until to).sumOf { byDay[it]?.optDouble(k, 0.0) ?: 0.0 })
            }
            col(sum, "Hari $from–${to - 1}", to - from)
        }
    }

    fun view(activity: Activity, state: JSONObject, currency: StudioCurrency): View {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.card(activity, 22)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val chips = LinearLayout(activity)
        val title = StudioUi.text(activity, "", 13f, true, StudioUi.MUTED)
        val detail = StudioUi.text(activity, "Ketuk batang untuk rinciannya.", 13f, true, StudioUi.MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        lateinit var money: Chart
        lateinit var ridesChart: Chart
        val usd = { v: Double -> StudioFormat.money(v * 1e6, currency) }
        fun describe(c: Col, last: Boolean): String {
            val per = if (c.perDay) "/hari" else ""
            return buildList {
                add(if (last) "${c.label} sejauh ini" else c.label)
                add("Tarif ${usd(c.fares)}$per")
                add("Biaya berjalan ${usd(-(c.running + c.upkeep))}$per")
                if (c.loan > 0) add("Pinjaman ${usd(-c.loan)}$per")
                if (c.oneOff > 0) add("Biaya sekali bayar ${usd(-c.oneOff)}$per")
                add("Naik angkutan umum ${StudioFormat.count(c.rides)}$per")
            }.joinToString("\n")
        }
        fun fill() {
            val cols = columns(state)
            chips.removeAllViews()
            for ((k, label) in listOf("1d" to "1h", "30d" to "30h", "all" to "Semua")) {
                val on = k == span
                chips.addView(StudioUi.chip(activity, label, if (on) StudioUi.INK else StudioUi.TINT_GREY, if (on) StudioUi.WHITE else StudioUi.INK).apply {
                    minHeight = dp(48); gravity = android.view.Gravity.CENTER; minWidth = dp(64)
                    contentDescription = when (k) { "1d" -> "24 jam terakhir"; "30d" -> "30 hari terakhir"; else -> "Semua hari" } + if (on) ", dipilih" else ""
                    setOnClickListener { span = k; fill() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            title.text = "Pendapatan dan pengeluaran di semua kota"
            money.cols = cols; ridesChart.cols = cols
            money.contentDescription = "Pendapatan dan pengeluaran, ${cols.size} kolom. " + (cols.lastOrNull()?.let { describe(it, true) } ?: "")
            ridesChart.contentDescription = "Naik angkutan umum, ${cols.size} kolom"
            detail.text = "Ketuk batang untuk rinciannya."
            money.invalidate(); ridesChart.invalidate()
        }
        val pick = { cols: List<Col>, i: Int -> detail.text = describe(cols[i], i == cols.lastIndex); detail.setTextColor(StudioUi.INK) }
        money = Chart(activity, rides = false, pick)
        ridesChart = Chart(activity, rides = true, pick)
        box.addView(chips)
        box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(money, LinearLayout.LayoutParams(-1, dp(104)))
        box.addView(StudioUi.text(activity, "● Pendapatan   ● Pengeluaran   ● Sekali bayar", 12f, true, StudioUi.MUTED).apply {
            text = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(REVENUE), 0, 1, 0)
                val e = indexOf("● Pengeluaran"); setSpan(android.text.style.ForegroundColorSpan(EXPENSE), e, e + 1, 0)
                val o = indexOf("● Sekali"); setSpan(android.text.style.ForegroundColorSpan(ONE_OFF), o, o + 1, 0)
            }
        })
        box.addView(StudioUi.text(activity, "Naik angkutan umum di semua kota", 13f, true, StudioUi.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        box.addView(ridesChart, LinearLayout.LayoutParams(-1, dp(72)))
        box.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fill()
        return box
    }

    /** Bars right-aligned in at least 7 (days) or 12 (hours) slots, as `FareCostBars`; the last column, the one now, paler. */
    private class Chart(activity: Activity, val rides: Boolean, val pick: (List<Col>, Int) -> Unit) : View(activity) {
        var cols: List<Col> = emptyList()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private fun slots() = maxOf(cols.size, if (span == "1d") 12 else 7)
        override fun onDraw(canvas: Canvas) {
            if (cols.isEmpty()) return
            val d = resources.displayMetrics.density
            val col = width.toFloat() / slots(); val x0 = width - cols.size * col
            val bw = maxOf(d, col - 1.5f * d)
            val bottom = height - 2 * d
            val top = if (rides) cols.maxOf { it.rides }.coerceAtLeast(1e-9) else cols.maxOf { maxOf(it.fares, it.costs) }.coerceAtLeast(1e-9)
            fun y(v: Double) = (bottom - (v / top) * (bottom - 3 * d)).toFloat().coerceAtLeast(3 * d)
            cols.forEachIndexed { i, c ->
                val alpha = if (i == cols.lastIndex) 0x73 else 0xff
                val left = x0 + i * col + (col - bw) / 2
                if (rides) {
                    pen.color = StudioUi.BLUE; pen.alpha = alpha
                    box.set(left, y(c.rides), left + bw, bottom); canvas.drawRect(box, pen)
                    return@forEachIndexed
                }
                pen.color = REVENUE; pen.alpha = alpha
                box.set(left, y(c.fares), left + bw / 2, bottom); canvas.drawRect(box, pen)
                pen.color = EXPENSE; pen.alpha = alpha
                box.set(left + bw / 2, y(c.costs), left + bw, bottom); canvas.drawRect(box, pen)
                if (c.oneOff > 0) {
                    pen.color = ONE_OFF; pen.alpha = alpha
                    box.set(left + bw / 2, y(c.costs + c.oneOff), left + bw, y(c.costs)); canvas.drawRect(box, pen)
                }
            }
            pen.color = 0x3322204a; pen.alpha = 0x33
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, pen)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (cols.isEmpty()) return false
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val col = width.toFloat() / slots(); val x0 = width - cols.size * col
                val i = ((e.x - x0) / col).toInt().coerceIn(0, cols.lastIndex)
                pick(cols, i)
                parent?.requestDisallowInterceptTouchEvent(e.action == MotionEvent.ACTION_MOVE)
                return true
            }
            return e.action == MotionEvent.ACTION_UP
        }
    }
}
