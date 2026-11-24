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
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Web TrainInspector (SPEC 8): where the train is and who is aboard (`OnBoard`), the next stops with
 * the riders getting off and on (`TrainStops`) and one rider drawn from them (`OneRider`). The
 * riders come from the running simulation (`SimulationClock.trainRiders`, train_riders.rs);
 * before it has them the sheet says "Menghitung", as the web does.
 */
internal object TrainInspector {
    /** Where the host asks for riders: game time, the rider's seed, the answer (null: not yet). */
    fun interface Riders { fun read(time: Double, seed: Long, done: (String?) -> Unit) }

    private val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun exact(v: Double) = count.format(Math.round(v))
    private fun km(m: Double) = String.format(Locale.forLanguageTag("id-ID"), "%,.1f km", m / 1000)

    /** Web `.board-bar`: filled to the riders aboard, to rated load then a second band to crush,
     * with marks at the seats and at the rated load. */
    private class LoadBar(c: Context) : View(c) {
        var aboard = 0.0; var seats = 0.0; var rated = 0.0; var crush = 1.0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        fun set(aboard: Double, seats: Double, rated: Double, crush: Double) {
            this.aboard = aboard; this.seats = seats; this.rated = rated; this.crush = maxOf(crush, 1.0); invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
            fun x(v: Double) = (minOf(v, crush) / crush * w).toFloat()
            paint.color = 0x1a22204a; box.set(0f, 0f, w, h); canvas.drawRoundRect(box, r, r, paint)
            paint.color = StudioUi.GOOD; box.set(0f, 0f, maxOf(if (aboard > 0.5) h else 0f, x(minOf(aboard, rated))), h); canvas.drawRoundRect(box, r, r, paint)
            if (aboard > rated) { paint.color = 0xffe8a317.toInt(); box.set(x(rated), 0f, x(aboard), h); canvas.drawRect(box, paint) }
            paint.color = StudioUi.INK
            for (mark in listOf(seats, rated).filter { it in 0.5..(crush - 0.5) }) canvas.drawRect(x(mark) - 1.5f, 0f, x(mark) + 1.5f, h, paint)
        }
    }

    fun show(activity: Activity, engine: Long, profile: Int, dep: Int, fill: Int?, time: Double,
             now: () -> Double, gauge: () -> Int?, riders: Riders?, follow: (String) -> Unit, openLine: (Int) -> Unit) {
        val first = Engine.trainInfo(engine, profile, dep / 100.0, time)?.takeIf { it != "null" }?.let(::JSONObject) ?: return
        val color = first.optInt("color") or 0xff000000.toInt()
        fun dp(n: Int) = StudioUi.dp(activity, n)
        StudioUi.sheet(activity, first.optString("name")) { body, sheet ->
            val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(StudioUi.badge(activity, first.optString("name").split(' ').lastOrNull()?.take(1)?.uppercase() ?: "?", color, 36),
                LinearLayout.LayoutParams(dp(36), dp(36)))
            val towards = StudioUi.text(activity, "", 16f, true, StudioUi.INK)
            head.addView(towards, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            body.addView(head)
            fun row(parent: LinearLayout, label: String, bold: Boolean = false): TextView {
                val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44) }
                line.addView(StudioUi.text(activity, label, 14f, true, if (bold) StudioUi.INK else StudioUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
                val value = StudioUi.text(activity, "—", if (bold) 18f else 15f, true, StudioUi.INK).apply { gravity = Gravity.END }
                if (bold) value.typeface = StudioUi.heavy(activity)
                line.addView(value)
                parent.addView(line)
                return value
            }
            val next = row(body, "Berikutnya")
            val along = row(body, "Sepanjang lini")
            // web OnBoard: "Di dalam", the period on the right
            val boardHead = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2)) }
            boardHead.addView(StudioUi.section(activity, "Di dalam"), LinearLayout.LayoutParams(0, -2, 1f))
            val period = StudioUi.text(activity, "", 12f, true, StudioUi.MUTED)
            boardHead.addView(period)
            body.addView(boardHead)
            val aboard = row(body, "Penumpang di dalam", bold = true)
            val typicalRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(typicalRow)
            val typical = row(typicalRow, "Kereta biasa jam ini")
            val barRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36) }
            val bar = LoadBar(activity)
            barRow.addView(bar, LinearLayout.LayoutParams(0, dp(10), 1f))
            val state = StudioUi.text(activity, "", 14f, true, StudioUi.INK)
            barRow.addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            body.addView(barRow)
            val full = row(body, "Penuh pada")
            // web TrainStops
            val stops = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(stops)
            // web OneRider
            var seed = System.nanoTime()
            val rider = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val riderHead = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            riderHead.addView(StudioUi.section(activity, "Penumpang acak"), LinearLayout.LayoutParams(0, -2, 1f))
            rider.addView(riderHead)
            val from = row(rider, "Dari")
            val to = row(rider, "Ke")
            val purpose = row(rider, "Tujuan")
            val drawn = StudioUi.text(activity, "", 12.5f, false, StudioUi.MUTED)
            rider.addView(drawn)
            body.addView(rider)
            lateinit var refresh: () -> Unit
            riderHead.addView(StudioUi.button(activity, "Pilih penumpang lain") { seed = System.nanoTime(); refresh() }.apply {
                minHeight = dp(48); minimumHeight = dp(48); textSize = 13f
                contentDescription = "Pilih penumpang lain"
            }, LinearLayout.LayoutParams(-2, -2))
            body.addView(StudioUi.button(activity, "Ikuti kereta", StudioUi.Tone.PRIMARY, StudioUi.Icon.CENTER) {
                sheet.dismiss(); follow(first.optString("name"))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            body.addView(StudioUi.button(activity, "Buka lini", StudioUi.Tone.SURFACE, StudioUi.Icon.ROUTE) {
                sheet.dismiss(); openLine(first.optInt("line"))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

            var vehicle = first.optString("vehicle", "Kereta")
            fun showPlace(info: JSONObject) {
                vehicle = info.optString("vehicle", "Kereta")
                towards.text = "$vehicle menuju ${info.optString("towards")}"
                next.text = if (info.isNull("next")) "Terminus" else info.optString("next")
                along.text = "${km(info.optDouble("s"))} dari ${km(info.optDouble("len"))}"
                (typicalRow.getChildAt(0) as LinearLayout).getChildAt(0).let { (it as TextView).text = "$vehicle biasa jam ini" }
            }
            /** Before the riders are in: the map's gauge only, as `train_loads.rs` draws it. */
            fun showGauge(g: Int?) {
                period.text = ""
                aboard.text = "Menghitung"
                aboard.setTextColor(StudioUi.MUTED)
                typicalRow.visibility = View.GONE
                full.text = "—"
                // gauge 0–50 seats, 50–100 standing to rated load, 100–200 crowded to crush
                state.text = when {
                    g == null -> ""
                    g <= 0 -> "Kosong"
                    g <= 50 -> "Ada kursi"
                    g <= 100 -> "Berdiri"
                    g < 200 -> "Padat"
                    else -> "Penuh"
                }
                bar.set((g ?: 0) / 200.0, 0.25, 0.5, 1.0)
                stops.removeAllViews()
                rider.visibility = View.GONE
            }
            fun showRiders(r: JSONObject) {
                period.text = r.optString("period")
                aboard.setTextColor(StudioUi.INK)
                aboard.text = exact(r.optDouble("aboard"))
                typicalRow.visibility = View.VISIBLE
                typical.text = exact(r.optDouble("typical"))
                val crush = r.optDouble("crush")
                full.text = exact(crush)
                state.text = r.optString("state")
                bar.set(r.optDouble("aboard"), r.optDouble("seats"), r.optDouble("rated"), crush)
                bar.contentDescription = "${exact(r.optDouble("aboard"))}/${exact(crush)} penumpang di dalam"
                stops.removeAllViews()
                val ahead = r.optJSONArray("stops") ?: JSONArray()
                if (ahead.length() > 0) {
                    val h = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2)) }
                    h.addView(StudioUi.section(activity, "Perhentian berikut"), LinearLayout.LayoutParams(0, -2, 1f))
                    h.addView(StudioUi.text(activity, "turun", 12f, true, StudioUi.MUTED))
                    stops.addView(h)
                }
                for (k in 0 until ahead.length()) {
                    val s = ahead.getJSONObject(k)
                    val name = s.optString("name")
                    val off = s.optDouble("off")
                    val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
                    val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    labels.addView(StudioUi.text(activity, name, 15f, true, StudioUi.INK))
                    val inS = if (s.isNull("inS")) null else s.optDouble("inS")
                    val lines = s.optJSONArray("lines") ?: JSONArray()
                    val parts = mutableListOf<String>()
                    if (inS != null) parts += if (inS < 60) "<1 mnt" else "${Math.round(inS / 60)} mnt"
                    if (!s.optBoolean("last")) parts += "${exact(s.optDouble("on"))} naik"
                    if (lines.length() > 0) parts += "ganti ke " + List(lines.length()) { lines.getJSONObject(it).optString("name") }.joinToString(", ")
                    labels.addView(StudioUi.text(activity, parts.joinToString(" · "), 12.5f, true, StudioUi.MUTED))
                    line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    line.addView(StudioUi.text(activity, exact(off), 16f, true, StudioUi.INK).apply { typeface = StudioUi.heavy(activity) })
                    // web trainStops.stopTip / lastTip, read out in place of the hover
                    line.contentDescription = if (s.optBoolean("last")) "Semua penumpang turun di $name, ujung lini"
                        else "${exact(off)} dari ${exact(s.optDouble("arriving"))} penumpang di dalam turun di $name, dan ${exact(s.optDouble("on"))} naik"
                    stops.addView(line)
                }
                val one = r.optJSONObject("rider")
                rider.visibility = if (one == null) View.GONE else View.VISIBLE
                if (one != null) {
                    from.text = one.optString("from")
                    to.text = one.optString("to")
                    purpose.text = "● " + one.optString("purposeName")
                    purpose.setTextColor(one.optInt("color") or 0xff000000.toInt())
                    val split = one.optJSONArray("split") ?: JSONArray()
                    drawn.text = if (split.length() == 0) "Belum ada pembagian per tujuan untuk ruas lini ini"
                        else "Diambil dari ${exact(one.optDouble("drawnFrom"))} penumpang di ruas lini ini pada ${r.optString("period").lowercase()}: " +
                            List(split.length()) { split.getJSONObject(it) }.joinToString(", ") { "${it.optString("name")} ${Math.round(it.optDouble("share") * 100)}%" }
                }
            }
            var asked = false
            refresh = {
                val t = now()
                Engine.trainInfo(engine, profile, dep / 100.0, t)?.takeIf { it != "null" }?.let { showPlace(JSONObject(it)) }
                if (riders == null) showGauge(gauge())
                else if (!asked) {
                    asked = true
                    riders.read(t, seed) { text ->
                        asked = false
                        if (!sheet.isShowing) return@read
                        val r = text?.let(::JSONObject)
                        when {
                            r == null -> showGauge(gauge())
                            r.optBoolean("finished") -> { showGauge(null); aboard.text = "Sudah selesai jalan" }
                            else -> showRiders(r)
                        }
                    }
                }
            }
            showPlace(first)
            showGauge(fill)
            refresh()
            // follow the train while the sheet is up
            val tick = object : Runnable {
                override fun run() {
                    if (!sheet.isShowing) return
                    refresh()
                    body.postDelayed(this, 1000)
                }
            }
            body.postDelayed(tick, 1000)
        }
    }
}
