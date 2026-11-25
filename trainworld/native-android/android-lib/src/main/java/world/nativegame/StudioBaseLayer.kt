// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.*

/** OpenFreeMap/Positron vector features, from the same release used by the route editor. Actual
 * city terrain/water from the installed Studio pack remains beneath transparent vector tiles. */
internal class StudioBaseLayer(context:Context, private val invalidate:()->Unit) {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val disk=Executors.newSingleThreadExecutor()
    private val net=Executors.newFixedThreadPool(3)
    private var closed=false
    private val assets=app.assets.list("world-native/basemap").orEmpty().toHashSet()
    private data class Entry(val tile:StudioVectorTile,val bytes:Int)
    private val cache=object:LruCache<MapTile,Entry>(20*1024*1024){
        override fun sizeOf(key:MapTile,value:Entry)=value.bytes.coerceAtLeast(2048)
    }
    private val busy=HashSet<MapTile>()
    private val tried=HashMap<MapTile,Long>()
    @Volatile private var wanted:Set<MapTile> = emptySet()
    private val density=app.resources.displayMetrics.density
    private val workPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val quad=floatArrayOf(0f,0f,4096f,0f,4096f,4096f,0f,4096f)
    private data class ScreenTile(val matrix:Matrix,val quad:FloatArray,val tilePx:Float,val clip:Path)
    private data class Camera(val handle:Long,val x:Float,val y:Float,val scale:Float,val w:Int,val h:Int,val merc:Float)
    var status:String?=null
        private set
    private var networkFailedUntil=0L
    private val knownLocal=HashSet<String>()
    private val connectivity=app.getSystemService(android.net.ConnectivityManager::class.java)
    @Volatile private var connected=runCatching {connectivity.activeNetwork!=null}.getOrDefault(true)
    private val callback=object:android.net.ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:android.net.Network) {main.post {
            if(!closed){connected=true;tried.clear();networkFailedUntil=0L;invalidate()}
        }}
        override fun onLost(network:android.net.Network) {main.post {
            if(!closed){connected=runCatching {connectivity.activeNetwork!=null}.getOrDefault(false);invalidate()}
        }}
    }
    init {
        runCatching {connectivity.registerDefaultNetworkCallback(callback)}
        disk.execute {
            val names=StudioMapTiles.localNames(app)
            main.post {if(!closed){knownLocal.addAll(names);invalidate()}}
        }
    }
    private var previous:Camera?=null
    private var viewTiles=emptyList<MapTile>()
    private var zoom=0f
    private val frames=HashMap<MapTile,ScreenTile>()
    internal data class Wording(val text:String,val x:Float,val y:Float,val angle:Float,val major:Boolean)
    private val roadNames=ArrayList<Wording>()
    private fun request(t:MapTile,remote:Boolean) {
        if(closed || cache.get(t)!=null || t in busy)return
        val now=SystemClock.elapsedRealtime()
        if(now<(tried[t]?:0L))return
        busy.add(t)
        fun complete(bytes:ByteArray?, attemptedDownload:Boolean=false) {
            val decoded=bytes?.let { b->runCatching { Entry(StudioVectorTile.decode(b),b.size) }.getOrNull() }
            main.post {
                if(!closed) {
                    busy.remove(t)
                    if(decoded!=null) { cache.put(t,decoded);knownLocal.add(t.name);tried.remove(t);invalidate() }
                    else {
                        tried[t]=SystemClock.elapsedRealtime()+if(attemptedDownload)30_000L else 2500L
                        if(attemptedDownload && t in wanted){networkFailedUntil=SystemClock.elapsedRealtime()+30_000L;invalidate()
                            if(connected)main.postDelayed({if(!closed && connected && t in wanted)invalidate()},31_000L)}
                    }
                }
            }
        }
        disk.execute {
            val local=runCatching { StudioMapTiles.local(app,t) }.getOrNull()
            if(local!=null || !remote || t !in wanted || !connected) complete(local)
            else if(!net.isShutdown) runCatching { net.execute {
                if(t in wanted && connected)complete(runCatching {StudioMapTiles.fetch(app,t)}.getOrNull(),true) else complete(null)
            } }
        }
    }
    private fun tiles(camera:Camera) {
        previous=camera;frames.clear()
        zoom=log2(camera.scale/density*40075016.7f/(512f*camera.merc))
        val z=zoom.toInt().coerceIn(0,14)
        val n=1 shl z
        fun column(lon:Double)=(lon+180)/360*n
        fun row(lat:Double)=(1-asinh(tan(Math.toRadians(lat.coerceIn(-85.05112878,85.05112878))))/Math.PI)/2*n
        val hx=camera.w/(2*camera.scale);val hy=camera.h/(2*camera.scale)
        val corners=listOf(-hx to -hy,hx to -hy,hx to hy,-hx to hy).map { (dx,dy) ->
            CitySurface.lonLat(camera.handle,camera.x+dx,camera.y+dy) }
        val centre=CitySurface.lonLat(camera.handle,camera.x,camera.y)
        val cx=column(centre[0]);val cy=row(centre[1])
        // Unwrap an antimeridian viewport around its centre.
        val xs=corners.map{var x=column(it[0]);if(x-cx>n/2.0)x-=n else if(cx-x>n/2.0)x+=n; x}
        val ys=corners.map{row(it[1])}
        val result=ArrayList<MapTile>()
        for(x in floor(xs.min()).toInt()..floor(xs.max()).toInt()) {
            for(y in floor(ys.min()).toInt().coerceIn(0,n-1)..floor(ys.max()).toInt().coerceIn(0,n-1)) {
                result.add(MapTile(z,Math.floorMod(x,n),y))
            }
        }
        viewTiles=result.distinct().sortedBy{hypot(it.x+.5-cx,it.y+.5-cy)}.take(64)
        wanted=viewTiles.toSet()
        tried.entries.removeAll { it.value<SystemClock.elapsedRealtime() }
    }
    private fun frame(t:MapTile,c:Camera):ScreenTile = frames.getOrPut(t) {
        val n=(1 shl t.z).toDouble()
        fun lng(x:Int)=x/n*360.0-180.0
        fun lat(y:Int)=Math.toDegrees(atan(sinh(Math.PI*(1-2*y/n))))
        val left=lng(t.x);val right=lng(t.x+1).let { if(it>=180.0)179.999999999 else it }
        val top=lat(t.y);val bottom=lat(t.y+1)
        val p=CitySurface.project(c.handle,doubleArrayOf(left,top,right,top,right,bottom,left,bottom))
        for(i in p.indices step 2){p[i]=(p[i]-c.x)*c.scale+c.w*.5f;p[i+1]=(c.y-p[i+1])*c.scale+c.h*.5f}
        val m=Matrix();m.setPolyToPoly(quad,0,p,0,4)
        val clip=Path().apply {moveTo(p[0],p[1]);lineTo(p[2],p[3]);lineTo(p[4],p[5]);lineTo(p[6],p[7]);close()}
        ScreenTile(m,p,hypot(p[2]-p[0],p[3]-p[1]).coerceAtLeast(1f),clip)
    }
    private fun width(stops:FloatArray,zoom:Float):Float {
        var i=2
        while(i<stops.size && zoom>stops[i])i+=2
        if(i==stops.size)return stops.last()
        val span=stops[i]-stops[i-2];val t=(zoom-stops[i-2]).coerceIn(0f,span)
        val progress=(1.4.pow(t.toDouble())-1)/(1.4.pow(span.toDouble())-1)
        return stops[i-1]+((stops[i+1]-stops[i-1])*progress).toFloat()
    }
    /** One tile to paint: its features, where they land (src) and, for a stand-in ancestor, the clip of the tile it covers. */
    class Piece internal constructor(internal val tile:StudioVectorTile,internal val clip:Path,internal val matrix:Matrix,internal val tilePx:Float)
    /** Everything [render] needs, taken on the UI thread (the tile frames read the world's projection). */
    class Job internal constructor(val w:Int,val h:Int,internal val zoom:Float,internal val pieces:List<Piece>,internal val labels:List<Wording>)

    /**
     * The UI-thread half of drawing the base map at a camera: picks and requests the tiles, works out
     * their screen frames and the road names. The painting itself ([render]) touches nothing of the
     * world or of this object's caches, so it runs on a worker while the map keeps moving.
     */
    fun prepare(handle:Long,x:Float,y:Float,scale:Float,w:Int,h:Int,merc:Float,mapNames:Boolean):Job? {
        if(handle==0L||closed||w<1||h<1||scale<=0)return null
        val c=Camera(handle,x,y,scale,w,h,merc)
        if(c!=previous)tiles(c)
        roadNames.clear()
        val pieces=ArrayList<Piece>()
        var coverage=0
        var baseCoverage=0
        for(t in viewTiles) {
            var ancestor=t.parent()
            while(ancestor!=null) {
                if(cache.get(ancestor)!=null)break
                if(ancestor.name in assets || "${ancestor.name}.gz" in assets || ancestor.name in knownLocal)request(ancestor,false)
                ancestor=ancestor.parent()
            }
            request(t,true)
            val selected=if(cache.get(t)!=null)t else ancestor?:continue
            val entry=cache.get(selected)?:continue
            baseCoverage++; if(t==selected)coverage++
            val child=frame(t,c);val src=frame(selected,c)
            pieces.add(Piece(entry.tile,child.clip,src.matrix,src.tilePx))
            if(mapNames && zoom>=14 && selected==t){
                for(label in entry.tile.labels) {
                    if(!label.major && zoom<16)continue
                    val xy=floatArrayOf(label.x,label.y);src.matrix.mapPoints(xy)
                    if(xy[0] in 0f..w.toFloat() && xy[1] in 0f..h.toFloat())
                        roadNames.add(Wording(label.name,xy[0],xy[1],label.angle,label.major))
                }
            }
        }
        status=if(coverage==viewTiles.size || viewTiles.isEmpty()) null else {
            when {
                !connected -> if(baseCoverage>0) "Detail peta belum tersimpan" else "Peta jalan belum tersedia luring"
                SystemClock.elapsedRealtime()<networkFailedUntil -> "Sebagian detail peta belum tersedia"
                baseCoverage==0 -> "Memuat peta jalan…"
                else -> null
            }
        }
        return Job(w,h,zoom,pieces,ArrayList(roadNames))
    }

    /** Paints a [prepare]d job; safe on any one thread at a time (its own paint, nothing shared is written). */
    fun render(job:Job,canvas:Canvas) {
        val paint=workPaint
        val zoom=job.zoom
        for(piece in job.pieces) {
            canvas.save(); canvas.clipPath(piece.clip);canvas.concat(piece.matrix)
            for((style,path) in piece.tile.paths) {
                if(style==StudioVectorTile.Style.BUILDING && zoom<13 || style==StudioVectorTile.Style.FOOT && zoom<14)continue
                val props=styles.getValue(style)
                if(props.second!=null) {
                    paint.style=Paint.Style.STROKE
                    paint.strokeWidth=width(props.second!!,zoom)*density*4096/piece.tilePx
                } else paint.style=Paint.Style.FILL
                paint.color=props.first;canvas.drawPath(path,paint)
            }
            canvas.restore()
        }
        paint.textSize=10*density;paint.textAlign=Paint.Align.CENTER
        val names=HashSet<String>();val boxes=ArrayList<RectF>()
        for(label in job.labels.sortedByDescending{it.major}) {
            if(label.text in names || boxes.size>=45)continue
            val half=paint.measureText(label.text)*.5f
            val rad=Math.toRadians(label.angle.toDouble())
            val dx=abs(cos(rad)).toFloat()*half+10*density;val dy=abs(sin(rad)).toFloat()*half+8*density
            val box=RectF(label.x-dx,label.y-dy,label.x+dx,label.y+dy)
            if(boxes.any {RectF.intersects(it,box)})continue
            names.add(label.text);boxes.add(box)
            canvas.save();canvas.rotate(label.angle,label.x,label.y)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=0xfff9f7ef.toInt();canvas.drawText(label.text,label.x,label.y,paint)
            paint.style=Paint.Style.FILL;paint.color=0xff62675e.toInt();canvas.drawText(label.text,label.x,label.y,paint)
            canvas.restore()
        }
        paint.textAlign=Paint.Align.LEFT
    }
    fun close(){closed=true;runCatching {connectivity.unregisterNetworkCallback(callback)};wanted=emptySet();disk.shutdownNow();net.shutdownNow();cache.evictAll();frames.clear()}
    companion object {
        private val motorway=floatArrayOf(4f,1f,8f,1.6f,12f,3f,16f,8f,20f,28f)
        private val mainRoad=floatArrayOf(8f,.6f,10f,1f,12f,2f,16f,6f,20f,20f)
        private val minor=floatArrayOf(12f,.7f,14f,1.4f,16f,3.5f,20f,14f)
        private val styles=mapOf(
            StudioVectorTile.Style.GREEN to (0xffdce8d2.toInt() to null),
            StudioVectorTile.Style.RESIDENTIAL to (0xffeeeae0.toInt() to null),
            StudioVectorTile.Style.INDUSTRY to (0xffe9e5dc.toInt() to null),
            StudioVectorTile.Style.WATER to (0xffb7d7db.toInt() to null),
            StudioVectorTile.Style.RIVER to (0xffafcfd4.toInt() to floatArrayOf(7f,.7f,14f,2f,19f,6f)),
            StudioVectorTile.Style.BUILDING to (0xffddd9cf.toInt() to null),
            StudioVectorTile.Style.FOOT to (0xffd7d4c8.toInt() to floatArrayOf(14f,.6f,19f,2f)),
            StudioVectorTile.Style.ROAD to (0xffc7c7b9.toInt() to minor),
            StudioVectorTile.Style.MAIN to (0xffc0bcab.toInt() to mainRoad),
            StudioVectorTile.Style.MOTORWAY to (0xffb9b09a.toInt() to motorway),
            StudioVectorTile.Style.RAIL to (0xffafb3a9.toInt() to floatArrayOf(8f,.5f,14f,1f,19f,3f))
        )
    }
}
