// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.view.ViewGroup

/** Minimal wrapping row for chips (no external flexbox dependency). */
internal class WrapRow(context: Context) : ViewGroup(context) {
    private val gap = (6 * context.resources.displayMetrics.density).toInt()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
        setMeasuredDimension(width, y + line + gap)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
    }
}
