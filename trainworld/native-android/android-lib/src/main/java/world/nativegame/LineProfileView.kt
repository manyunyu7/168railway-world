// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The line panel's foot: web `LineTopSpeed.tsx` (T-2987: the top speed its trains reach and the
 * line's speed limit) and web `LineProfile.tsx` (T-2734, "Penampang": the track along the line by
 * level, its stops), with the speed the trains run at along it drawn over the section.
 */
internal object LineProfileView {
    @JvmStatic private external fun read(handle: Long, id: Int): String
    @JvmStatic private external fun limit(handle: Long, id: Int, kmh: Int, clock: Double): String

    /** Sets the line's speed limit (0 takes it off); the error to show, or null once it is set. */
    fun setLimit(handle: Long, id: Int, kmh: Int, clock: Double): String? =
        JSONObject(limit(handle, id, kmh, clock)).let { if (it.has("error")) it.getString("error") else null }

    /** Web `LEVEL_COLOURS` (palette.ts), level −3 to +3. */
    private val LEVEL_COLOURS = intArrayOf(0xffc2351c.toInt(), 0xffee7a1a.toInt(), 0xfff0b928.toInt(), 0xff58b04a.toInt(),
        0xff16a39a.toInt(), 0xff2f6fd0.toInt(), 0xff4a2c9a.toInt())
    /** The drawing's height a level, m (web `PROFILE_LEVEL_M`); the sim's own is 8 m. */
    private const val PROFILE_LEVEL_M = 5.0
    private const val SIM_LEVEL_M = 8.0
    private fun levelOf(simH: Double) = (simH / SIM_LEVEL_M).roundToInt().coerceIn(-3, 3)
    private fun levelLabel(l: Int) = if (l > 0) "+$l" else if (l < 0) "−${-l}" else "0"
    private fun speed(kmh: Double) = "${kmh.roundToInt()} km/h"
    /** Web `distance` (format.ts). */
    private fun distance(m: Double) = if (m >= 1000) String.format(Locale.ROOT, "%.1f km", m / 1000) else String.format(Locale.ROOT, "%.2f km", m / 1000)

    /**
     * The section for line [id], or null for a bus, a ferry or a line with no route. [setLimit]
     * (null when the panel is read-only) applies a new speed limit in km/h, 0 for none.
     */
    fun section(c: Context, handle: Long, id: Int, colour: Int, setLimit: ((Int) -> Unit)?): View? {
        if (handle == 0L) return null
        val payload = runCatching { read(handle, id) }.getOrNull() ?: return null
        if (payload == "null") return null
        val p = JSONObject(payload)
        fun dp(n: Int) = StudioUi.dp(c, n)
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val reached = p.optDouble("reached", 0.0)
        // web LineTopSpeed: hidden until the trains reach some speed
        if (reached > 0) {
            val kindTop = p.optInt("kindTop", 160)
            val step = p.optInt("step", 10); val least = p.optInt("min", 40)
            val limit = min(p.optInt("limit"), kindTop)
            val toggle = setLimit?.let { apply ->
                StudioUi.button(c, "Batasi", if (limit > 0) StudioUi.Tone.PRIMARY else StudioUi.Tone.SURFACE) {
                    apply(if (limit > 0) 0 else max(least, min(kindTop, (reached / step).toInt() * step)))
                }.apply {
                    minHeight = dp(48); minimumHeight = dp(48)
                    contentDescription = if (limit > 0) "Batasi, aktif. Ketuk untuk melepas batas laju" else "Batasi kecepatan line ini"
                }
            }
            val top = StudioUi.row(c, null, 0, "Kecepatan puncak", speed(reached), toggle)
            box.addView(top)
            if (limit > 0) {
                val stepper = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
                fun stepButton(label: String, d: Int, name: String) = StudioUi.button(c, label) {
                    val to = max(least, min(kindTop, limit + d * step))
                    if (to != limit) setLimit?.invoke(to)
                }.apply {
                    contentDescription = name
                    isEnabled = setLimit != null && max(least, min(kindTop, limit + d * step)) != limit
                }
                stepper.addView(stepButton("−", -1, "Kurangi batas laju"), LinearLayout.LayoutParams(dp(52), dp(48)))
                stepper.addView(StudioUi.text(c, speed(limit.toDouble()), 15f, true).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(84), -2))
                stepper.addView(stepButton("+", 1, "Tambah batas laju"), LinearLayout.LayoutParams(dp(52), dp(48)))
                box.addView(StudioUi.row(c, null, 0, "Batas laju", null, stepper), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        val points = p.getJSONArray("points")
        if (points.length() >= 2) {
            box.addView(StudioUi.section(c, "Penampang"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (box.childCount > 0) 14 else 0) })
            val readout = StudioUi.text(c, "Geser di grafik untuk melihat tiap tempat di line.", 12.5f, true, StudioUi.MUTED).apply {
                minHeight = dp(54)
                setPadding(dp(4), dp(4), dp(4), 0)
            }
            box.addView(Chart(c, p, colour, readout).apply {
                background = StudioUi.card(c, 18)
            }, LinearLayout.LayoutParams(-1, dp(168)).apply { topMargin = dp(6) })
            box.addView(readout)
        }
        return box
    }

    /**
     * The line from west to east: above, the speed its trains run at (line colour, with its peak
     * marked); below, the track over a flat ground line, coloured by level; its stops as white
     * dots. A finger on it shows the place, as the web's close-up does with the pointer.
     */
    @SuppressLint("ViewConstructor")
    private class Chart(c: Context, p: JSONObject, val colour: Int, val readout: TextView) : View(c) {
        private val len = p.optDouble("len", 1.0).coerceAtLeast(1.0)
        private val pts: List<DoubleArray> = p.getJSONArray("points").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } } }
        private val stops: List<Pair<Double, String>> = (p.optJSONArray("stops") ?: JSONArray()).let { a -> List(a.length()) { a.getJSONArray(it).getDouble(0) to a.getJSONArray(it).optString(1) } }
        private val vTop = max(40.0, pts.maxOf { it[1] })
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = StudioUi.dp(c, 11).toFloat(); color = StudioUi.MUTED }
        private var at: Double? = null
        private fun d(n: Float) = n * resources.displayMetrics.density

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Penampang line sepanjang ${distance(len)}: kecepatan tertinggi ${speed(pts.maxOf { it[1] })}, " +
                stops.size + " pemberhentian dari " + (stops.firstOrNull()?.second ?: "") + " ke " + (stops.lastOrNull()?.second ?: "")
        }

        private fun pointAt(s: Double): DoubleArray {
            val i = pts.indexOfFirst { it[0] >= s }.let { if (it <= 0) 1 else it }.coerceAtMost(pts.lastIndex)
            val a = pts[i - 1]; val b = pts[i]
            val t = if (b[0] > a[0]) ((s - a[0]) / (b[0] - a[0])).coerceIn(0.0, 1.0) else 0.0
            return doubleArrayOf(s, a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2]))
        }

        override fun onDraw(canvas: Canvas) {
            val pad = d(10f)
            val w = width - 2 * pad
            val x = { s: Double -> (pad + s / len * w).toFloat() }
            // speed band on top, the section under it
            val speedTop = d(16f); val speedBottom = height * .52f
            val ySpeed = { v: Double -> (speedBottom - v / vTop * (speedBottom - speedTop)).toFloat() }
            val ground = height - d(22f)
            val levelPx = d(7f)
            val yTrack = { h: Double -> (ground - h / SIM_LEVEL_M * levelPx).toFloat() }
            // the window the finger is on, as the web's highlighted 2 km
            at?.let { s ->
                pen.style = Paint.Style.FILL; pen.color = StudioUi.BLUE; pen.alpha = 38
                val half = max(d(2f), (1000 / len * w).toFloat())
                canvas.drawRect(x(s) - half, 0f, x(s) + half, height.toFloat(), pen)
                pen.alpha = 255
            }
            // ground line
            pen.style = Paint.Style.STROKE; pen.strokeWidth = d(1f); pen.color = StudioUi.MUTED; pen.alpha = 90
            canvas.drawLine(pad, ground, pad + w, ground, pen)
            pen.alpha = 255
            // speed
            pen.color = colour; pen.strokeWidth = d(2f)
            val path = Path()
            pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(x(q[0]), ySpeed(q[1])) else path.lineTo(x(q[0]), ySpeed(q[1])) }
            canvas.drawPath(path, pen)
            label.textAlign = Paint.Align.LEFT
            canvas.drawText(speed(vTop), pad, speedTop - d(4f), label)
            // track by level
            pen.strokeWidth = d(3f)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                pen.color = LEVEL_COLOURS[levelOf((a[2] + b[2]) / 2) + 3]
                canvas.drawLine(x(a[0]), yTrack(a[2]), x(b[0]), yTrack(b[2]), pen)
            }
            // stops
            for ((s, _) in stops) {
                val q = pointAt(s)
                pen.style = Paint.Style.FILL; pen.color = StudioUi.WHITE
                canvas.drawCircle(x(s), yTrack(q[2]), d(3.5f), pen)
                pen.style = Paint.Style.STROKE; pen.color = StudioUi.INK; pen.strokeWidth = d(1.5f)
                canvas.drawCircle(x(s), yTrack(q[2]), d(3.5f), pen)
            }
            label.textAlign = Paint.Align.LEFT
            stops.firstOrNull()?.let { canvas.drawText(it.second, pad, height - d(6f), label) }
            label.textAlign = Paint.Align.RIGHT
            stops.lastOrNull()?.takeIf { stops.size > 1 }?.let { canvas.drawText(it.second, pad + w, height - d(6f), label) }
            at?.let { s ->
                pen.style = Paint.Style.STROKE; pen.color = StudioUi.INK; pen.strokeWidth = d(1f); pen.alpha = 120
                canvas.drawLine(x(s), 0f, x(s), height.toFloat(), pen)
                pen.alpha = 255
                pen.style = Paint.Style.FILL; pen.color = colour
                canvas.drawCircle(x(s), ySpeed(pointAt(s)[1]), d(4f), pen)
            }
        }

        private val drag = StudioUi.SidewaysDrag()
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            drag.track(this, e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val pad = d(10f); val w = width - 2 * pad
                    var s = ((e.x - pad) / w * len).coerceIn(0.0, len)
                    // a stop this near the finger takes the place (web SNAP_PX)
                    stops.minByOrNull { abs(it.first - s) }?.let { if (abs(it.first - s) / len * w < d(8f)) s = it.first }
                    at = s
                    describe(s)
                    invalidate()
                }
            }
            return true
        }

        /** Web Lens words: how far from the first stop, the track's level and height, and the speed there. */
        private fun describe(s: Double) {
            val q = pointAt(s)
            val lv = levelOf(q[2])
            val above = q[2] / SIM_LEVEL_M * PROFILE_LEVEL_M
            val h = "${abs(above).roundToInt()} m"
            val track = when {
                lv > 0 -> "Rel di level ${levelLabel(lv)}, $h di atas tanah"
                lv < 0 -> "Rel di level ${levelLabel(lv)}, $h di bawah tanah"
                else -> "Rel di permukaan tanah"
            }
            val near = stops.firstOrNull { abs(it.first - s) < 1 }?.second
            val from = stops.firstOrNull()?.second.orEmpty()
            readout.text = listOfNotNull(
                near ?: if (from.isNotEmpty()) "${distance(s)} dari $from" else null,
                track,
                "Kereta melaju ${speed(q[1])}",
            ).joinToString("\n")
            readout.setTextColor(StudioUi.INK)
        }
    }
}
