package world.nativegame

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

/**
 * A line's look as the web's line title edits it (ui/inspector/LineTitle.tsx): symbol, bullet shape
 * (T-3319) and colour (T-670, the ColourPicker with LINE_PALETTE), the colour shown on the badge and the
 * map while it is picked (T-672), and saved as one edit. Every line badge is drawn here, with the symbol
 * and shape the geometry carries (Rust `line_look::looks`).
 */
internal object LineLook {
    @JvmStatic private external fun apply(handle: Long, id: Int, now: Double, colour: Int, symbol: String, shape: String): String

    /** The undo entry's label, and what tells the activity to redraw the map's lines. */
    const val LABEL = "Tampilan line"
    /** Web `LINE_PALETTE` (game/palette.ts). */
    val PALETTE = intArrayOf(0xd7263d, 0x1f6fd1, 0x1a9a4a, 0xf08a00, 0x8a3fb8, 0xf2c200, 0x8f5a2a, 0x0e9a9a,
        0x74797e, 0x9ccb19, 0xf59dc6, 0xe62aa6, 0x5cc3f0, 0x6f7a0f, 0xee7d63, 0xa9a0e8)
    /** Web `LINE_SHAPES` and their names (i18n widgets.*). */
    private val SHAPES = listOf("circle" to "Lingkaran", "square" to "Kotak", "diamond" to "Wajik", "outline" to "Garis tepi")
    /** Rust `line_look::SYMBOL_MAX`. */
    private const val SYMBOL_MAX = 5

    private val symbols = HashMap<Int, String>()
    private val shapes = HashMap<Int, String>()
    private val tints = HashMap<Int, Int>()
    /** Redraws the map after a colour preview (set by the activity). */
    var redraw: (() -> Unit)? = null

    /** Each line's symbol and shape from the geometry's `looks`; a reload ends any colour preview. */
    fun load(geometry: JSONObject) {
        val looks = geometry.optJSONObject("looks") ?: return
        symbols.clear(); shapes.clear(); tints.clear()
        for (key in looks.keys()) {
            val id = key.toIntOrNull() ?: continue
            val l = looks.optJSONObject(key) ?: continue
            symbols[id] = l.optString("symbol")
            shapes[id] = l.optString("shape", "circle")
        }
    }

    /** A line's inspection (ServicePanel.inspect) as the latest word on its symbol and shape. */
    fun remember(service: JSONObject) {
        val id = service.optInt("id", -1).takeIf { it >= 0 } ?: return
        symbols[id] = service.optString("symbol")
        shapes[id] = service.optString("shape").takeIf { it.isNotEmpty() && it != "null" } ?: service.optString("modeShape", "circle")
    }

    /** The badge's text: the symbol the player set, else the name's code (ServicePanel.code). */
    fun letter(id: Int, name: String) = symbols[id]?.takeIf { it.isNotBlank() } ?: ServicePanel.code(name)
    fun shape(id: Int) = shapes[id] ?: "circle"
    /** The colour line `id` is drawn in on the map while its editor previews one. */
    fun tint(id: Int): Int? = tints[id]

    private fun preview(id: Int, colour: Int?) {
        if (colour == null) { if (tints.remove(id) == null) return } else tints[id] = colour or 0xff000000.toInt()
        redraw?.invoke()
    }

    /** Line `id`'s badge; lay it out with [params], it widens for a long symbol. */
    fun badge(context: Context, id: Int, name: String, colour: Int, size: Int = 40): TextView =
        draw(context, letter(id, name), shape(id), colour, size)

    fun params(context: Context, size: Int) = LinearLayout.LayoutParams(-2, StudioUi.dp(context, size))

    /** Web `inkOn`: dark ink on a light colour, else white. */
    private fun inkOn(colour: Int): Int {
        val l = .299 * Color.red(colour) + .587 * Color.green(colour) + .114 * Color.blue(colour)
        return if (l / 255 > .62) 0xff2b2b2b.toInt() else Color.WHITE
    }

    fun draw(context: Context, letter: String, shape: String, colour: Int, size: Int): TextView {
        val c = colour or 0xff000000.toInt()
        val h = StudioUi.dp(context, size)
        return StudioUi.text(context, letter, size * (if (letter.length <= 2) .42f else .34f), color = if (shape == "outline") c else inkOn(c)).apply {
            typeface = StudioUi.heavy(context)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            minWidth = when (shape) { "pill" -> (h * 1.5f).toInt(); "diamond" -> (h * 1.14f).toInt(); else -> h }
            minHeight = h
            val side = if (shape == "diamond") (h * .3f).toInt() else (h * .2f).toInt()
            setPadding(side, 0, side, 0)
            background = Bullet(shape, c, max(StudioUi.dp(context, 2).toFloat(), h * .09f))
            contentDescription = "Line $letter"
        }
    }

    /** A bullet's outline (web ui/bullet.ts `bulletDraw`): a circle or pill, a rounded square, a diamond
     * stretched into a hexagon for a wide symbol, or a ring. */
    private class Bullet(val shape: String, val colour: Int, val ring: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; strokeJoin = Paint.Join.ROUND }
        private val box = RectF()
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            paint.style = Paint.Style.FILL
            when (shape) {
                "square" -> {
                    val s = .94f * h
                    box.set(b.left.toFloat(), b.top + (h - s) / 2, b.right.toFloat(), b.top + (h + s) / 2)
                    canvas.drawRoundRect(box, s * .18f, s * .18f, paint)
                }
                "diamond" -> {
                    val r = .06f * h; val cy = b.exactCenterY(); val a = h / 2 - r; val x = b.left.toFloat()
                    path.reset()
                    path.moveTo(x + r, cy); path.lineTo(x + h / 2, cy - a); path.lineTo(x + w - h / 2, cy - a)
                    path.lineTo(x + w - r, cy); path.lineTo(x + w - h / 2, cy + a); path.lineTo(x + h / 2, cy + a); path.close()
                    paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = 2 * r
                    canvas.drawPath(path, paint)
                }
                "outline" -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = ring
                    box.set(b.left + ring / 2, b.top + ring / 2, b.right - ring / 2, b.bottom - ring / 2)
                    canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, paint)
                }
                else -> {
                    box.set(b)
                    canvas.drawRoundRect(box, h / 2, h / 2, paint)
                }
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Web ColourPicker's saturation-brightness area over its hue strip; [pick] on every move. */
    @SuppressLint("ViewConstructor")
    private class HuePad(context: Context, start: Int, val pick: (Int) -> Unit) : View(context) {
        val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
        private val strip = StudioUi.dp(context, 28).toFloat()
        private val gap = StudioUi.dp(context, 12).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private var dragging = 0
        fun set(colour: Int) { Color.colorToHSV(colour, hsv); invalidate() }
        override fun onMeasure(w: Int, h: Int) =
            setMeasuredDimension(MeasureSpec.getSize(w), (StudioUi.dp(context, 132) + gap + strip).toInt())
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val area = height - gap - strip
            val r = StudioUi.dp(context, 10).toFloat()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
            box.set(0f, 0f, w, area); c.drawRoundRect(box, r, r, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, area, 0, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, r, r, paint)
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }.also { it[6] = Color.RED }
            paint.shader = LinearGradient(0f, 0f, w, 0f, hues, null, Shader.TileMode.CLAMP)
            box.set(0f, area + gap, w, height.toFloat()); c.drawRoundRect(box, strip / 2, strip / 2, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            val knob = StudioUi.dp(context, 9).toFloat()
            fun ring(x: Float, y: Float) {
                paint.strokeWidth = StudioUi.dp(context, 4).toFloat(); paint.color = Color.WHITE; c.drawCircle(x, y, knob, paint)
                paint.strokeWidth = StudioUi.dp(context, 1).toFloat(); paint.color = StudioUi.INK; c.drawCircle(x, y, knob + StudioUi.dp(context, 2), paint)
            }
            ring(hsv[1] * w, (1 - hsv[2]) * area)
            ring(hsv[0] / 360f * w, area + gap + strip / 2)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val area = height - gap - strip
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                dragging = if (e.y <= area + gap / 2) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                dragging = 0; parent?.requestDisallowInterceptTouchEvent(false)
            }
            fun clamp(v: Float) = v.coerceIn(0f, 1f)
            when (dragging) {
                1 -> { hsv[1] = clamp(e.x / width); hsv[2] = 1 - clamp(e.y / area) }
                2 -> hsv[0] = clamp(e.x / width) * 359.9f
                else -> if (e.actionMasked != MotionEvent.ACTION_UP) return true
            }
            if (dragging != 0 || e.actionMasked == MotionEvent.ACTION_UP) { invalidate(); pick(Color.HSVToColor(hsv) and 0xffffff) }
            return true
        }
    }

    /** "Tampilan": the line's symbol, shape and colour, the badge and the map showing the choice at once,
     * kept with "Simpan" as one edit (undo) through the panel's transaction. */
    fun edit(host: Activity, handle: Long, id: Int, service: JSONObject, transaction: PanelEdit, done: () -> Unit) {
        fun dp(n: Int) = StudioUi.dp(host, n)
        remember(service)
        val name = service.optString("name")
        val startColour = ServicePanel.color(service) and 0xffffff
        val startSymbol = service.optString("symbol")
        val modeShape = service.optString("modeShape", "circle")
        val startShape = shape(id)
        var colour = startColour
        var shape = startShape
        var symbol = startSymbol

        val preview = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
        val badgeSlot = LinearLayout(host).apply { gravity = Gravity.CENTER }
        preview.addView(badgeSlot, LinearLayout.LayoutParams(dp(92), dp(52)))
        preview.addView(StudioUi.text(host, name, 15f, true, StudioUi.INK).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })

        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        fun caption(text: String) = box.addView(StudioUi.text(host, text, 12f, true, StudioUi.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })

        val shapeViews = mutableListOf<Pair<String, LinearLayout>>()
        val swatches = mutableListOf<Pair<Int, View>>()
        lateinit var hex: EditText
        lateinit var pad: HuePad
        fun paint() {
            badgeSlot.removeAllViews()
            badgeSlot.addView(draw(host, symbol.trim().ifEmpty { ServicePanel.code(name) }, shape, colour, 44), LinearLayout.LayoutParams(-2, dp(44)))
            shapeViews.forEach { (s, v) ->
                v.background = StudioUi.panel(host, if (s == shape) 0x1422204a else StudioUi.WHITE, 12, if (s == shape) StudioUi.INK else 0xffcfcbe0.toInt())
                v.isSelected = s == shape
                v.removeAllViews()
                v.addView(draw(host, "", s, colour, 18), LinearLayout.LayoutParams(-2, dp(18)))
            }
            swatches.forEach { (c, v) -> v.background = StudioUi.panel(host, c or 0xff000000.toInt(), 999, if (c == colour) StudioUi.INK else c or 0xff000000.toInt()).apply {
                if (c == colour) setStroke(dp(3), StudioUi.INK)
            } }
        }
        fun setColour(c: Int, from: Any?) {
            colour = c and 0xffffff
            if (from !== hex) { val t = String.format("#%06x", colour); if (hex.text.toString() != t) hex.setText(t) }
            if (from !== pad) pad.set(colour or 0xff000000.toInt())
            preview(id, colour)
            paint()
        }

        caption("Simbol")
        val field = EditText(host).apply {
            setText(startSymbol); setSingleLine(); setSelectAllOnFocus(true)
            StudioUi.field(this)
            hint = ServicePanel.code(name)
            contentDescription = "Simbol line, paling banyak $SYMBOL_MAX karakter; kosongkan untuk huruf dari nama"
            filters = arrayOf(InputFilter.LengthFilter(SYMBOL_MAX))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { symbol = s?.toString().orEmpty(); paint() }
            })
        }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))

        caption("Bentuk")
        val shapeRow = LinearLayout(host)
        SHAPES.forEachIndexed { i, (s, label) ->
            val v = LinearLayout(host).apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                contentDescription = label + if (s == modeShape) ", bawaan" else ""
                setOnClickListener { shape = s; paint() }
            }
            shapeViews += s to v
            shapeRow.addView(v, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        box.addView(shapeRow)
        val defaultName = SHAPES.firstOrNull { it.first == modeShape }?.second ?: "Kapsul"
        box.addView(StudioUi.text(host, "Bawaan ${service.optString("vehicle").lowercase()}: $defaultName", 11.5f, true, StudioUi.MUTED).apply { setPadding(dp(2), dp(4), 0, 0) })

        caption("Warna")
        for (row in 0 until 2) {
            val line = LinearLayout(host)
            for (k in 0 until 8) {
                val c = PALETTE[row * 8 + k]
                val cell = LinearLayout(host).apply {
                    gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                    contentDescription = String.format("Warna #%06x", c)
                    setOnClickListener { setColour(c, null) }
                }
                val dot = View(host)
                cell.addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)))
                swatches += c to dot
                line.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            box.addView(line)
        }
        pad = HuePad(host, startColour or 0xff000000.toInt()) { setColour(it, pad) }.apply {
            contentDescription = "Pilih warna bebas: geser di kotak untuk kecerahan, di pita untuk rona"
        }
        box.addView(pad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        hex = EditText(host).apply {
            setText(String.format("#%06x", startColour)); setSingleLine()
            StudioUi.field(this)
            contentDescription = "Warna dalam hex"
            filters = arrayOf(InputFilter.LengthFilter(7))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val m = Regex("^#?([0-9a-fA-F]{6})$").find(s?.toString().orEmpty().trim()) ?: return
                    val c = m.groupValues[1].toInt(16)
                    if (c != colour) setColour(c, hex)
                }
            })
        }
        box.addView(hex, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        paint()

        fun save() {
            val sym = symbol.trim()
            if (colour == startColour && sym == startSymbol.trim() && shape == startShape) { preview(id, null); return }
            transaction { now ->
                ServicePanel.editLabel = LABEL
                val r = JSONObject(apply(handle, id, now, colour, sym, if (shape == modeShape) "" else shape))
                if (r.has("error")) { preview(id, null); WorldSound.refused(host, r.getString("error")) }
                else { remember(r); done() }
            }
        }
        StudioDialogBuilder(host).setTitle("Tampilan line").setPinned(preview).setView(box)
            .setPositiveButton("Simpan") { _, _ -> save() }
            .setNegativeButton("Batal") { _, _ -> preview(id, null) }
            .setOnCancelListener { preview(id, null) }
            .create().show()
    }
}
