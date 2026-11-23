// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Missions, badges and the masinis level, derived only from what the world already measures. */
internal object StudioProgress {
    data class Snapshot(val routes: Int, val stations: Int, val trains: Int, val day: Int, val cashMillions: Double)
    data class Badge(val id: String, val title: String, val goal: String, val color: Int, val target: Double, val value: (Snapshot) -> Double)

    fun badges(currency: StudioCurrency): List<Badge> = listOf(
        Badge("first-line", "Lini pertama", "Jalankan 1 jalur", 0xff3fb06a.toInt(), 1.0) { it.routes.toDouble() },
        Badge("ten-lines", "Jaringan kota", "Jalankan 10 jalur", 0xff3e7bfa.toInt(), 10.0) { it.routes.toDouble() },
        Badge("hundred-stations", "Stasiun ramai", "Punya 100 stasiun", 0xff1fb5c9.toInt(), 100.0) { it.stations.toDouble() },
        Badge("metropolis", "Megapolitan", "Jalankan 25 jalur", 0xff6c4df6.toInt(), 25.0) { it.routes.toDouble() },
        Badge("fleet", "Armada besar", "200 kereta di peta", 0xffff7a2f.toInt(), 200.0) { it.trains.toDouble() },
        Badge("platforms", "Ratusan peron", "Punya 300 stasiun", 0xffa35bea.toInt(), 300.0) { it.stations.toDouble() },
        Badge("week", "Seminggu jalan", "Capai hari ke-7", 0xff22204a.toInt(), 7.0) { it.day.toDouble() },
        Badge("rich", "Kas melimpah", "Kas ${currency.compact(20000.0)}", 0xffffb020.toInt(), 20000.0) { it.cashMillions }
    )

    private val ranks = listOf("Juru langsir", "Asisten masinis", "Masinis", "Masinis utama", "Kepala stasiun",
        "Kepala Daop", "Direktur operasi", "Direktur utama", "Legenda rel")
    fun level(unlocked: Int) = unlocked + 1
    fun rank(unlocked: Int) = ranks[unlocked.coerceIn(0, ranks.lastIndex)]

    fun progress(b: Badge, s: Snapshot) = (b.value(s) / b.target).coerceIn(0.0, 1.0)
    fun unlocked(list: List<Badge>, s: Snapshot) = list.filter { progress(it, s) >= 1.0 }
    fun nextMission(list: List<Badge>, s: Snapshot) = list.filter { progress(it, s) < 1.0 }.maxByOrNull { progress(it, s) }

    /** Badges already celebrated for this world, so a new one shows a single toast. */
    fun seen(c: Context, world: String): MutableSet<String> =
        c.getSharedPreferences("world-progress", 0).getStringSet(world, emptySet())!!.toMutableSet()
    fun remember(c: Context, world: String, ids: Set<String>) =
        c.getSharedPreferences("world-progress", 0).edit().putStringSet(world, ids).apply()

    /** Thin rounded progress bar. */
    class Bar(c: Context, private val color: Int, private val track: Int) : View(c) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track; box.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(box, r, r, paint)
            paint.color = color; box.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat()); canvas.drawRoundRect(box, r, r, paint)
        }
    }

    /** Level ring for the Pencapaian header. */
    class Ring(c: Context, private val label: String, private val fraction: Float) : View(c) {
        private val d = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9 * d; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textAlign = Paint.Align.CENTER; textSize = 24 * d; typeface = StudioUi.display(c) }
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val p = 6 * d; box.set(p, p, width - p, height - p)
            stroke.color = 0x40ffffff; canvas.drawArc(box, 0f, 360f, false, stroke)
            stroke.color = 0xffffd23f.toInt(); canvas.drawArc(box, -90f, 360f * fraction, false, stroke)
            canvas.drawText(label, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2, text)
        }
    }

    fun show(activity: Activity, currency: StudioCurrency, s: Snapshot) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val list = badges(currency)
        val done = unlocked(list, s)
        StudioUi.sheet(activity, "Pencapaian") { column, _ ->
            column.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = StudioUi.pill(activity, 0xff6c4df6.toInt(), 28)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(Ring(activity, level(done.size).toString(), done.size.toFloat() / list.size), LinearLayout.LayoutParams(dp(86), dp(86)))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(StudioUi.text(activity, "LEVEL MASINIS", 12f, true, 0xffd9d0ff.toInt()))
                    addView(StudioUi.title(activity, rank(done.size), 24f, -1))
                    addView(StudioUi.text(activity, "${done.size} dari ${list.size} lencana", 12f, true, 0xffd9d0ff.toInt()))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            })
            column.addView(StudioUi.section(activity, "Misi aktif"))
            list.filter { progress(it, s) < 1.0 }.sortedByDescending { progress(it, s) }.take(3).forEach { b ->
                column.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = StudioUi.card(activity, 18)
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    addView(StudioUi.text(activity, b.goal, 15f, true))
                    addView(Bar(activity, b.color, 0x1a22204a).apply { fraction = progress(b, s).toFloat() },
                        LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            column.addView(StudioUi.section(activity, "Lencana"))
            val grid = android.widget.GridLayout(activity).apply { columnCount = 3 }
            list.forEach { b ->
                val got = progress(b, s) >= 1.0
                grid.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = StudioUi.card(activity, 20, if (got) 0xe6ffffff.toInt() else 0x73ffffff)
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    alpha = if (got) 1f else .6f
                    addView(StudioDepot.symbol(activity, if (got) StudioUi.Icon.TRAIN else StudioUi.Icon.CLOSE, 24f, -1).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (got) b.color else 0x2022204a)
                        }
                    }, LinearLayout.LayoutParams(dp(54), dp(54)))
                    addView(StudioUi.text(activity, b.title, 13f, true).apply { gravity = Gravity.CENTER })
                    addView(StudioUi.text(activity, b.goal, 11f, true, StudioUi.MUTED).apply { gravity = Gravity.CENTER })
                }, android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            column.addView(grid, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** End-of-day card: numbers the HUD already tracks, measured from the start of the day. */
    fun report(activity: Activity, currency: StudioCurrency, day: Int, trains: Int, cashStart: Double, cashEnd: Double, busiest: String?, mission: String?) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val delta = cashEnd - cashStart
        StudioUi.sheet(activity, "Laporan hari $day") { column, dialog ->
            column.addView(StudioUi.text(activity, if (delta >= 0) "Hari yang untung!" else "Hari yang berat", 16f, true, StudioUi.MUTED))
            val grid = android.widget.GridLayout(activity).apply { columnCount = 2 }
            listOf(
                Triple("Kereta di peta", trains.toString(), StudioUi.INK),
                Triple("Kas akhir hari", currency.compact(cashEnd), StudioUi.INK),
                Triple("Perubahan kas", (if (delta >= 0) "+" else "−") + currency.compact(kotlin.math.abs(delta)), if (delta >= 0) StudioUi.GOOD else StudioUi.BAD),
                Triple("Hari berikutnya", "Hari ${day + 1}", StudioUi.INK)
            ).forEach { (a, b, c) ->
                grid.addView(StudioUi.stat(activity, a, b, c), android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            column.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (busiest != null) column.addView(StudioUi.row(activity, StudioUi.Icon.PIN, StudioUi.TINT_RED, busiest, "Stasiun paling sibuk di peta"),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (mission != null) column.addView(StudioUi.row(activity, StudioUi.Icon.TRAIN, StudioUi.TINT_PURPLE, "Misi berikutnya", mission),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            column.addView(StudioUi.button(activity, "Lanjut ke hari ${day + 1}", StudioUi.Tone.PRIMARY) { dialog.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }
}
