// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

/** The same zoom breakpoints and body dimensions as renderer.ts and the Studio train shader. */
internal class StudioTrainPainter(private val density:Float) {
    var engine: () -> Long = { 0L }
    var mercatorPerFrame = 1f
    var time = Double.NaN
    private data class View(val handle:Long,val time:Double,val x:Float,val y:Float,val scale:Float,val w:Int,val h:Int)
    private var last: View? = null
    private var bodies = FloatArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripes = HashMap<Int,LinearGradient>()
    private val path = Path()
    private val rect = RectF()
    private var zoom = 0f
    private var detailed = false
    private var loadEpoch = Long.MIN_VALUE
    private val loads = HashMap<Long,Int>()
    private fun tripKey(profile:Int,dep:Int) = (profile.toLong() shl 32) or (dep.toLong() and 0xffffffffL)
    fun setLoads(epoch:Long,rows:org.json.JSONArray) {
        if (loadEpoch != epoch) { loads.clear(); loadEpoch=epoch }
        for (i in 0 until rows.length() step 3) loads[tripKey(rows.getInt(i),rows.getInt(i+1))] = rows.getInt(i+2)
    }
    private fun baseWidth() = density * when {
        zoom < 11 -> 3f+2.25f*(zoom-10)
        zoom < 12 -> 5.25f+.75f*(zoom-11)
        else -> 6f+(zoom-12).coerceIn(0f,3f)
    }
    val needsLoads get() = bodies.size>1 && loadEpoch != floor(time/3600).toLong()*3600
    val count get() = bodies.firstOrNull()?.toInt() ?: 0

    private fun blend(a:Int,b:Int,f:Float) = Color.rgb(
        (Color.red(a)*(1-f)+Color.red(b)*f).roundToInt(), (Color.green(a)*(1-f)+Color.green(b)*f).roundToInt(),
        (Color.blue(a)*(1-f)+Color.blue(b)*f).roundToInt())
    fun reset(keepTrainLoads:Boolean) {
        last=null
        if (!keepTrainLoads) { loads.clear(); loadEpoch=Long.MIN_VALUE }
    }
    fun update(x:Float,y:Float,scale:Float,w:Int,h:Int) {
        val handle = engine()
        if (handle == 0L || !time.isFinite() || scale <= 0f) { bodies = FloatArray(0); last = null; return }
        val view = View(handle,time,x,y,scale,w,h)
        if (last == view) return
        // MapLibre uses 512 CSS pixels across its zoom-zero world.
        zoom = log2(scale / density * 40075016.7f / (512f*mercatorPerFrame))
        // Keep a single body until a real 20 m car has room for its rim, gap and long silhouette.
        detailed = zoom >= 14 && 20*scale >= 1.65f*baseWidth()+1.5f*density
        val detail = when { w == 0 || h == 0 || zoom < 10 -> 0; detailed -> 2; else -> 1 }
        bodies = Engine.trainScene(handle,time,x,y,w/(2f*scale),h/(2f*scale),detail)
        last = view
    }
    /** The train drawn nearest a tap (screen px), within `radius` px: its trip profile, departure ×100 and load gauge (0–200, null before loads). */
    fun trainAt(px:Float,py:Float,scale:Float,w:Int,h:Int,radius:Float):Triple<Int,Int,Int?>? {
        var best=-1; var bestD=radius
        var i=1
        while (i+14<bodies.size) {
            val d=hypot(w*.5f+bodies[i+4]*scale-px,h*.5f-bodies[i+5]*scale-py)
            if (d<=bestD) { bestD=d; best=i }
            i+=15
        }
        if (best<0) return null
        val profile=bodies[best+11].toInt(); val dep=bodies[best+12].toInt()
        val useLoads = loadEpoch == floor(time/3600).toLong()*3600
        return Triple(profile,dep,if (useLoads) loads[tripKey(profile,dep)] else null)
    }
    fun loadOf(profile:Int,dep:Int):Int? = if (loadEpoch == floor(time/3600).toLong()*3600) loads[tripKey(profile,dep)] else null
    fun draw(canvas:Canvas,scale:Float,w:Int,h:Int,display:StudioGameSettings.Display) {
        if (bodies.size < 16) return
        val detail = detailed
        val base = baseWidth()
        val useLoads = loadEpoch == floor(time/3600).toLong()*3600
        var i = 1
        while (i + 14 < bodies.size) {
            val flags = bodies[i+1].toInt()
            val kind = flags and 3
            if (!display.buses && kind >= 2) { i += 15; continue }
            val mPx = scale*bodies[i+8]
            val ax = bodies[i+2]*scale; val ay = -bodies[i+3]*scale
            val bx = bodies[i+6]*scale; val by = -bodies[i+7]*scale
            val dx=bx-ax; val dy=by-ay; val chord=hypot(dx,dy)
            if (chord < .001f) { i += 15; continue }
            val lane = ((4*mPx/density-7)/3).coerceIn(0f,1f) * abs(bodies[i+9])
            val offset = bodies[i+9]*lane*2*mPx
            val physicalWidth = when(kind) { 1 -> 2.4f; 2 -> 2.5f; 3 -> 8f; else -> 3f }
            val widthRatio = when(kind) { 1 -> .885f; 2 -> .77f; 3 -> 1.3f; else -> 1f }
            val physical = physicalWidth*mPx*(if (kind == 2) 1.2f else 1f)
            val width = max(base*widthRatio,physical)*(1f-lane)+physical*lane
            val physicalLength = bodies[i+10]*mPx
            val length = if (detail) max((if(kind==2) 1.82f else 2f)*density,
                physicalLength*(if (kind==2) 1.2f else 1f) - if(kind==1) 0f else max(1.5f*density,mPx))
                else max(physicalLength,base*2.2f/.75f*(if(kind==2) .77f*.65f else if(kind==1) .75f else 1f))
            val cx = w*.5f+bodies[i+4]*scale - dy/chord*offset
            val cy = h*.5f-bodies[i+5]*scale + dx/chord*offset
            val rgb = bodies[i].toInt()
            val dark = Color.rgb((Color.red(rgb)*.48f).roundToInt(),(Color.green(rgb)*.48f).roundToInt(),
                (Color.blue(rgb)*.48f).roundToInt())
            val fill = if (useLoads) loads[tripKey(bodies[i+11].toInt(),bodies[i+12].toInt())] else null
            val car = bodies[i+13].toInt()
            val cars = bodies[i+14].toInt()
            val empty = blend(rgb or Color.BLACK,Color.WHITE,.18f)
            val red = Color.rgb(210,60,60)
            val rimColor = if(display.fullMarks && fill==200) red else Color.WHITE
            // A shader paints the current section of the same physical gauge across all cars.
            val coreShader = if (display.capacity && fill==null) stripes.getOrPut(empty) {
                val step = 2*density
                LinearGradient(0f,0f,step,step,intArrayOf(empty,empty,Color.WHITE,Color.WHITE),
                    floatArrayOf(0f,.499f,.5f,1f),Shader.TileMode.REPEAT)
            } else null
            val fraction = (if (fill == null) 0f else if(fill<=100) fill/100f else (fill-100)/100f)*cars-car
            val loaded = if(fill!=null && fill>100) blend(dark,red,.65f) else dark
            val rest = if(fill!=null && fill>100) dark else empty
            val core = if (!display.capacity) rgb or Color.BLACK else if(fraction>=1) loaded else rest
            val divided = display.capacity && fill != null && fraction>0 && fraction<1
            canvas.save()
            canvas.translate(cx,cy)
            canvas.rotate(Math.toDegrees(atan2(dy,dx).toDouble()).toFloat())
            val rim = min(1.7f,width*.24f)
            val rounded = if(detail || kind==3) min(2*density,width*.22f) else width/2
            if (!detail && kind < 2) {
                // The overview's one continuous body bends along the chord through its midpoint.
                val sag = ((ax+bx)*.5f-bodies[i+4]*scale)*(-dy/chord)+((ay+by)*.5f+bodies[i+5]*scale)*(dx/chord)
                val span = max(0f,length-width)
                path.rewind(); path.moveTo(-span/2,sag)
                path.quadTo(0f,-sag,span/2,sag)
                paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
                paint.strokeWidth=width; paint.color=rimColor; canvas.drawPath(path,paint)
                paint.strokeWidth=max(.5f,width-2*rim); paint.color=core; paint.shader=coreShader; canvas.drawPath(path,paint)
                paint.shader=null
                if(divided) {
                    canvas.save(); canvas.clipRect(-2*length,-2*width,(fraction-.5f)*length,2*width)
                    paint.color=loaded; canvas.drawPath(path,paint); canvas.restore()
                }
                paint.style=Paint.Style.FILL
            } else {
                fun shape(inset:Float,color:Int) {
                    val a=max(0f,length/2-inset); val b=max(0f,width/2-inset)
                    paint.color=color
                    if (kind==3) {
                        val bow=a-min(length*.66f,width*1.6f)
                        path.rewind(); path.moveTo(-a,-b); path.lineTo(bow,-b); path.lineTo(a,0f)
                        path.lineTo(bow,b); path.lineTo(-a,b); path.close(); canvas.drawPath(path,paint)
                    } else {
                        rect.set(-a,-b,a,b)
                        val radius=(rounded-inset).coerceAtLeast(0f)
                        canvas.drawRoundRect(rect,radius,radius,paint)
                    }
                }
                shape(0f,rimColor); paint.shader=coreShader; shape(rim,core); paint.shader=null
                if(divided) {
                    canvas.save(); canvas.clipRect(-length,-width,(fraction-.5f)*length,width)
                    shape(rim,loaded); canvas.restore()
                }
                if(detail && flags and 4 != 0 && length>18*density) {
                    paint.color=Color.rgb((Color.red(if(fill==null) dark else core)*.55f).toInt(),(Color.green(if(fill==null) dark else core)*.55f).toInt(),(Color.blue(if(fill==null) dark else core)*.55f).toInt())
                    canvas.drawRect(.30f*length,-.30f*width,.5f*length-rim,.30f*width,paint)
                }
            }
            canvas.restore()
            i += 15
        }
    }
}
