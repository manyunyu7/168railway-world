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
    val INK = 0xff1f2937.toInt()
    val MUTED = 0xff52606d.toInt()
    val GREEN = 0xff58cc02.toInt()
    val BLUE = 0xff1cb0f6.toInt()
    val WHITE = Color.WHITE
    val BACKGROUND = 0xfff8f9fa.toInt()
    val BORDER = 0xffdee2e6.toInt()
    enum class Tone { PRIMARY, BLUE, SURFACE }
    enum class Icon(val code: Int) {
        TRAIN(0xf024a), SAVE(0xf0126), PLUS(0xf537), BACK(0xf572), FOLDER(0xf77d),
        CLOUD(0xf650), PLAY(0xf00a0), MENU(0xf0258), PIN(0xf884), CENTER(0xf8f0),
        MINUS(0xf00f1), PAUSE(0xf0056), ROUTE(0xf0377), WALLET(0xf520), HELP(0xf7e5), UNDO(0xf0261), CLOSE(0xf647)
    }

    fun dp(c: Context, n: Int) = (c.resources.displayMetrics.density * n + .5f).toInt()
    private val fonts = mutableMapOf<String, Typeface>()
    private fun font(c: Context, file: String) = fonts.getOrPut(file) {
        Typeface.createFromAsset(c.assets, "world-native/fonts/$file")
    }
    fun face(c: Context, bold: Boolean = false) = font(c, if (bold) "Nunito-ExtraBold.ttf" else "Nunito-Regular.ttf")
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
        val fill = when (tone) { Tone.PRIMARY -> GREEN; Tone.BLUE -> BLUE; Tone.SURFACE -> WHITE }
        val edge = when (tone) { Tone.PRIMARY -> 0xff46a302.toInt(); Tone.BLUE -> 0xff1899d6.toInt(); Tone.SURFACE -> BORDER }
        fun layers(top: Int, bottom: Int, pressed: Boolean, focus: Boolean = false): Drawable {
            return LayerDrawable(arrayOf(panel(c, bottom, 16, bottom), panel(c, top, 16, if (focus) INK else bottom))).apply {
                setLayerInset(1, 0, if (pressed) dp(c, 3) else 0, 0, if (pressed) 0 else dp(c, 4))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), layers(0xffedf0f2.toInt(), BORDER, false))
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
        button.setCompoundDrawablesRelative(icon?.let { glyph(button.context, it, INK) }, null, null, null)
    }

    fun button(context: Context, label: String, tone: Tone = Tone.SURFACE, icon: Icon? = null, click: () -> Unit) =
        Button(context).apply {
            text = label
            typeface = face(context, true)
            textSize = 15f
            isAllCaps = false
            letterSpacing = 0f
            stateListAnimator = null
            backgroundTintList = null
            style(this, tone)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, INK)))
            minWidth = dp(context, 48)
            minimumWidth = minWidth
            minHeight = dp(context, 48)
            minimumHeight = minHeight
            setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 6))
            compoundDrawablePadding = dp(context, 5)
            setIcon(this, icon)
            setOnClickListener { click() }
        }

    fun iconButton(c: Context, description: String, icon: Icon, tone: Tone = Tone.SURFACE, click: () -> Unit) =
        button(c, String(Character.toChars(icon.code)), tone, click = click).apply {
            typeface = font(c, "StudioMaterialIcons.otf")
            textSize = 23f
            contentDescription = description
            setPadding(0, 0, 0, dp(c, 4))
        }

    /** A shared surface for pickers, confirmations and the larger gameplay tools. */
    fun layoutSheet(activity: Activity, dialog: Dialog, title: CharSequence?, body: View,
                    footer: View?, heightFraction: Float = .9f) {
        val root = object : LinearLayout(activity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val available = MeasureSpec.getSize(heightSpec)
                val limit = (activity.window.decorView.height * heightFraction).toInt()
                val height = if (limit > 0) min(available, limit) else available
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            background = panel(activity, StudioDepot.PAPER, 24, StudioDepot.LINE)
            clipToOutline = true
            setPadding(dp(context,18),dp(context,10),dp(context,18),dp(context,12))
        }
        root.addView(View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = panel(context, 0xffa9bdb2.toInt(), 4, 0xffa9bdb2.toInt())
        }, LinearLayout.LayoutParams(dp(activity,36),dp(activity,4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity,10)
        })
        if (!title.isNullOrEmpty()) root.addView(text(activity,title.toString(),22f,true,StudioDepot.TEAL).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(activity,8) })
        root.addView(body,LinearLayout.LayoutParams(-1,-2,1f))
        footer?.let { root.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(activity,8) }) }
        dialog.setContentView(root)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val host = activity.window.decorView
        fun resize() {
            val available = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val wide = available >= dp(activity,600)
            window.setGravity(if (wide) Gravity.CENTER else Gravity.BOTTOM)
            window.setLayout(if (wide) min(available-dp(activity,48),dp(activity,660)) else available,-2)
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

    fun sheet(activity: Activity, title: String, heightFraction: Float = .9f, pinnedAction: (() -> View)? = null, populate: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context,2),dp(context,2),dp(context,2),dp(context,5))
        }
        populate(column, dialog)
        val scroll = ScrollView(activity).apply { addView(column) }
        layoutSheet(activity,dialog,title,scroll,pinnedAction?.invoke(),heightFraction)
        dialog.show()
        return dialog
    }
}
