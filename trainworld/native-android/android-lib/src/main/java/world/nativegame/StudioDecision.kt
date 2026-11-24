// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

/** A preview reads draft values only. The calling screen owns confirmation and persistence. */
internal class StudioDecision(context: Context, title: String, detail: String? = null) : LinearLayout(context) {
    private fun dp(n: Int) = StudioUi.dp(context,n)

    init {
        orientation = VERTICAL
        background = StudioUi.card(context, 14)
        setPadding(dp(10),dp(8),dp(10),dp(8))
        addView(StudioUi.text(context,title,15f,color = StudioUi.INK).apply { typeface = StudioUi.heavy(context) })
        detail?.let { addView(StudioUi.text(context,it,13f,true,StudioUi.MUTED).apply { setPadding(0,dp(2),0,dp(4)) }) }
    }

    fun fact(label: String, value: String) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.pill(context, 0x0d22204a, 14)
            setPadding(dp(10),dp(6),dp(10),dp(6))
            addView(StudioUi.text(context,label,13f,true,StudioUi.MUTED), LayoutParams(0,-2,1f))
            addView(StudioUi.text(context,value,14f,color = StudioUi.INK).apply {
                typeface = StudioUi.heavy(context)
                gravity = Gravity.END
            }, LayoutParams(0,-2,1.15f))
        }
        addView(row, LayoutParams(-1,-2).apply { topMargin = dp(6) })
    }

    fun bar(label: String, value: String, fraction: Double, color: Int) {
        fact(label,value)
        val track = LinearLayout(context).apply {
            background = StudioUi.pill(context,0x1f22204a,4)
            weightSum = 1f
            val part = fraction.toFloat().coerceIn(0f,1f)
            if (part > 0) addView(View(context).apply { background = StudioUi.pill(context,color,4) },
                LayoutParams(0,-1,part))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(track,LayoutParams(-1,dp(8)).apply { topMargin=dp(5); bottomMargin=dp(4) })
    }

    companion object {
        fun draft(c: Context, stops: List<PointF>, bends: List<Pair<Int,PointF>>, title: String = "Rancangan jalur", detail: String = "${stops.size} titik · ${bends.size} titik lengkung", alignment: List<PointF>? = null): StudioDecision {
            val card = StudioDecision(c,title,detail)
            val pathPoints = alignment ?: stops.flatMapIndexed { i, stop -> listOf(stop) + bends.filter { it.first==i }.map { it.second } }
            if (pathPoints.isEmpty()) return card
            val inset = StudioUi.dp(c,16).toFloat()
            val diagram = object : View(c) {
                private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND }
                override fun onDraw(canvas: Canvas) {
                    val left=pathPoints.minOf { it.x }; val right=pathPoints.maxOf { it.x }
                    val bottom=pathPoints.minOf { it.y }; val top=pathPoints.maxOf { it.y }
                    val scale=min((width-2*inset)/(right-left).coerceAtLeast(1f),(height-2*inset)/(top-bottom).coerceAtLeast(1f))
                    fun x(pt: PointF)=width/2f+(pt.x-(left+right)/2f)*scale
                    fun y(pt: PointF)=height/2f-(pt.y-(bottom+top)/2f)*scale
                    p.color=StudioUi.BLUE; p.strokeWidth=StudioUi.dp(c,5).toFloat()
                    pathPoints.zipWithNext().forEach { (a,b) -> canvas.drawLine(x(a),y(a),x(b),y(b),p) }
                    for (stop in stops) {
                        p.color=StudioUi.INK
                        canvas.drawCircle(x(stop),y(stop),StudioUi.dp(c,7).toFloat(),p)
                        p.color=StudioUi.WHITE
                        canvas.drawCircle(x(stop),y(stop),StudioUi.dp(c,3).toFloat(),p)
                    }
                }
            }
            diagram.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            diagram.background=StudioUi.pill(c,0xffcfebc0.toInt(),16)
            card.addView(diagram, LayoutParams(-1,StudioUi.dp(c,92)).apply { topMargin=StudioUi.dp(c,8) })
            return card
        }
    }
}
