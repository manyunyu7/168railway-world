// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** The HUD's day arc: the sun rides from 06.00 to 18.00, the moon through the night. */
internal class SunArcView(context: Context) : View(context) {
    var second = 8 * 3600
        set(value) {
            val changed = field / 120 != value / 120
            field = value
            if (changed) invalidate()
        }
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * density; strokeCap = Paint.Cap.ROUND; color = 0x2e22204a
    }
    private val lit = Paint(track).apply { color = 0xffffb020.toInt() }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = -1 }
    private val oval = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val pad = 6f * density
        val rx = width / 2f - pad
        val ry = height - pad * 1.4f
        val cx = width / 2f
        val cy = height - pad * .5f
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawArc(oval, 180f, 180f, false, track)
        val day = second in 6 * 3600 until 18 * 3600
        val f = (if (day) second - 6 * 3600 else Math.floorMod(second - 18 * 3600, 86400)) / (12f * 3600)
        lit.color = if (day) 0xffffb020.toInt() else 0xff6c4df6.toInt()
        canvas.drawArc(oval, 180f, 180f * f.coerceIn(0f, 1f), false, lit)
        val a = Math.toRadians(180.0 + 180.0 * f.coerceIn(0f, 1f))
        val x = cx + rx * cos(a).toFloat()
        val y = cy + ry * sin(a).toFloat()
        body.color = lit.color
        canvas.drawCircle(x, y, 5.5f * density, body)
        canvas.drawCircle(x, y, 5.5f * density, rim)
    }
}
