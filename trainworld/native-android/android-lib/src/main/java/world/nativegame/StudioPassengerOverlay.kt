// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import kotlin.math.*

/** Paused, read-only solve. Selecting a map layer never advances the clock in the player's archive. */
internal class StudioPassengerOverlay(private val activity: Activity?, private val redraw: () -> Unit) {
    val selection = arrayOf("none","none","line")
    var city = ""
    var allowTap: (() -> Boolean)? = null
    var snapshot: (() -> ByteArray?)? = null
    private val main = Handler(Looper.getMainLooper())
    var manifest: ((ByteArray) -> String)? = null
    private var nativeTask: java.util.concurrent.Future<*>? = null
    private companion object { val executor = java.util.concurrent.Executors.newSingleThreadExecutor() }
    private var generation = 0
    private var error: String? = null
    private var loading = ""
    private val loaded = mutableSetOf<String>()
    val track get() = selection[2]
    val active get() = selection[0] != "none" || selection[1] != "none" || track != "line"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cells = mutableListOf<FloatArray>()
    private val stations = mutableListOf<FloatArray>()
    /** Daily riders at the stop nearest (x,y) and that stop's share of the busiest stop; null until loaded. */
    fun riders(x: Float, y: Float, radius: Float = 120f): Pair<Int, Float>? {
        if (stations.isEmpty()) return null
        val hit = stations.minByOrNull { (it[0]-x)*(it[0]-x)+(it[1]-y)*(it[1]-y) } ?: return null
        if ((hit[0]-x)*(hit[0]-x)+(hit[1]-y)*(hit[1]-y) > radius*radius) return null
        val top = stations.maxOf { it[2] }.coerceAtLeast(1f)
        return hit[2].toInt() to hit[2] / top
    }
    private val edgeProfit = mutableMapOf<Int,Float>()
    private data class Span(val path: Path, val riders: Float, val profit: Float, val color: Int)
    private val spans = mutableListOf<Span>()
    private val bySpeed = mutableMapOf<Int,Path>()
    private val byHeight = mutableMapOf<Int,Path>()
    private var busiest = 0f
    private val routes = mutableMapOf<Int,Pair<Int,PathMeasure>>()
    fun route(id: Int, color: Int, path: Path) { routes[id] = color to PathMeasure(path,false) }

    fun choose(group: Int, choice: String) {
        selection[group] = choice
        error = null
        gridKey = ""
        if ((loading == "trips" && selection[0]=="none") ||
            (loading == "network" && selection[1]=="none" && track=="line")) stop()
        startNeeded()
        redraw()
    }
    fun stop() {
        generation++
        nativeTask?.cancel(true)
        nativeTask = null
        loading = ""
    }
    fun reset() {
        stop()
        error = null
        cells.clear(); stations.clear(); edgeProfit.clear(); spans.clear(); routes.clear(); loaded.clear(); discs.clear()
        bySpeed.clear(); byHeight.clear()
        busiest = 0f
        gridKey = ""
    }
    fun startNeeded() {
        val activity = activity ?: return
        if (activity.isDestroyed || activity.isFinishing || loading.isNotEmpty() || error != null) return
        val kind = when {
            selection[0] != "none" && "trips" !in loaded -> "trips"
            (selection[1] != "none" || track != "line") && "network" !in loaded -> "network"
            else -> return
        }
        val bytes = snapshot?.invoke() ?: return
        val id = ++generation
        loading = kind
        fun failure(reason: String) {
            if (generation != id) return
            stop()
            error = reason
            redraw()
        }
        if (kind == "trips") {
            val zone = city
            val inputs = requireNotNull(manifest)
            nativeTask = executor.submit {
                val result = runCatching {
                    val packed = NativeRidership.tripCells(bytes,inputs(bytes),zone)
                    require(packed.size % 10 == 0)
                    List(packed.size / 10) { i -> packed.copyOfRange(i*10,i*10+10) }
                }
                main.post {
                    if (generation == id) result.fold({ rows ->
                        cells.addAll(rows)
                        loaded.add(kind)
                        gridKey = ""
                        stop()
                        redraw()
                        startNeeded()
                    }, { failure(it.localizedMessage ?: "Data perjalanan belum tersedia.") })
                }
            }
            return
        }
        val inputs = requireNotNull(manifest)
        nativeTask = executor.submit {
            val result = runCatching {
                val data = org.json.JSONObject(NativeRidership.networkLayers(bytes,inputs(bytes)))
                mapOf("stops" to 4, "edges" to 2, "spans" to 5, "rail" to 6, "road" to 4).mapValues { (name,stride) ->
                    val packed = data.getJSONArray(name)
                    require(packed.length() % stride == 0)
                    List(packed.length()/stride) { i -> FloatArray(stride) { j -> packed.getDouble(i*stride+j).toFloat() } }
                }
            }
            main.post {
                if (id != generation) return@post
                val pending = result.getOrElse { failure(it.localizedMessage ?: "Lapisan jaringan belum tersedia."); return@post }
                stations.addAll(pending["stops"].orEmpty())
                for (edge in pending["edges"].orEmpty()) edgeProfit[edge[0].toInt()] = edge[1]
                for (s in pending["spans"].orEmpty()) {
                    val route = routes[s[0].toInt()] ?: continue
                    val measure = route.second
                    val path = Path()
                    if (measure.getSegment(s[1]*measure.length,s[2]*measure.length,path,true)) {
                        spans.add(Span(path,s[3],s[4],route.first))
                        busiest = max(busiest,s[3])
                    }
                }
                fun line(paths:MutableMap<Int,Path>,color:Int,row:FloatArray) {
                    val path=paths.getOrPut(color){ Path() }
                    path.moveTo(row[0],row[1]);path.lineTo(row[2],row[3])
                }
                for(r in pending["rail"].orEmpty()) {
                    line(bySpeed,ramp(r[5].roundToInt().toFloat(),speeds,speedColors),r)
                    line(byHeight,levelColor(r[4]),r)
                }
                for(r in pending["road"].orEmpty()) {
                    val route=routes[r[0].toInt()] ?: continue
                    val measure=route.second
                    val speedColor=if(r[3] < 0) 0xff879194.toInt() else ramp(r[3].roundToInt().toFloat(),speeds,speedColors)
                    measure.getSegment(r[1]*measure.length,r[2]*measure.length,bySpeed.getOrPut(speedColor){Path()},true)
                    measure.getSegment(r[1]*measure.length,r[2]*measure.length,byHeight.getOrPut(levelColor(0f)){Path()},true)
                }
                loaded.add(kind)
                gridKey = ""
                stop()
                redraw()
                main.post { startNeeded() }
            }
        }
    }
    private fun mix(a:Int,b:Int,t:Float) = Color.rgb(
        (Color.red(a)+(Color.red(b)-Color.red(a))*t).roundToInt(),
        (Color.green(a)+(Color.green(b)-Color.green(a))*t).roundToInt(),
        (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).roundToInt())
    private val levels = intArrayOf(0xc2351c,0xee7a1a,0xf0b928,0x58b04a,0x16a39a,0x2f6fd0,0x4a2c9a)
    private val speeds = floatArrayOf(1f,20f,40f,60f,80f,110f,160f,200f,260f,320f)
    private val speedColors = intArrayOf(0x8a2232,0xd85b4b,0xe2922e,0xd9b92e,0xa3c23c,0x4aa850,0x4e9d95,0x397bc1,0x5b4fc9,0xa03fc2)
    private val gains = floatArrayOf(0f,.5f,.95f,1.05f,1.6f,3f)
    private val gainColors = intArrayOf(0xb2182b,0xe9853b,0xf1d54a,0x96d58f,0x8ecae6,0x2166ac)
    private fun ramp(value:Float, keys:FloatArray, colors:IntArray):Int {
        val i = keys.indexOfFirst { value <= it }
        return when { i < 0 -> colors.last() or Color.BLACK; i == 0 -> colors[0] or Color.BLACK
            else -> mix(colors[i-1],colors[i],(value-keys[i-1])/(keys[i]-keys[i-1])) }
    }
    private fun levelColor(raw: Float): Int {
        val z=(raw+3).coerceIn(0f,6f)
        val a=floor(z).toInt()
        return mix(levels[a],levels[min(a+1,6)],z-a)
    }
    fun railColor(id:Int, level:Int, speed:Double) = when(track) {
        "height" -> levels[(level+3).coerceIn(0,6)] or Color.BLACK
        "speed" -> ramp(speed.toFloat(),speeds,speedColors)
        else -> edgeProfit[id]?.let { ramp(it,gains,gainColors) } ?: 0xff879194.toInt()
    }
    fun drawRoutes(c:Canvas, width:Float) {
        paint.style=Paint.Style.STROKE
        paint.strokeCap=Paint.Cap.ROUND
        paint.strokeJoin=Paint.Join.ROUND
        if(track == "height" || track == "speed") {
            paint.strokeWidth=width
            for ((color,path) in (if(track == "height") byHeight else bySpeed)) {
                paint.color=color
                c.drawPath(path,paint)
            }
            return
        }
        for(s in spans) {
            if(track == "traffic") {
                paint.strokeWidth=width*(.5f + 4.2f*(if(busiest>0) s.riders/busiest else 0f))
                paint.color=s.color
            } else if(track == "profit" && s.profit >= 0) {
                paint.strokeWidth=width
                paint.color=ramp(s.profit,gains,gainColors)
            } else continue
            c.drawPath(s.path,paint)
        }
    }
    private data class Disc(val x:Float,val y:Float,val radius:Float,val posX:Float,val posY:Float,
        val counts:DoubleArray,val station:Boolean)
    private val discs=mutableListOf<Disc>()
    private var gridKey = ""
    private val groups = mutableMapOf<Long,DoubleArray>()
    private val modeColors = intArrayOf(0x317bb8,0x67ab55,0xd99154,0x88758a)
    fun circles(c:Canvas, cx:Float, cy:Float, scale:Float, density:Float) {
        discs.clear()
        val w=c.width; val h=c.height
        val end = selection[0]
        if (end != "none" && cells.isNotEmpty()) {
            val bin = 2.0.pow(round(log2((40*density/scale).coerceAtLeast(1f).toDouble())))
            val key="$end/$bin"
            if (key != gridKey) {
                groups.clear()
                for (r in cells) {
                    val gx=floor(r[0]/bin).toLong(); val gy=floor(r[1]/bin).toLong()
                    val g=groups.getOrPut((gx shl 32) or (gy and 0xffffffffL)){ DoubleArray(7) }
                    var n=0.0
                    for(i in 0..3) {
                        val v=(if(end!="work") r[2+i] else 0f)+(if(end!="home") r[6+i] else 0f)
                        g[3+i]+=v.toDouble(); n+=v
                    }
                    g[0]+=r[0]*n; g[1]+=r[1]*n; g[2]+=n
                }
                gridKey=key
            }
            paint.style=Paint.Style.FILL
            for(g in groups.values) {
                if(g[2]<=0) continue
                val x=((g[0]/g[2]-cx)*scale+w/2).toFloat(); val y=((cy-g[1]/g[2])*scale+h/2).toFloat()
                val rad=(sqrt(g[2])/sqrt(bin)*1.6f*density).toFloat().coerceIn(2*density,24*density)
                if(x < -rad || y < -rad || x>w+rad || y>h+rad) continue
                discs.add(Disc(x,y,rad,(g[0]/g[2]).toFloat(),(g[1]/g[2]).toFloat(),g,false))
                var angle=-90f
                for(i in 0..3) {
                    val sweep=(360*g[3+i]/g[2]).toFloat()
                    if(sweep==0f) continue
                    paint.color=modeColors[i] or (0xaf shl 24)
                    c.drawArc(x-rad,y-rad,x+rad,y+rad,angle,sweep,true,paint)
                    angle+=sweep
                }
            }
        }
        val stationMode=selection[1]
        if(stationMode!="none") for(s in stations) {
            val total=s[2]; val changes=s[3].coerceIn(0f,total)
            val n=when(stationMode) { "origins"->total-changes; "transfers"->changes; else->total }
            if(n<=0) continue
            val rad=max(3*density, sqrt(n/140000f)*42*density)
            val x=(s[0]-cx)*scale+w/2; val y=(cy-s[1])*scale+h/2
            if(x < -rad || y < -rad || x > w+rad || y > h+rad) continue
            discs.add(Disc(x,y,rad,s[0],s[1],doubleArrayOf((total-changes).toDouble(),changes.toDouble()),true))
            val tint=mix(0x2456e0,0x169a50,when(stationMode) { "origins"->0f; "transfers"->1f; else->changes/total })
            paint.color=tint;paint.style=Paint.Style.FILL;paint.alpha=60;c.drawCircle(x,y,rad,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.alpha=190;c.drawCircle(x,y,rad,paint)
        }
        paint.style=Paint.Style.FILL
    }
    fun inspect(x:Float,y:Float,nearby:List<NetworkView.Station>,openStation:(Int)->Unit):Boolean {
        val host=activity ?: return false
        if(!active || allowTap?.invoke()==false) return false
        val hit=discs.asReversed().firstOrNull { hypot(x-it.x,y-it.y)<=it.radius } ?: return false
        val format=java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("id-ID"))
        val nearest=nearby.asSequence().filter { it.id>=0 }.minByOrNull {
            hypot(it.x-hit.posX,it.y-hit.posY)
        }
        val title=if(hit.station) "Penumpang stasiun" else when(selection[0]) {
            "home"->"Asal perjalanan"; "work"->"Tujuan perjalanan"; else->"Asal dan tujuan"
        }
        StudioUi.sheet(host,title) { sheet,dialog ->
            val values=if(hit.station) hit.counts else hit.counts.copyOfRange(3,7)
            val total=values.sum()
            val labels=if(hit.station) listOf("Memulai perjalanan","Berganti layanan")
                else listOf("Angkutan umum","Jalan kaki","Kendaraan jalan raya","Tanpa akses")
            val colors=if(hit.station) intArrayOf(0x2456e0,0x169a50) else modeColors
            sheet.addView(StudioDecision(host,format.format(total)+if(hit.station) " penumpang per hari" else " perjalanan per hari").apply {
                for(i in values.indices) {
                    val share=if(total>0) values[i]/total else 0.0
                    bar(labels[i],format.format(values[i])+" · "+format.format(100*share)+"%",share,colors[i] or Color.BLACK)
                }
            })
            if(nearest!=null) sheet.addView(StudioUi.button(host,"Stasiun terdekat · "+nearest.name,icon=StudioUi.Icon.PIN){
                dialog.dismiss()
                openStation(nearest.id)
            })
            sheet.addView(StudioUi.button(host,"Kembali ke peta"){ dialog.dismiss() })
        }
        return true
    }

    fun legend():String? {
        if(!active) return null
        if(error!=null) return "Gagal memuat lapisan · pilih lagi untuk mencoba"
        if(loading.isNotEmpty()) return if(loading=="network" && selection[1]=="none" && (track=="speed" || track=="height"))
            "Memuat profil jalur…" else "Menghitung perjalanan…"
        val parts=mutableListOf<String>()
        if(selection[0]!="none") parts.add(if(cells.isEmpty()) "Belum ada perjalanan" else when(selection[0]){"work"->"Tujuan";"home"->"Asal";else->"Asal + tujuan"})
        if(selection[1]!="none") parts.add(if(stations.none { if(selection[1]=="transfers") it[3]>0 else if(selection[1]=="origins") it[2]>it[3] else it[2]>0 }) "Belum ada penumpang" else when(selection[1]){"transfers"->"Transit";"origins"->"Asal penumpang";else->"Total penumpang"})
        if(track!="line") parts.add(when(track){"height"->"Ketinggian";"speed"->"Kecepatan";"profit"->"Laba";else->"Lalu lintas"})
        return parts.joinToString(" · ")
    }
}
