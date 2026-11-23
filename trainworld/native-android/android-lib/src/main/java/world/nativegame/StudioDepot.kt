// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Artwork and ticket cards shared by the Android library and the departure form. */
internal object StudioDepot {
    // Kaca Diorama palette (see StudioUi): cards sit on frosted glass.
    val PAPER = 0xfff8f6ff.toInt()
    val TEAL = 0xff22204a.toInt()
    val SECONDARY = 0xff5a5878.toInt()
    val CORAL = 0xffff7a2f.toInt()
    val LINE = 0xffe4e0ee.toInt()
    val MINT = 0xffe3f5da.toInt()
    private var engine: Bitmap? = null
    private fun dp(c: Context, value: Int) = StudioUi.dp(c, value)

    fun symbol(c: Context, icon: StudioUi.Icon, size: Float, color: Int) =
        TextView(c).apply {
            text = String(Character.toChars(icon.code))
            textSize = size
            typeface = android.graphics.Typeface.createFromAsset(c.assets, "world-native/fonts/StudioMaterialIcons.otf")
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun shape(c: Context, color: Int, radius: Int, edge: Int = color) =
        StudioUi.panel(c, color, radius, edge)

    fun touch(c: Context, color: Int, radius: Int, edge: Int = color) = RippleDrawable(
        ColorStateList.valueOf(0x2524554a), shape(c, color, radius, edge), null
    )

    fun shelf(c: Context, title: String, trailing: View? = null) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(StudioUi.text(c,title,21f,true,TEAL), LinearLayout.LayoutParams(0,-2,1f))
        trailing?.let { addView(it) }
    }

    fun tools(c: Context, icon: StudioUi.Icon, label: String, click: () -> Unit) =
        FrameLayout(c).apply {
            background = touch(c, 0xffeeefe6.toInt(), 16)
            contentDescription = label
            isFocusable = true
            addView(symbol(c, icon, 22f, TEAL), FrameLayout.LayoutParams(-1,-1))
            setOnClickListener { click() }
        }

    fun ticket(c: Context, title: String, detail: String, icon: StudioUi.Icon, fill: Int = MINT, click: () -> Unit) =
        LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = touch(c, 0xffffffff.toInt(), 18, LINE)
            setPadding(dp(c,12),dp(c,11),dp(c,12),dp(c,11))
            isFocusable = true
            contentDescription = "$title. $detail"
            val badge = symbol(c,icon,23f,TEAL).apply { background = shape(c,fill,13) }
            addView(badge, LinearLayout.LayoutParams(dp(c,43),dp(c,43)))
            val words = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(c,title,17f,true,TEAL))
                addView(StudioUi.text(c,detail,13f,false,SECONDARY))
            }
            addView(words, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(c,12); marginEnd = dp(c,6) })
            addView(StudioUi.text(c,"›",25f,true,SECONDARY).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            setOnClickListener { click() }
        }

    /** The library uses the actual CC206 sprite shipped on 168Railway's fleet page. */
    fun scene(c: Context, title: String, caption: String, compact: Boolean = false) = FrameLayout(c).apply {
        background = shape(c, MINT, if (compact) 16 else 22)
        clipToOutline = true
        addView(Landscape(c), FrameLayout.LayoutParams(-1,-1))
        val copy = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(StudioUi.text(c,caption,13f,true,TEAL))
            addView(StudioUi.text(c,title,if (compact) 24f else 37f,true,TEAL).apply {
                includeFontPadding = false
                maxLines = if (compact) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        addView(copy, FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.START).apply {
            marginStart = dp(c,if(compact) 14 else 20)
            marginEnd = dp(c,if(compact) 82 else 30)
            topMargin = dp(c,if(compact) 9 else 15)
        })
    }

    private class Landscape(c: Context): View(c) {
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val hills = Path()
        private val destination = RectF()
        private val locomotive = engine ?: context.assets.open("world-native/depot/cc206.webp").use {
            BitmapFactory.decodeStream(it).also { sprite -> engine = sprite }
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(0xffcfebdf.toInt())
            brush.color = 0xffffd890.toInt()
            canvas.drawCircle(w*.83f,h*.30f,h*.16f,brush)
            hills.reset()
            hills.moveTo(0f,h*.78f)
            hills.cubicTo(w*.16f,h*.76f,w*.32f,h*.34f,w*.53f,h*.64f)
            hills.cubicTo(w*.64f,h*.82f,w*.78f,h*.25f,w,h*.55f)
            hills.lineTo(w,h); hills.lineTo(0f,h); hills.close()
            brush.color = 0xff9bcab1.toInt()
            canvas.drawPath(hills,brush)
            hills.reset()
            hills.moveTo(0f,h*.90f)
            hills.cubicTo(w*.27f,h*.59f,w*.42f,h*.96f,w*.69f,h*.75f)
            hills.cubicTo(w*.82f,h*.62f,w*.90f,h*.71f,w,h*.65f)
            hills.lineTo(w,h); hills.lineTo(0f,h); hills.close()
            brush.color = 0xff70a98c.toInt()
            canvas.drawPath(hills,brush)
            val rail = h*.89f
            brush.color = 0xff547366.toInt()
            canvas.drawRect(0f,rail,w,rail+h*.036f,brush)
            val spacing=w*.17f
            for(i in 0..6) canvas.drawRect(spacing*i,rail,spacing*i+w*.014f,h,brush)
            val sprite = locomotive ?: return
            val engineWidth = minOf(w*.49f,h*1.55f)
            val engineHeight = engineWidth * sprite.height / sprite.width
            val right = w*.96f
            destination.set(right-engineWidth,rail-engineHeight,right,rail)
            brush.color = 0xffffffff.toInt()
            canvas.drawBitmap(sprite,null,destination,brush)
        }
    }
}
