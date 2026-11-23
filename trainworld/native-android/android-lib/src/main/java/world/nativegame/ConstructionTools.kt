// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/** The Studio tool palette stays on the Android map at every window size. */
internal object ConstructionTools {
    private data class Tile(val art: Int, val face: Int)
    private val tiles = mapOf(
        "track" to Tile(R.drawable.studio_build_track, 0xffd7f2dc.toInt()),
        "station" to Tile(R.drawable.studio_build_station, 0xffdaedff.toInt()),
        "bus" to Tile(R.drawable.studio_build_bus, 0xffffeaca.toInt()),
        "ferry" to Tile(R.drawable.studio_build_ferry, 0xffcceff2.toInt()),
        "line" to Tile(R.drawable.studio_build_line, 0xffeaddf7.toInt()),
        "select" to Tile(R.drawable.studio_build_select,0xffe8eee6.toInt()),
        "delete" to Tile(R.drawable.studio_build_delete,0xffffdfd7.toInt())
    )
    fun palette(activity: Activity, tools: List<Triple<String,String,()->Unit>>): View {
        fun dp(n: Int) = StudioUi.dp(activity,n)
        return object : ViewGroup(activity) {
            private var columns = 1
            private var rowHeight = 0
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                columns = (width/dp(104)).coerceIn(1,3)
                rowHeight = 0
                val tileWidth = (width/columns-dp(8)).coerceAtLeast(1)
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    child.measure(MeasureSpec.makeMeasureSpec(tileWidth,MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
                    rowHeight = maxOf(rowHeight,child.measuredHeight)
                }
                setMeasuredDimension(width,resolveSize((rowHeight+dp(8))*((childCount+columns-1)/columns),h))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                for (i in 0 until childCount) {
                    val col = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) columns-1-i%columns else i%columns
                    val x = col*width/columns+dp(4)
                    val y = i/columns*(rowHeight+dp(8))+dp(4)
                    val child = getChildAt(i)
                    child.layout(x,y,x+child.measuredWidth,y+rowHeight)
                }
            }
        }.apply {
            for ((key, label, action) in tools) {
                val tile = tiles[key] ?: tiles.getValue("line")
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isFocusable = true
                    background = StudioDepot.touch(activity,tile.face,16,StudioDepot.LINE)
                    setPadding(dp(5),dp(8),dp(5),dp(8))
                    minimumHeight = dp(86)
                    contentDescription = label
                    addView(ImageView(activity).apply {
                        setImageResource(tile.art)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },LinearLayout.LayoutParams(dp(34),dp(34)))
                    addView(StudioUi.text(activity,label,14f,true,StudioDepot.TEAL).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    setOnClickListener { action() }
                }
                addView(cell,ViewGroup.LayoutParams(-2,-2))
            }
        }
    }
}

/** Bangun as a radial wheel over the frosted map: tools sit around a close button. */
internal object BuildWheel {
    private val colors = mapOf(
        "track" to (0xff3fb06a.toInt() to 0xff2c8450.toInt()),
        "station" to (0xff3e7bfa.toInt() to 0xff2a5bc4.toInt()),
        "line" to (0xff6c4df6.toInt() to 0xff4630b8.toInt()),
        "bus" to (0xffffb020.toInt() to 0xffc98500.toInt()),
        "ferry" to (0xff1fb5c9.toInt() to 0xff13889a.toInt()),
        "select" to (0xff8e8aa8.toInt() to 0xff66627f.toInt()),
        "delete" to (0xffe5484d.toInt() to 0xffb02e33.toInt())
    )
    private val art = mapOf(
        "track" to R.drawable.studio_build_track, "station" to R.drawable.studio_build_station,
        "bus" to R.drawable.studio_build_bus, "ferry" to R.drawable.studio_build_ferry,
        "line" to R.drawable.studio_build_line, "select" to R.drawable.studio_build_select,
        "delete" to R.drawable.studio_build_delete
    )

    fun show(activity: Activity, title: String, tools: List<Triple<String,String,()->Unit>>, hint: String? = null): android.app.Dialog {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val root = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(0xd022204a.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(132), dp(20), 0)
        }
        head.addView(StudioUi.title(activity, title, 30f, StudioUi.WHITE).apply { gravity = Gravity.CENTER })
        head.addView(StudioUi.text(activity, "Ketuk alat untuk mulai membangun", 15f, true, 0xe6ffffff.toInt()).apply { gravity = Gravity.CENTER })
        if (hint != null) head.addView(StudioUi.text(activity, hint, 14f, true, StudioUi.INK).apply {
            background = StudioUi.glass(activity, 22, StudioUi.GLASS_STRONG)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        root.addView(head, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val wheel = object : ViewGroup(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val side = minOf(MeasureSpec.getSize(w), dp(340))
                val cell = MeasureSpec.makeMeasureSpec(dp(80), MeasureSpec.EXACTLY)
                for (i in 0 until childCount) getChildAt(i).measure(cell, cell)
                setMeasuredDimension(side, side)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val cx = width / 2; val cy = height / 2
                val ring = childCount - 1
                val radius = width / 2 - dp(44)
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    val (x, y) = if (i == 0) cx to cy else {
                        val a = Math.toRadians(-90.0 + 360.0 * (i - 1) / ring)
                        (cx + radius * Math.cos(a)).toInt() to (cy + radius * Math.sin(a)).toInt()
                    }
                    child.layout(x - child.measuredWidth / 2, y - child.measuredHeight / 2, x + child.measuredWidth / 2, y + child.measuredHeight / 2)
                }
            }
        }.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x24ffffff)
                setStroke(dp(3), 0x59ffffff)
            }
        }
        wheel.addView(StudioUi.iconButton(activity, "Tutup", StudioUi.Icon.CLOSE) { dialog.dismiss() }.apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(-1) }
            setPadding(0, 0, 0, 0)
            textSize = 28f
        })
        for ((key, label, action) in tools) {
            val (fill, edge) = colors[key] ?: colors.getValue("line")
            wheel.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = label
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    StudioUi.panel(activity, edge, 26, edge), StudioUi.panel(activity, fill, 26, fill))).apply {
                    setLayerInset(1, 0, 0, 0, dp(5))
                }
                setPadding(dp(4), dp(4), dp(4), dp(9))
                addView(ImageView(activity).apply {
                    setImageResource(art[key] ?: R.drawable.studio_build_line)
                    setColorFilter(-1)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(StudioUi.text(activity, label, 12f, true, -1).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnClickListener { action() }
            })
        }
        root.addView(wheel, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(150) })
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(-1, -1)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = dp(10) }
            }
        }
        dialog.show()
        return dialog
    }
}
