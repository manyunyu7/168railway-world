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

/** The end-of-day report and the thin progress bar; achievements are `StudioAchievements` (web achievements.ts). */
internal object StudioProgress {
    data class Snapshot(val routes: Int, val stations: Int, val trains: Int, val day: Int, val cashMillions: Double)
    /** Thin rounded progress bar. */
    class Bar(c: Context, color: Int, private val track: Int) : View(c) {
        var color = color
            set(v) { field = v; invalidate() }
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
