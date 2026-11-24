// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

internal class WorldSpeedChip(private val host: Activity, initial: Double, private val changed: (start: Boolean) -> Unit) : TextView(host) {
    private val steps = listOf(1.0, 4.0, 16.0, 32.0, 64.0, 1000.0)
    private val stops = listOf(1.0/60, 4.0/60, .5, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 40.0, 50.0, 64.0, 80.0, 100.0, 200.0, 500.0, 1000.0)
    var multiple = initial.takeIf { it in stops } ?: 1.0
        private set
    var capped = false
        set(value) { if (field != value) { field = value; render(); changed(false) } }
    val rate get() = 60 * if (capped) minOf(4.0, multiple) else multiple

    private fun label(x: Double) = when (x) {
        stops[0] -> "Real"
        stops[1] -> "4× real"
        .5 -> "0,5×"
        else -> "${x.toInt()}×"
    }

    private fun render() {
        text = label(multiple)
        contentDescription = "Kecepatan simulasi ${text}" + if (capped && multiple > 4) ", dibatasi 4× saat menghitung penumpang" else ""
        setTextColor(if (capped && multiple > 4) StudioUi.BAD else StudioUi.INK)
    }

    private fun select(value: Double, start: Boolean = false) {
        if (multiple == value) return
        multiple = value
        render()
        changed(start)
    }

    /** Every web SLIDER_STOPS speed as a labelled button (8× included), plus the slider; the plain bar hid the values. */
    private fun slider() = StudioUi.sheet(host, "Kecepatan simulasi") { body, sheet ->
        val value = StudioUi.text(host, label(multiple), 18f, true, StudioUi.INK)
        body.addView(StudioUi.text(host, "1× = 1 menit di game per detik. Saat penumpang dihitung, paling cepat 4×.", 13f, false, StudioUi.MUTED))
        body.addView(value, LinearLayout.LayoutParams(-2, -2).apply { topMargin = StudioUi.dp(host, 8) })
        val buttons = mutableListOf<TextView>()
        fun mark() = buttons.forEachIndexed { i, b ->
            val on = stops[i] == multiple
            b.background = StudioUi.pill(host, if (on) StudioUi.INK else 0x1422204a, 14)
            b.setTextColor(if (on) StudioUi.WHITE else StudioUi.INK)
        }
        val bar = SeekBar(host).apply {
            max = stops.lastIndex
            progress = stops.indexOf(multiple)
            contentDescription = "Atur kecepatan simulasi"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, index: Int, fromUser: Boolean) {
                    if (fromUser) { select(stops[index], start = true); value.text = label(multiple); mark() }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        stops.chunked(4).forEach { row ->
            val line = LinearLayout(host)
            row.forEach { x ->
                val b = StudioUi.text(host, label(x), 14f, true, StudioUi.INK).apply {
                    gravity = Gravity.CENTER
                    minHeight = StudioUi.dp(host, 44)
                    isClickable = true
                    setOnClickListener { select(x, start = true); value.text = label(multiple); bar.progress = stops.indexOf(x); mark() }
                }
                buttons += b
                line.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = StudioUi.dp(host, 6) })
            }
            repeat(4 - row.size) { line.addView(android.view.View(host), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = StudioUi.dp(host, 6) }) }
            body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(host, 6) })
        }
        mark()
        body.addView(bar, LinearLayout.LayoutParams(-1, StudioUi.dp(host, 48)).apply { topMargin = StudioUi.dp(host, 8) })
    }

    private val gesture = GestureDetector(host, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
        override fun onDoubleTap(e: MotionEvent): Boolean { slider(); return true }
        // the gesture detector takes every touch, so the view's own long-click listener never fired
        override fun onLongPress(e: MotionEvent) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); slider() }
    })
    init {
        gravity = Gravity.CENTER
        minHeight = StudioUi.dp(host, 48)
        textSize = 13f
        typeface = StudioUi.heavy(host)
        background = StudioDepot.touch(host, 0xeaffffff.toInt(), 16)
        setOnClickListener { select(steps.firstOrNull { it > multiple } ?: steps.first()) }
        setOnLongClickListener { slider(); true }
        render()
    }
    override fun onTouchEvent(event: MotionEvent) = if (isEnabled) gesture.onTouchEvent(event) else false
}
