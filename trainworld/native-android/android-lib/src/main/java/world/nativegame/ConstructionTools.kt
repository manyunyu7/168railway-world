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
