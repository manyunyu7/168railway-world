// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The new-world sheet's example networks: every start a city offers as a large map card in a row that
 * swipes one card at a time, the next card peeking in, with dots and "2 dari 7" under it.
 */
internal object StudioExampleNetworks {
    class Example(val id: String, val image: String?, val title: String, val detail: String,
                  val counts: List<String> = emptyList(), val modes: String = "")

    /**
     * Lines and stations of the bundled scenario saves (live lines, distinct stop names; bus stops are only
     * placed once a world runs, so TransJakarta-only has no station count). Recount when a save changes.
     */
    val facts: Map<String, Pair<List<String>, String>> = mapOf(
        "jak_mulai" to (listOf("17 line", "39 stasiun") to "KRL · TransJakarta"),
        "indo" to (listOf("199 line", "500 stasiun") to "KA antarkota · KRL · MRT · LRT · Kalayang · TransJakarta"),
        "jak" to (listOf("28 line", "123 stasiun") to "KRL · MRT · LRT · Kalayang · TransJakarta"),
        "jak_2019" to (listOf("25 line", "106 stasiun") to "KRL · MRT · LRT · Kalayang · TransJakarta"),
        "jak_krl" to (listOf("9 line", "83 stasiun") to "KRL"),
        "jak_tj" to (listOf("14 line") to "TransJakarta"),
        "jak_bogor" to (listOf("2 line", "27 stasiun") to "KRL")
    )

    private fun dp(c: Context, n: Int) = StudioUi.dp(c, n)

    /** Cards for [examples], [selected] marked; tapping one calls [pick] with its id. */
    fun carousel(activity: Activity, examples: List<Example>, selected: String, pick: (String) -> Unit): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val many = examples.size > 1
        val counter = StudioUi.text(activity, "", 13f, true, StudioUi.MUTED).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (many) {
            column.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(StudioUi.text(activity, "${examples.size} jaringan contoh · geser untuk melihat", 13f, true, StudioUi.INK),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(counter)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 6) })
        }
        val strip = Strip(activity)
        val marks = ArrayList<(Boolean) -> Unit>()
        var current = examples.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        examples.forEachIndexed { i, example ->
            val (card, mark) = card(activity, example, i, examples.size)
            card.setOnClickListener {
                if (current != i) {
                    marks[current](false)
                    current = i
                    mark(true)
                    pick(example.id)
                }
                strip.snapTo(i)
            }
            mark(i == current)
            marks += mark
            strip.row.addView(card)
        }
        column.addView(strip, LinearLayout.LayoutParams(-1, -2))
        if (many) {
            val dots = LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val dot = examples.map { View(activity).also { dots.addView(it) } }
            fun show(page: Int) {
                counter.text = "${page + 1} dari ${examples.size}"
                dot.forEachIndexed { i, v ->
                    v.background = StudioUi.pill(activity, if (i == page) StudioUi.BLUE else 0x3322204a, 4)
                    v.layoutParams = LinearLayout.LayoutParams(dp(activity, if (i == page) 20 else 8), dp(activity, 8)).apply {
                        marginStart = dp(activity, 3); marginEnd = dp(activity, 3)
                    }
                }
            }
            show(current)
            strip.onPage = ::show
            column.addView(dots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) })
        }
        strip.start(current)
        return column
    }

    /** One example: snapshot with the check over it, name, summary chips, modes and a short description. */
    private fun card(activity: Activity, example: Example, index: Int, total: Int): Pair<LinearLayout, (Boolean) -> Unit> {
        val c: Context = activity
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 12))
            minimumHeight = dp(c, 48)
            isFocusable = true
            isClickable = true
        }
        val frame = FrameLayout(c)
        val bitmap = example.image?.let { path ->
            runCatching { c.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        frame.addView(if (bitmap != null) KScene.mapCover(c, bitmap, 16) else KScene.Island(c).apply {
            background = StudioUi.pill(c, StudioUi.TINT_GREEN, 16)
        }, FrameLayout.LayoutParams(-1, -1))
        val check = StudioUi.text(c, "", 16f, true, StudioUi.WHITE).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(check, FrameLayout.LayoutParams(dp(c, 32), dp(c, 32), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(c, 8); marginEnd = dp(c, 8)
        })
        card.addView(frame, LinearLayout.LayoutParams(-1, dp(c, 150)))
        card.addView(StudioUi.text(c, example.title, 16f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(c); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10); marginStart = dp(c, 4); marginEnd = dp(c, 4) })
        if (example.counts.isNotEmpty()) {
            val chips = LinearLayout(c)
            example.counts.forEachIndexed { i, value ->
                val (fill, ink) = if (i == 0) StudioUi.TINT_PURPLE to 0xff3f2aa8.toInt() else StudioUi.TINT_ORANGE to 0xff9a3a0b.toInt()
                chips.addView(StudioUi.chip(c, value, fill, ink), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(c, 6) })
            }
            card.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 6); marginStart = dp(c, 4) })
        }
        if (example.modes.isNotEmpty()) card.addView(StudioUi.text(c, example.modes, 12f, true, 0xff3f2aa8.toInt()).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 6); marginStart = dp(c, 4); marginEnd = dp(c, 4) })
        card.addView(StudioUi.text(c, example.detail, 13f, true, StudioUi.MUTED).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 4); marginStart = dp(c, 4); marginEnd = dp(c, 4) })
        val spoken = listOf(example.title, example.detail, example.counts.joinToString(", "), example.modes).filter { it.isNotBlank() }
        val mark: (Boolean) -> Unit = { on ->
            card.background = KScene.choiceBackground(c, on)
            card.isSelected = on
            check.text = if (on) "✓" else ""
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) StudioUi.BLUE else 0xb3ffffff.toInt())
                setStroke(dp(c, 2), if (on) StudioUi.WHITE else 0x6622204a)
            }
            card.contentDescription = (spoken + listOfNotNull(if (on) "Terpilih" else null,
                if (total > 1) "${index + 1} dari $total" else null)).joinToString(". ")
        }
        return card to mark
    }

    /** Horizontal row of equal cards that settles on one card per swipe; with more than one the next peeks in. */
    private class Strip(context: Context) : HorizontalScrollView(context) {
        val row = LinearLayout(context)
        var onPage: (Int) -> Unit = {}
        private val gap = StudioUi.dp(context, 10)
        private var sized = 0
        private var cardWidth = 0
        private var pending = -1
        private var flung = false
        private var shown = -1

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row, LayoutParams(-2, -2))
        }

        private val step get() = (cardWidth + gap).coerceAtLeast(1)
        private val last get() = (row.childCount - 1).coerceAtLeast(0)
        private fun page() = ((scrollX + step / 2) / step).coerceIn(0, last)

        fun start(page: Int) { pending = page; shown = -1 }

        fun snapTo(page: Int) = smoothScrollTo(page.coerceIn(0, last) * step, 0)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            if (w > 0 && w != sized) {
                if (sized > 0 && pending < 0) pending = page()
                sized = w
                val n = row.childCount
                cardWidth = if (n > 1) (w * .86f).toInt() else w
                for (i in 0 until n) row.getChildAt(i).layoutParams = LinearLayout.LayoutParams(cardWidth, -1).apply {
                    if (i < n - 1) marginEnd = gap
                }
                // Room after the last card so it too can settle at the left edge.
                row.setPadding(0, 0, if (n > 1) w - cardWidth else 0, 0)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (pending >= 0 && cardWidth > 0) {
                scrollTo(pending.coerceIn(0, last) * step, 0)
                pending = -1
            }
        }

        override fun fling(velocityX: Int) {
            flung = true
            val base = (scrollX / step).coerceIn(0, last)
            snapTo(if (velocityX > 0) base + 1 else base)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
            val handled = super.onTouchEvent(ev)
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) snapTo(page())
            return handled
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val p = page()
            if (p != shown) { shown = p; onPage(p) }
        }
    }
}
