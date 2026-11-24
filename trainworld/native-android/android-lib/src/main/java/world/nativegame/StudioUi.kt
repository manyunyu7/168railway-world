// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

internal object StudioUi {
    // Kaca Diorama: playful game colours on frosted glass over a full-screen map.
    val INK = 0xff22204a.toInt()
    val MUTED = 0xff5a5878.toInt()
    val GREEN = 0xffff7a2f.toInt()          // primary action (orange)
    val GREEN_EDGE = 0xffc24e12.toInt()
    val BLUE = 0xff6c4df6.toInt()           // secondary emphasis (purple)
    val BLUE_EDGE = 0xff4630b8.toInt()
    val LEAF = 0xff3fb06a.toInt()
    val WHITE = Color.WHITE
    val BACKGROUND = 0xffcfebc0.toInt()
    val BORDER = 0xffe4e0ee.toInt()
    val SURFACE_EDGE = 0xffd8cbb0.toInt()
    val GLASS = 0xe6ffffff.toInt()
    val GLASS_STRONG = 0xf2ffffff.toInt()
    val GLASS_DARK = 0xcc22204a.toInt()
    /** Selected state everywhere (chips, segmented choices): navy. Orange stays the call to action. */
    val NAVY = 0xff22204a.toInt()
    val NAVY_EDGE = 0xff0f0e2a.toInt()
    enum class Tone { PRIMARY, BLUE, SURFACE }
    enum class Icon(val code: Int) {
        TRAIN(0xf024a), SAVE(0xf0126), PLUS(0xf537), BACK(0xf572), FOLDER(0xf77d),
        CLOUD(0xf650), PLAY(0xf00a0), MENU(0xf0258), PIN(0xf884), CENTER(0xf8f0),
        MINUS(0xf00f1), PAUSE(0xf0056), ROUTE(0xf0377), WALLET(0xf520), HELP(0xf7e5), UNDO(0xf0261), CLOSE(0xf647), LAYERS(0xf847),
        EDIT(0xf6fb), DOWNLOAD(0xf6df), COPY(0xf66c), MORE(0xf8d9), MORE_VERT(0xf8dc), SEARCH(0xf013d), INFO(0xf815),
        WARNING(0xf02a0), TROPHY(0xf707), PEOPLE(0xf005e), SCHEDULE(0xf012b), BUILD(0xf665), DELETE(0xf696),
        UPLOAD(0xf026a), OPEN_FILE(0xf0318), EYE(0xf0293), SPEED(0xf01b5), FLAG(0xf768), STAR(0xf01d4),
        SWAP(0xf01fa), BOLT(0xf5ca), CARDS(0xf0281), TABLE(0xf0210)
    }

    fun dp(c: Context, n: Int) = (c.resources.displayMetrics.density * n + .5f).toInt()
    private val fonts = mutableMapOf<String, Typeface>()
    private fun font(c: Context, file: String) = fonts.getOrPut(file) {
        Typeface.createFromAsset(c.assets, "world-native/fonts/$file")
    }
    fun face(c: Context, bold: Boolean = false) = font(c, if (bold) "Nunito-ExtraBold.ttf" else "Nunito-Regular.ttf")
    fun heavy(c: Context) = font(c, "Nunito-Black.ttf")
    fun display(c: Context) = font(c, "BricolageGrotesque-ExtraBold.ttf")
    fun title(context: Context, value: String, size: Float = 24f, color: Int = INK) =
        text(context, value, size, color = color).apply { typeface = display(context); letterSpacing = -0.01f }

    /** Frosted panel: translucent white with a bright rim. Blur comes from the window where Android supports it. */
    fun glass(context: Context, radius: Int = 26, fill: Int = GLASS) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1) + 1, 0xccffffff.toInt())
    }
    fun pill(context: Context, fill: Int, radius: Int = 999) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, minOf(radius, 200)).toFloat()
    }
    fun text(context: Context, value: String, size: Float, bold: Boolean = false, color: Int = INK) =
        TextView(context).apply {
            text = value
            textSize = size
            typeface = face(context, bold)
            setTextColor(color)
        }

    fun panel(context: Context, fill: Int = WHITE, radius: Int = 16, border: Int = BORDER) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), border)
    }

    private fun raised(c: Context, tone: Tone): StateListDrawable {
        // BLUE is the "selected" tone: navy, the one colour every panel uses for a chosen option.
        val fill = when (tone) { Tone.PRIMARY -> GREEN; Tone.BLUE -> NAVY; Tone.SURFACE -> WHITE }
        val edge = when (tone) { Tone.PRIMARY -> GREEN_EDGE; Tone.BLUE -> NAVY_EDGE; Tone.SURFACE -> SURFACE_EDGE }
        // Compact: a 2 dp lip instead of the old 5 dp slab, and 2 dp of air above and below so the face
        // reads ~44 dp while the touch target stays 48 dp.
        val air = dp(c, 2)
        fun layers(top: Int, bottom: Int, pressed: Boolean, focus: Boolean = false): Drawable {
            return LayerDrawable(arrayOf(panel(c, bottom, 14, bottom), panel(c, top, 14, if (focus) INK else top))).apply {
                setLayerInset(0, 0, air, 0, air)
                setLayerInset(1, 0, air + if (pressed) dp(c, 2) else 0, 0, air + if (pressed) 0 else dp(c, 2))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), layers(0xffefedf5.toInt(), 0xffd9d5e6.toInt(), false))
            addState(intArrayOf(android.R.attr.state_pressed), layers(fill, edge, true))
            addState(intArrayOf(android.R.attr.state_focused), layers(fill, edge, false, true))
            addState(intArrayOf(), layers(fill, edge, false))
        }
    }

    fun glyph(c: Context, icon: Icon, color: Int): Drawable {
        val size = dp(c, 20)
        val symbol = String(Character.toChars(icon.code))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(c, "StudioMaterialIcons.otf")
            textSize = size.toFloat()
            textAlign = Paint.Align.CENTER
            this.color = color
        }
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                canvas.drawText(symbol, bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent()+paint.descent())/2, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Android Drawable protocol")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }.apply { setBounds(0, 0, size, size) }
    }
    fun style(button: Button, tone: Tone) {
        if (button.tag != tone) {
            button.background = raised(button.context, tone)
            button.tag = tone
        }
    }

    fun setIcon(button: Button, icon: Icon?) {
        val ink = if (button.tag == Tone.SURFACE || button.tag == null) INK else WHITE
        button.setCompoundDrawablesRelative(icon?.let { glyph(button.context, it, ink) }, null, null, null)
    }

    fun button(context: Context, label: String, tone: Tone = Tone.SURFACE, icon: Icon? = null, click: () -> Unit) =
        Button(context).apply {
            text = label
            typeface = heavy(context)
            textSize = 14f
            isAllCaps = false
            letterSpacing = 0f
            stateListAnimator = null
            backgroundTintList = null
            style(this, tone)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(MUTED, if (tone == Tone.SURFACE) INK else WHITE)))
            minWidth = dp(context, 48)
            minimumWidth = minWidth
            minHeight = dp(context, 48)
            minimumHeight = minHeight
            setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 4))
            compoundDrawablePadding = dp(context, 4)
            setIcon(this, icon)
            setOnClickListener { click() }
        }

    fun iconButton(c: Context, description: String, icon: Icon, tone: Tone = Tone.SURFACE, click: () -> Unit) =
        button(c, String(Character.toChars(icon.code)), tone, click = click).apply {
            typeface = font(c, "StudioMaterialIcons.otf")
            textSize = 20f
            contentDescription = description
            setPadding(0, 0, 0, dp(c, 2))
        }

    /** A shared surface for pickers, confirmations and the larger gameplay tools. */
    fun layoutSheet(activity: Activity, dialog: Dialog, title: CharSequence?, body: View,
                    footer: View?, heightFraction: Float = .85f, centered: Boolean = false) {
        val root = object : LinearLayout(activity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val available = MeasureSpec.getSize(heightSpec)
                val screen = activity.window.decorView.height
                // Bottom sheets stop below the HUD pill so the clock and cash stay visible.
                val limit = if (centered) (screen * heightFraction).toInt()
                    else minOf((screen * heightFraction).toInt(), screen - dp(activity, 150))
                val height = if (limit > 0) min(available, limit) else available
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            background = glass(activity, 24, 0xfffbfaff.toInt())
            clipToOutline = true
            setPadding(dp(context,16),dp(context,6),dp(context,16),dp(context,12))
        }
        root.addView(View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = pill(context, 0x3822204a)
        }, LinearLayout.LayoutParams(dp(activity,36),dp(activity,4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity,6)
        })
        if (!title.isNullOrEmpty()) {
            val heading = StudioUi.title(activity,title.toString(),if (centered) 19f else 20f).apply {
                if (centered) gravity = Gravity.CENTER_HORIZONTAL
                // One line: a long line name ends in "…" instead of pushing the content down a row.
                if (!centered) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }
            if (centered) root.addView(heading, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(activity,8) })
            else root.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heading, LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton(activity, "Tutup", Icon.CLOSE) { dialog.cancel() }.apply {
                    background = pill(activity, 0xffefedf5.toInt(), 20)
                    setPadding(0,0,0,0)
                    textSize = 16f
                    minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                }, LinearLayout.LayoutParams(dp(activity,36),dp(activity,36)).apply { marginStart = dp(activity,8) })
            }, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(activity,6) })
        }
        root.addView(body,LinearLayout.LayoutParams(-1,-2,1f))
        footer?.let { root.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(activity,8) }) }
        dialog.setContentView(root)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setDimAmount(.18f)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Frosted glass: blur what is behind the sheet itself, keep the map visible around it.
            window.setBackgroundBlurRadius(dp(activity, 28))
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = dp(activity, 6) }
        }
        val host = activity.window.decorView
        fun resize() {
            val available = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val wide = available >= dp(activity,600)
            window.setGravity(if (wide || centered) Gravity.CENTER else Gravity.BOTTOM)
            window.setLayout(when {
                centered -> min(available-dp(activity,44),dp(activity,420))
                wide -> min(available-dp(activity,48),dp(activity,660))
                else -> available-dp(activity,16) },-2)
            window.attributes = window.attributes.apply { y = if (wide || centered) 0 else dp(activity,8) }
        }
        val listener = View.OnLayoutChangeListener { _,l,_,r,_,ol,_,or,_ ->
            if (r-l != or-ol) resize()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { host.addOnLayoutChangeListener(listener); resize() }
            override fun onViewDetachedFromWindow(v: View) { host.removeOnLayoutChangeListener(listener) }
        })
        resize()
    }

    fun sheet(activity: Activity, title: String, heightFraction: Float = .85f, pinnedAction: (() -> View)? = null, populate: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0,dp(context,2),0,dp(context,4))
        }
        populate(column, dialog)
        val scroll = ScrollView(activity).apply { addView(column) }
        layoutSheet(activity,dialog,title,scroll,pinnedAction?.invoke(),heightFraction)
        dialog.show()
        return dialog
    }

    // ---- Kaca Diorama building blocks shared by every panel ----
    /** White card on glass. */
    fun card(context: Context, radius: Int = 22, fill: Int = 0xd9ffffff.toInt()) = pill(context, fill, radius)
    /** Small uppercase section label. */
    fun section(context: Context, value: String) = text(context, value.uppercase(), 12f, true, MUTED).apply {
        letterSpacing = .06f
        setPadding(dp(context, 2), dp(context, 10), 0, dp(context, 3))
    }
    /** Rounded colour chip such as "28 jalur". */
    fun chip(context: Context, value: String, fill: Int, ink: Int) = text(context, value, 13f, true, ink).apply {
        background = pill(context, fill, 12)
        setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 5))
    }
    /**
     * The one selectable chip: 36 dp pill (48 dp touch via 6 dp air), grey when off, navy when on.
     * Use [select] to flip it later.
     */
    fun choiceChip(context: Context, label: String, on: Boolean, click: () -> Unit) = Button(context).apply {
        text = label
        typeface = face(context, true)
        textSize = 13f
        isAllCaps = false
        letterSpacing = 0f
        stateListAnimator = null
        backgroundTintList = null
        minWidth = dp(context, 44); minimumWidth = minWidth
        minHeight = dp(context, 48); minimumHeight = minHeight
        maxLines = 1
        setPadding(dp(context, 14), 0, dp(context, 14), 0)
        select(this, on)
        setOnClickListener { click() }
    }
    fun select(chip: Button, on: Boolean) {
        val c = chip.context
        val air = dp(c, 6)
        fun face(fill: Int) = LayerDrawable(arrayOf(pill(c, fill))).apply { setLayerInset(0, 0, air, 0, air) }
        chip.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), face(0xfff4f3f8.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), face(if (on) NAVY_EDGE else 0xffdedbe9.toInt()))
            addState(intArrayOf(), face(if (on) NAVY else TINT_GREY))
        }
        chip.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xff9a98b0.toInt(), if (on) WHITE else INK)))
        chip.isSelected = on
    }
    /**
     * Destructive actions are small red text with an icon, never a wide red slab. Confirmation dialogs
     * keep a filled (flat) red for the final "Hapus".
     */
    fun danger(button: Button, filled: Boolean = false) {
        val c = button.context
        val air = dp(c, 2)
        fun face(fill: Int) = LayerDrawable(arrayOf(panel(c, fill, 14, fill))).apply { setLayerInset(0, 0, air, 0, air) }
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), face(if (filled) 0xffefedf5.toInt() else 0x00000000))
            addState(intArrayOf(android.R.attr.state_pressed), face(if (filled) BAD else TINT_RED))
            addState(intArrayOf(), face(if (filled) RED else 0x00000000))
        }
        button.tag = "danger"
        val ink = if (filled) WHITE else BAD
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, ink)))
        if (!filled) button.setCompoundDrawablesRelative(glyph(c, Icon.DELETE, ink), null, null, null)
        button.compoundDrawablePadding = dp(c, 4)
    }

    /** The one text field look: white box, thin border, 48 dp, hint clearly lighter than typed text. */
    fun field(edit: android.widget.EditText) = edit.apply {
        val c = context
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), panel(c, WHITE, 12, INK))
            addState(intArrayOf(), panel(c, WHITE, 12, 0xffcfcbe0.toInt()))
        }
        typeface = face(c, true)
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(0xffaeabc2.toInt())
        minHeight = dp(c, 48); minimumHeight = minHeight
        setPadding(dp(c, 12), 0, dp(c, 12), 0)
    }

    /** Line badge: coloured rounded square with a short code. */
    fun badge(context: Context, code: String, color: Int, size: Int = 40) = text(context, code.take(2), size * .42f, color = WHITE).apply {
        typeface = heavy(context)
        gravity = Gravity.CENTER
        background = LayerDrawable(arrayOf(panel(context, darker(color), 14, darker(color)), panel(context, color, 14, color))).apply {
            setLayerInset(1, 0, 0, 0, dp(context, 3))
        }
        setPadding(0, 0, 0, dp(context, 3))
    }
    fun darker(color: Int): Int {
        val r = (Color.red(color) * .78f).toInt(); val g = (Color.green(color) * .78f).toInt(); val b = (Color.blue(color) * .78f).toInt()
        return Color.rgb(r, g, b)
    }
    /** A tappable list row on glass: icon tile, title, optional subtitle, optional trailing view. */
    fun row(context: Context, icon: Icon?, tint: Int, title: String, subtitle: String? = null, trailing: View? = null, click: (() -> Unit)? = null) =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = if (click != null) StudioDepot.touch(context, 0xd9ffffff.toInt(), 18) else card(context, 18)
            setPadding(dp(context, 8), dp(context, 5), dp(context, 10), dp(context, 5))
            minimumHeight = dp(context, 48)
            if (icon != null) addView(StudioDepot.symbol(context, icon, 17f, INK).apply {
                background = panel(context, tint, 10, tint)
            }, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(context, title, 14f, true))
            if (subtitle != null) texts.addView(text(context, subtitle, 12f, false, MUTED))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, if (icon != null) 10 else 4) })
            trailing?.let { addView(it) }
            if (click != null) { isClickable = true; contentDescription = title; setOnClickListener { click() } }
        }
    /** Pastel tints for icon tiles. */
    val TINT_GREEN = 0xffddf4d3.toInt(); val TINT_BLUE = 0xffe3edf9.toInt(); val TINT_PURPLE = 0xffe8e2ff.toInt()
    val TINT_ORANGE = 0xffffe9da.toInt(); val TINT_RED = 0xffffe4e4.toInt(); val TINT_YELLOW = 0xfffff3c4.toInt(); val TINT_GREY = 0xffefedf5.toInt()
    val RED = 0xffe5484d.toInt(); val YELLOW = 0xffffb020.toInt(); val GOOD = 0xff2f6b22.toInt(); val BAD = 0xffb02e33.toInt()
    /** Large figure card: caption + big value, for stats grids. */
    fun stat(context: Context, caption: String, value: String, ink: Int = INK) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = card(context, 14)
        setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 7))
        addView(text(context, caption, 11f, true, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(text(context, value, 15f, color = ink).apply { typeface = heavy(context); maxLines = 1 })
    }

    /** Big K toggle: green pill track, white knob. */
    fun styleSwitch(sw: android.widget.CompoundButton) {
        val c = sw.context
        if (sw is android.widget.Switch) {
            sw.thumbTintList = null; sw.trackTintList = null
            sw.thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(WHITE); setSize(dp(c, 28), dp(c, 28)); setStroke(dp(c, 3), 0x00000000)
            }
            sw.trackDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), GradientDrawable().apply { setColor(LEAF); cornerRadius = dp(c, 17).toFloat(); setSize(dp(c, 54), dp(c, 34)); setStroke(dp(c, 3), LEAF) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0x3022204a); cornerRadius = dp(c, 17).toFloat(); setSize(dp(c, 54), dp(c, 34)); setStroke(dp(c, 3), 0x00000000) })
            }
            sw.switchPadding = dp(c, 12)
        }
    }
    /** Thick slider with a white knob ringed in ink. */
    fun styleSeek(seek: android.widget.SeekBar, color: Int = INK) {
        val c = seek.context
        seek.progressTintList = null; seek.progressBackgroundTintList = null; seek.thumbTintList = null
        val track = GradientDrawable().apply { setColor(0x2622204a); cornerRadius = dp(c, 5).toFloat() }
        val fill = android.graphics.drawable.ClipDrawable(GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 5).toFloat() }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        seek.progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
        seek.minimumHeight = dp(c, 10); if (android.os.Build.VERSION.SDK_INT >= 29) { seek.minHeight = dp(c, 10); seek.maxHeight = dp(c, 10) }
        seek.thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE); setStroke(dp(c, 4), color); setSize(dp(c, 30), dp(c, 30)) }
        seek.splitTrack = false
        seek.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
    }

    /**
     * Charts inside a scrolling sheet: the chart only keeps the finger once the drag is clearly sideways,
     * so a vertical swipe that starts on it still scrolls the sheet.
     */
    class SidewaysDrag {
        private var x0 = 0f; private var y0 = 0f; private var claimed = false
        fun track(view: View, e: android.view.MotionEvent) {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; claimed = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(e.x - x0); val dy = kotlin.math.abs(e.y - y0)
                    if (!claimed && dx > dy && dx > dp(view.context, 6)) { claimed = true; view.parent?.requestDisallowInterceptTouchEvent(true) }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    /** A legend key as distinct colour blocks (not a blurred gradient), so each colour reads on its own. */
    fun segments(c: Context, colors: IntArray, radius: Int = 4): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            clip.reset()
            clip.addRoundRect(android.graphics.RectF(b), dp(c, radius).toFloat(), dp(c, radius).toFloat(), android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            val w = b.width().toFloat() / colors.size
            colors.forEachIndexed { i, col -> paint.color = col; canvas.drawRect(b.left + i * w, b.top.toFloat(), b.left + (i + 1) * w, b.bottom.toFloat(), paint) }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Android Drawable protocol")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
