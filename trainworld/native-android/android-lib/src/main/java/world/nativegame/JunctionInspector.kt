// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Web `JunctionInspector.tsx` (SPEC 8): the routes through a junction, the trains an hour on each
 * and the wait per train where they cross, the busiest crossing, and its flyover. Opened from the
 * trouble list (a junction's capacity row, web `nearestJunction`) and by tapping a junction on the map.
 */
internal object JunctionInspector {
    /** The junction nearest world (x, y) within [radius] m (any distance when 0), or -1. */
    @JvmStatic external fun at(handle: Long, x: Double, y: Double, radius: Double): Int
    @JvmStatic private external fun read(handle: Long, node: Int, now: Double): String
    @JvmStatic private external fun flying(handle: Long, node: Int, flying: Boolean, clock: Double): String

    private val ID = Locale.forLanguageTag("id-ID")
    private fun levelLabel(l: Int) = if (l > 0) "+$l" else if (l < 0) "−${-l}" else "0"
    /** Web `formatDuration` for Indonesian: "4m00s", "1h05m", "45s". */
    private fun duration(s: Double): String {
        val t = Math.round(s); val h = t / 3600; val m = t / 60 % 60; val sec = t % 60
        return when { t >= 3600 -> String.format(Locale.ROOT, "%dh%02dm", h, m); t >= 60 -> String.format(Locale.ROOT, "%dm%02ds", m, sec); else -> "${t}s" }
    }
    private fun colour(v: Double) = (v.toLong().toInt() and 0xffffff) or 0xff000000.toInt()
    private val DEMAND = listOf("tinggi", "sedang", "rendah")
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    fun show(activity: Activity, handle: Long, node: Int, clock: Double, transaction: PanelEdit, changed: () -> Unit) {
        if (handle == 0L) return
        val payload = read(handle, node, clock)
        if (payload == "null") return
        val j = JSONObject(payload)
        val moves = j.getJSONArray("moves").objects()
        val ports = j.getJSONArray("portList").objects()
        val flyingNow = j.optBoolean("flying")
        val built = j.optBoolean("built")
        val demand = DEMAND.getOrElse(j.optInt("demand")) { "rendah" }
        fun dp(n: Int) = StudioUi.dp(activity, n)
        fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        StudioUi.sheet(activity, "Persimpangan", heightFraction = .78f) { column, dialog ->
            column.addView(StudioUi.text(activity, "Level ${levelLabel(j.optInt("level"))}, ${j.optInt("ports")} rel bertemu" +
                if (flyingNow) ", dengan flyover" else "", 14f, true, StudioUi.MUTED))
            if (!built) column.addView(StudioUi.chip(activity, "Rencana", StudioUi.TINT_BLUE, StudioUi.INK), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            fun setFlying(on: Boolean) {
                dialog.dismiss()
                transaction { now ->
                    val result = JSONObject(flying(handle, node, on, now))
                    if (result.has("error")) Toast.makeText(activity, result.getString("error"), Toast.LENGTH_LONG).show()
                    else {
                        changed()
                        if (on) {
                            val charge = result.optDouble("charge")
                            Toast.makeText(activity, if (charge > 0) "Flyover dibangun seharga ${StudioFormat.money(charge * 1e6, StudioCurrency.current(activity))}."
                                else "Flyover masuk ke rencana.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    show(activity, handle, node, clock, transaction, changed)
                }
            }
            // web: a planned flyover can come off; a flat junction (not at a crossover) can have one
            if (flyingNow) {
                if (!built) column.addView(StudioUi.button(activity, "Hapus flyover") { setFlying(false) }, lp(10))
            } else if (!j.optBoolean("atCrossover")) {
                column.addView(StudioUi.text(activity, if (built) "Bayar langsung" else "Masuk ke rencana", 12f, true, StudioUi.MUTED).apply {
                    setPadding(dp(2), dp(10), 0, 0)
                })
                column.addView(StudioUi.button(activity, if (built) "Bangun flyover" else "Tambah flyover", StudioUi.Tone.PRIMARY) { setFlying(true) }.apply {
                    contentDescription = "${text}. Satu rel lewat di atas yang lain, jadi kereta tak perlu menunggu menyeberang"
                }, lp(4))
            }
            if (!flyingNow) {
                column.addView(StudioUi.text(activity, if (j.optBoolean("crossing")) "Kereta saling menunggu untuk menyeberang di sini"
                    else "Tak ada kereta menyeberang di sini sekarang.", 14f, true, StudioUi.MUTED).apply { setPadding(dp(2), dp(12), 0, 0) })
                if (!j.optBoolean("atCrossover")) {
                    val price = j.optDouble("price", 0.0)
                    column.addView(StudioUi.row(activity, null, 0, "Harga flyover", null,
                        figure(activity, StudioFormat.money(price * 1e6, StudioCurrency.current(activity)))), lp(8))
                    val perTrain = j.optDouble("perTrain", 0.0)
                    column.addView(StudioUi.row(activity, null, 0, "Waktu tunggu yang dihemat", null,
                        figure(activity, if (perTrain >= 1) "${duration(perTrain)} per kereta pada permintaan $demand" else "tidak ada")), lp(6))
                }
            }
            if (moves.isEmpty()) {
                column.addView(StudioUi.text(activity, "Tidak ada lini aktif yang lewat sini.", 14f, true, StudioUi.MUTED).apply { setPadding(dp(2), dp(12), 0, 0) })
                return@sheet
            }
            column.addView(JunctionMap(activity, ports, moves).apply {
                contentDescription = "Peta persimpangan: " + moves.joinToString("; ") { m ->
                    "${m.getString("label")}, ${Math.round(m.optDouble("tph"))} kereta per jam" +
                        if (m.getJSONArray("crosses").length() > 0) ", memotong rute lain secara sebidang" else ""
                }
            }, LinearLayout.LayoutParams(dp(170), dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
            val head = LinearLayout(activity).apply { gravity = Gravity.BOTTOM }
            head.addView(StudioUi.section(activity, "Rute yang lewat"), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(StudioUi.text(activity, "kereta per jam, tunggu per kereta", 11f, true, StudioUi.MUTED))
            column.addView(head, lp(12))
            for (m in moves) column.addView(moveRow(activity, m, moves), lp(6))
            if (j.optBoolean("crossing")) {
                val pct = Math.round(j.optDouble("worst") * 100)
                column.addView(StudioUi.row(activity, null, 0, "Persilangan tersibuk", null,
                    figure(activity, "${String.format(ID, "%d", pct)}% dari kapasitas")), lp(10))
            }
        }
    }

    private fun figure(c: Context, value: String) = StudioUi.text(c, value, 14f, true, StudioUi.INK).apply {
        gravity = Gravity.END
        maxWidth = StudioUi.dp(c, 190)
    }

    /** One route through the junction: its lines, from and to, what it crosses, trains an hour and the wait. */
    private fun moveRow(c: Context, m: JSONObject, all: List<JSONObject>): View {
        fun dp(n: Int) = StudioUi.dp(c, n)
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.card(c, 18)
            minimumHeight = dp(56)
            setPadding(dp(10), dp(8), dp(12), dp(8))
        }
        val lines = m.getJSONArray("lines").objects()
        val bullets = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        lines.take(3).forEachIndexed { i, l ->
            bullets.addView(StudioUi.badge(c, ServicePanel.code(l.optString("name")), colour(l.optDouble("color")), 26),
                LinearLayout.LayoutParams(dp(26), dp(26)).apply { if (i > 0) marginStart = dp(3) })
        }
        row.addView(bullets)
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(StudioUi.text(c, m.getString("label"), 14f, true))
        val crosses = m.getJSONArray("crosses")
        if (crosses.length() > 0) {
            val names = List(crosses.length()) { all.getOrNull(crosses.getInt(it))?.optString("label").orEmpty() }.filter { it.isNotEmpty() }
            val list = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " dan " + names.last()
            texts.addView(StudioUi.text(c, "Memotong $list", 12f, true, StudioUi.MUTED))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        row.addView(StudioUi.text(c, Math.round(m.optDouble("tph")).toString(), 15f, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(34), -2))
        val delay = m.optDouble("delay")
        row.addView(StudioUi.text(c, if (delay > 0) "+" + duration(delay) else "", 14f, true, StudioUi.MUTED).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(58), -2))
        row.contentDescription = m.getString("label") + ", ${Math.round(m.optDouble("tph"))} kereta per jam" +
            (if (delay > 0) ", tunggu ${duration(delay)} per kereta" else "") +
            ", lini " + lines.joinToString { it.optString("name") }
        return row
    }

    /** Web `JunctionMap`: each track leaving the junction, and the moves as curves through the middle
     * in the first line's colour; a move that crosses another is drawn thicker. */
    private class JunctionMap(c: Context, val ports: List<JSONObject>, val moves: List<JSONObject>) : View(c) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        // Branches leave almost along the main line; spread close ends to at least 30 degrees apart.
        private val ang = DoubleArray(ports.size) { atan2(ports[it].optDouble("dy"), ports[it].optDouble("dx")) }.also { a ->
            val min = PI / 6
            repeat(4) {
                for (i in a.indices) for (k in i + 1 until a.size) {
                    var d = a[k] - a[i]
                    d = atan2(sin(d), cos(d))
                    if (abs(d) < min) {
                        val push = (min - abs(d)) / 2 * (if (d >= 0) 1 else -1)
                        a[i] -= push; a[k] += push
                    }
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            val k = width / 112f
            val c = width / 2f
            val r = 46 * k
            fun end(i: Int) = Pair(c + cos(ang[i]).toFloat() * r, c - sin(ang[i]).toFloat() * r)
            pen.color = StudioUi.MUTED; pen.alpha = 77; pen.strokeWidth = 7 * k
            for (i in ports.indices) { val (x, y) = end(i); canvas.drawLine(c, c, x, y, pen) }
            pen.alpha = 255
            for (m in moves) {
                val from = m.optInt("from"); val to = m.optInt("to")
                if (from !in ports.indices || to !in ports.indices) continue
                val (x1, y1) = end(from); val (x2, y2) = end(to)
                val line = m.getJSONArray("lines").optJSONObject(0)
                pen.color = line?.let { colour(it.optDouble("color")) } ?: 0xff888888.toInt()
                pen.strokeWidth = (if (m.getJSONArray("crosses").length() > 0) 3f else 1.5f) * k
                canvas.drawPath(Path().apply { moveTo(x1, y1); quadTo(c, c, x2, y2) }, pen)
            }
            pen.style = Paint.Style.FILL; pen.color = StudioUi.INK
            canvas.drawCircle(c, c, 3 * k, pen)
            pen.style = Paint.Style.STROKE
        }
    }
}
