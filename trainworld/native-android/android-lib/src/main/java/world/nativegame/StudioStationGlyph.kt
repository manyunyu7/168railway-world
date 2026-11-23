package world.nativegame

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Geometry in frame units relative to the station node, as sampled along the actual track. */
internal class StudioStationGlyph private constructor(
    private val xy: FloatArray, private val physicalHalf: Float, private val upgrades: Int,
    private val tracks: Int, private val end: Boolean
) {
    val midX = xy[12]
    val midY = xy[13]
    private val normals = FloatArray(xy.size)
    private val body = Path()
    private val track = Path()
    private var cachedHalf = -1f
    private var length = 0f
    val reach: Float

    init {
        var extent = 0f
        for (i in xy.indices step 2) {
            val a = max(0,i-2)
            val b = min(xy.size-2,i+2)
            val dx = xy[b]-xy[a]
            val dy = xy[b+1]-xy[a+1]
            val d = hypot(dx,dy).coerceAtLeast(.001f)
            normals[i] = -dy/d
            normals[i+1] = dx/d
            if (i == 0) track.moveTo(xy[i],-xy[i+1]) else {
                track.lineTo(xy[i],-xy[i+1])
                length += hypot(xy[i]-xy[a],xy[i+1]-xy[a+1])
            }
            extent = max(extent,max(abs(xy[i]),abs(xy[i+1])))
        }
        reach = extent+physicalHalf*5
    }

    private fun half(scale:Float,density:Float,mercator:Float):Float {
        // A constant minimum on screen at z14–15, then scale the physical footprint with the map.
        val floorDp = 3.2f
        return max(physicalHalf, max(floorDp*density/scale, floorDp*2.38866f/mercator))
    }

    private fun prepareBody(h:Float) {
        if (h == cachedHalf) return
        cachedHalf = h
        body.rewind()
        for (i in xy.indices step 2) {
            val x = xy[i]+normals[i]*h
            val y = -(xy[i+1]+normals[i+1]*h)
            if (i == 0) body.moveTo(x,y) else body.lineTo(x,y)
        }
        for (i in xy.size-2 downTo 0 step 2) {
            body.lineTo(xy[i]-normals[i]*h,-(xy[i+1]-normals[i+1]*h))
        }
        body.close()
        if (upgrades and 4 != 0) {
            // Waiting room annex joins the platform at its midpoint, with one shared outline.
            val side = if (normals[13] >= 0) 1f else -1f
            val nx = side*normals[12]
            val ny = side*normals[13]
            val dx = normals[13]
            val dy = -normals[12]
            val l = (.175f*length).coerceIn(1.2f*h,2.5f*h)
            val annex = Path()
            fun corner(t:Float,w:Float,first:Boolean=false) {
                val x = midX+dx*t+nx*w
                val y = -(midY+dy*t+ny*w)
                if(first) annex.moveTo(x,y) else annex.lineTo(x,y)
            }
            corner(-l,0f,true); corner(l,0f); corner(l,2.5f*h); corner(-l,2.5f*h)
            annex.close()
            body.op(annex,Path.Op.UNION)
        }
    }

    fun draw(canvas:Canvas,paint:Paint,x:Float,y:Float,scale:Float,density:Float,mercator:Float,selected:Boolean) {
        val h = half(scale,density,mercator)
        prepareBody(h)
        canvas.save()
        canvas.translate(x,y)
        canvas.scale(scale,scale)
        if (selected) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xff00aa92.toInt()
            paint.strokeWidth = 4*density/scale
            canvas.drawPath(body,paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xfffffcf0.toInt()
        canvas.drawPath(body,paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.BUTT
        val rim = min(h*.5f,1.25f*density/scale)
        paint.strokeWidth = rim
        paint.color = 0xff374b50.toInt()
        canvas.drawPath(body,paint)
        if (tracks >= 2 && h*scale >= 6*density) {
            paint.color = 0xffacb8b3.toInt()
            paint.strokeWidth = min(1.5f*density/scale,h*.2f)
            canvas.drawPath(track,paint)
        }
        if (end && upgrades and 1 != 0) {
            paint.color = 0xff374b50.toInt()
            paint.strokeWidth = 2.5f*rim
            val ear = h+1.5f*rim
            canvas.drawLine(xy[0]-normals[0]*ear,-xy[1]+normals[1]*ear,
                xy[0]+normals[0]*ear,-xy[1]-normals[1]*ear,paint)
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    /** Distance to the visible platform footprint. The touch allowance is applied by the caller. */
    fun distance(x:Float,y:Float,scale:Float,density:Float,mercator:Float):Float {
        val h = half(scale,density,mercator)
        var best = Float.POSITIVE_INFINITY
        for (i in 2 until xy.size step 2) {
            val ax = xy[i-2]; val ay = xy[i-1]
            val dx = xy[i]-ax; val dy = xy[i+1]-ay
            val len2 = dx*dx+dy*dy
            val t = if (len2 > 0) (((x-ax)*dx+(y-ay)*dy)/len2).coerceIn(0f,1f) else 0f
            best = min(best,hypot(x-ax-t*dx,y-ay-t*dy))
        }
        var edge = (best-h).coerceAtLeast(0f)
        if (upgrades and 4 != 0) {
            val side = if (normals[13] >= 0) 1f else -1f
            val dx = x-midX; val dy = y-midY
            val across = (dx*normals[12]+dy*normals[13])*side
            val along = abs(dx*normals[13]-dy*normals[12])
            val l = (.175f*length).coerceIn(1.2f*h,2.5f*h)
            edge = min(edge,hypot((along-l).coerceAtLeast(0f),max(-across,across-2.5f*h).coerceAtLeast(0f)))
        }
        return edge*scale
    }

    companion object {
        fun read(data:JSONObject?):StudioStationGlyph? {
            val a = data?.optJSONArray("dm") ?: return null
            if (a.length()!=26) return null
            return StudioStationGlyph(FloatArray(26){ a.getInt(it)/10f },data.getDouble("halfWidth").toFloat(),
                data.optInt("upgrades"),data.optInt("tracks"),data.optBoolean("end"))
        }
    }
}
