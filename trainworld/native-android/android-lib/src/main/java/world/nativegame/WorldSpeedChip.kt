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

    /** Eight common speeds as chips; the slider below still reaches every web SLIDER_STOPS speed. */
    private val presets = listOf(stops[0], .5, 1.0, 4.0, 8.0, 16.0, 64.0, 1000.0)

    private fun slider() = StudioUi.sheet(host, "Kecepatan simulasi") { body, sheet ->
        val value = StudioUi.text(host, label(multiple), 16f, true, StudioUi.INK)
        body.addView(StudioUi.text(host, "1× = 1 menit di game per detik. Saat penumpang dihitung, paling cepat 4×.", 12f, false, StudioUi.MUTED))
        body.addView(value, LinearLayout.LayoutParams(-2, -2).apply { topMargin = StudioUi.dp(host, 4) })
        val chips = mutableListOf<android.widget.Button>()
        fun mark() = chips.forEachIndexed { i, b ->
            StudioUi.select(b, presets[i] == multiple)
            // past 4× while riders are counted the speed is held at 4×: those presets read as unavailable
            b.alpha = if (capped && presets[i] > 4) .45f else 1f
        }
        val bar = SeekBar(host).apply {
            max = stops.lastIndex
            progress = stops.indexOf(multiple)
            contentDescription = "Atur kecepatan simulasi"
            StudioUi.styleSeek(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, index: Int, fromUser: Boolean) {
                    if (fromUser) { select(stops[index], start = true); value.text = label(multiple); mark() }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        presets.chunked(4).forEach { row ->
            val line = LinearLayout(host)
            row.forEach { x ->
                val b = StudioUi.choiceChip(host, label(x), x == multiple) {
                    select(x, start = true); value.text = label(multiple); bar.progress = stops.indexOf(x); mark()
                }
                chips += b
                line.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = StudioUi.dp(host, 6) })
            }
            body.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        mark()
        body.addView(bar, LinearLayout.LayoutParams(-1, StudioUi.dp(host, 44)).apply { topMargin = StudioUi.dp(host, 4) })
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
