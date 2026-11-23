// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Real Studio city maps and scenario snapshots. Cards decode thumbnails at list resolution. */
internal object StudioMapPreview {
    private val thumbnails = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cityPath(id: String) = "world-native/zone-previews/$id.webp"
    fun scenarioPath(id: String) = "world-native/snapshots/$id.jpg"

    fun image(context: Context, path: String, description: String, thumbnail: Boolean = false): ImageView? {
        val bitmap = (if (thumbnail) thumbnails.get(path) else null) ?: try {
            context.assets.open(path).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (thumbnail) 4 else 1 })
            }?.also { if (thumbnail) thumbnails.put(path, it) }
        } catch (_: java.io.IOException) { null }
        bitmap ?: return null
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = if (thumbnail) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = !thumbnail
            background = StudioUi.panel(context, StudioUi.WHITE, 18, 0xccffffff.toInt())
            clipToOutline = true
            contentDescription = description
        }
    }

    fun card(context: Context, path: String, title: String, detail: String, selected: Boolean, click: () -> Unit) =
        LinearLayout(context).apply {
            fun dp(n: Int) = StudioUi.dp(context, n)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(8),dp(12),dp(8))
            background = KScene.choiceBackground(context, selected)
            val thumb = image(context, path, "", true) ?: StudioDepot.symbol(context,StudioUi.Icon.PIN,24f,StudioUi.INK).apply {
                background = StudioUi.panel(context, StudioUi.TINT_PURPLE, 14, StudioUi.TINT_PURPLE)
            }
            thumb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(thumb,LinearLayout.LayoutParams(dp(72),dp(54)))
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(context,title,16f,color=StudioUi.INK).apply { typeface = StudioUi.heavy(context) })
                addView(StudioUi.text(context,detail,13f,true,StudioUi.MUTED))
            }
            addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12); marginEnd=dp(4) })
            addView(KScene.check(context, selected))
            contentDescription = listOf(title,detail,if(selected) "Terpilih" else "").filter { it.isNotBlank() }.joinToString(". ")
            isFocusable = true
            isSelected = selected
            setOnClickListener { click() }
        }
}

/** Kaca Diorama scenery and cards for the screens outside a world: sky, floating islands, glass choices. */
internal object KScene {
    private fun dp(c: Context, n: Int) = StudioUi.dp(c, n)

    /** Sky gradient with clouds and a green hill at the bottom; sits behind the library and archive. */
    class Sky(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xff9fd8f7.toInt(), 0xffd7f0ff.toInt(), 0xfffff1db.toInt()), floatArrayOf(0f, .6f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.color = 0xe6ffffff.toInt()
            val d = resources.displayMetrics.density
            canvas.drawOval(w * .05f, 120 * d, w * .05f + 100 * d, 156 * d, paint)
            canvas.drawOval(w * .68f, 200 * d, w * .68f + 120 * d, 240 * d, paint)
            paint.color = 0xffbfe6a8.toInt()
            canvas.drawOval(-w * .2f, h - 150 * d, w * 1.2f, h + 120 * d, paint)
        }
    }

    /** Isometric island with two rail lines, trains and trees. */
    class Island(context: Context, private val grass: Int = 0xff9bd67b.toInt()) : View(context) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: android.graphics.Canvas) {
            // Artwork is authored on a 200×140 board and scaled to fit, centred.
            val s = minOf(width / 200f, height / 140f)
            canvas.save()
            canvas.translate((width - 200 * s) / 2, (height - 140 * s) / 2)
            canvas.scale(s, s)
            p.style = android.graphics.Paint.Style.FILL
            p.color = 0x2422204a; canvas.drawOval(26f, 106f, 174f, 130f, p)
            fun poly(color: Int, vararg xy: Float) {
                path.reset(); path.moveTo(xy[0], xy[1])
                var i = 2; while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
                path.close(); p.color = color; canvas.drawPath(path, p)
            }
            poly(grass, 22f, 70f, 100f, 34f, 178f, 70f, 100f, 106f)
            poly(0xffc9935c.toInt(), 22f, 70f, 100f, 106f, 100f, 122f, 22f, 86f)
            poly(0xffa9743f.toInt(), 178f, 70f, 100f, 106f, 100f, 122f, 178f, 86f)
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeCap = android.graphics.Paint.Cap.ROUND
            for ((a, color) in listOf(floatArrayOf(44f, 72f, 128f, 42f) to 0xffe5484d.toInt(), floatArrayOf(70f, 92f, 160f, 62f) to 0xff3e7bfa.toInt())) {
                p.strokeWidth = 6f; p.color = -1; canvas.drawLine(a[0], a[1], a[2], a[3], p)
                p.strokeWidth = 3.5f; p.color = color; canvas.drawLine(a[0], a[1], a[2], a[3], p)
            }
            for ((x, y) in listOf(84f to 56f, 118f to 72f)) {
                p.style = android.graphics.Paint.Style.FILL; p.color = -1
                canvas.drawRoundRect(x, y, x + 16, y + 12, 3f, 3f, p)
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2f; p.color = StudioUi.INK
                canvas.drawRoundRect(x, y, x + 16, y + 12, 3f, 3f, p)
            }
            p.style = android.graphics.Paint.Style.FILL; p.color = 0xff7cc26a.toInt()
            canvas.drawCircle(60f, 60f, 7f, p); canvas.drawCircle(150f, 80f, 6f, p); canvas.drawCircle(140f, 56f, 5f, p)
            canvas.restore()
        }
    }

    fun choiceBackground(c: Context, selected: Boolean): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x226c4df6),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) 0xf2ffffff.toInt() else 0xa6ffffff.toInt())
                cornerRadius = dp(c, 22).toFloat()
                setStroke(dp(c, if (selected) 3 else 1) , if (selected) StudioUi.BLUE else 0xccffffff.toInt())
            }, null)

    /** Purple check disc for the chosen option; an empty ring otherwise. */
    fun check(c: Context, selected: Boolean) = StudioUi.text(c, if (selected) "✓" else "", 14f, true, StudioUi.WHITE).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (selected) setColor(StudioUi.BLUE) else { setColor(0); setStroke(dp(c, 2), 0x4d22204a) }
        }
        layoutParams = LinearLayout.LayoutParams(dp(c, 26), dp(c, 26))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A glass choice card: icon tile, title, detail and a purple check. */
    fun choice(c: Context, icon: StudioUi.Icon, tint: Int, title: String, detail: String, selected: Boolean, click: () -> Unit) =
        LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = choiceBackground(c, selected)
            setPadding(dp(c, 12), dp(c, 11), dp(c, 14), dp(c, 11))
            minimumHeight = dp(c, 68)
            addView(StudioDepot.symbol(c, icon, 22f, StudioUi.INK).apply {
                background = StudioUi.panel(c, tint, 15, tint)
            }, LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)))
            val copy = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(c, title, 16f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(c) })
                addView(StudioUi.text(c, detail, 13f, true, StudioUi.MUTED))
            }
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 12); marginEnd = dp(c, 6) })
            addView(check(c, selected))
            isFocusable = true
            isSelected = selected
            contentDescription = "$title. $detail" + if (selected) ". Terpilih" else ""
            setOnClickListener { click() }
        }

    /** Dashed "Pulau baru" card with a purple plus. */
    fun newIsland(c: Context, label: String, click: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(c, 150)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x226c4df6),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0x4dffffff)
                cornerRadius = dp(c, 26).toFloat()
                setStroke(dp(c, 2) + 1, 0x4d22204a, dp(c, 8).toFloat(), dp(c, 6).toFloat())
            }, null)
        addView(StudioUi.text(c, "+", 28f, true, StudioUi.WHITE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(StudioUi.BLUE_EDGE) },
                android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(StudioUi.BLUE) })).apply {
                setLayerInset(1, 0, 0, 0, dp(c, 4))
            }
            setPadding(0, 0, 0, dp(c, 4))
        }, LinearLayout.LayoutParams(dp(c, 54), dp(c, 58)))
        addView(StudioUi.text(c, label, 15f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(c); setPadding(0, dp(c, 8), 0, 0) })
        isFocusable = true
        contentDescription = label
        setOnClickListener { click() }
    }

    /** Small world card for the library grid: island thumbnail, title and detail. */
    fun worldCard(c: Context, title: String, detail: String, grass: Int, click: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x226c4df6),
            StudioUi.glass(c, 26), null)
        elevation = dp(c, 2).toFloat()
        setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 12))
        addView(Island(c, grass), LinearLayout.LayoutParams(-1, dp(c, 92)))
        addView(StudioUi.text(c, title, 15f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(c); gravity = Gravity.CENTER; maxLines = 2
        })
        addView(StudioUi.text(c, detail, 12f, true, StudioUi.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 })
        isFocusable = true
        contentDescription = "$title. $detail"
        setOnClickListener { click() }
    }

    /** Round glass icon button for screen toolbars. */
    fun glassIcon(c: Context, icon: StudioUi.Icon, label: String, click: () -> Unit) =
        android.widget.FrameLayout(c).apply {
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2222204a), StudioUi.glass(c, 25), null)
            elevation = dp(c, 3).toFloat()
            contentDescription = label
            isFocusable = true
            addView(StudioDepot.symbol(c, icon, 22f, StudioUi.INK), android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { click() }
        }

    /** Progress bar in purple on a pale track. */
    fun meter(c: Context) = android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
            StudioUi.pill(c, 0x1a22204a, 8),
            android.graphics.drawable.ClipDrawable(StudioUi.pill(c, StudioUi.BLUE, 8), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        indeterminateTintList = android.content.res.ColorStateList.valueOf(StudioUi.BLUE)
    }

    /** Checklist line: green done, yellow active, grey pending. */
    fun step(c: Context, label: String): Pair<LinearLayout, (Int) -> Unit> {
        val dot = StudioUi.text(c, "", 12f, true, StudioUi.WHITE).apply { gravity = Gravity.CENTER }
        val text = StudioUi.text(c, label, 14f, true, StudioUi.INK)
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 4), 0, dp(c, 4))
            addView(dot, LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)))
            addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 10) })
        }
        fun set(state: Int) {
            dot.text = if (state == 0) "✓" else ""
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(when (state) { 0 -> StudioUi.LEAF; 1 -> 0xffffd23f.toInt(); else -> 0x1f22204a })
            }
            text.setTextColor(if (state == 2) StudioUi.MUTED else StudioUi.INK)
        }
        set(2)
        return row to ::set
    }
}
