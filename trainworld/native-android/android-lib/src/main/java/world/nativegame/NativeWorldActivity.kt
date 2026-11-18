package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

internal object Engine {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun open(bytes: ByteArray): Long
    @JvmStatic external fun geometry(handle: Long): String
    @JvmStatic external fun positions(handle: Long, time: Double): FloatArray
    @JvmStatic external fun save(handle: Long, time: Double): ByteArray
    @JvmStatic external fun close(handle: Long)
}

class NativeWorldActivity : Activity() {
    private var handle = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val local by lazy { AtomicFile(File(filesDir, "world-native-nyc.save")) }
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var map: NetworkView
    private lateinit var play: Button
    private lateinit var save: Button
    private var clock = 0.0
    private var running = false
    private var lastTick = 0L
    private var lastStatus = -1
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff153d42.toInt())
            setPadding(0, 0, 0, 0)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this)
        val back = Button(this).apply { text = "‹"; contentDescription = "Kembali"; setOnClickListener { finish() } }
        header.addView(back, LinearLayout.LayoutParams(dp(52), dp(56)))
        header.addView(TextView(this).apply {
            text = "168Railway World · Native"; textSize = 20f
            setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(header)
        info = TextView(this).apply { text = "Membuka jaringan New York…"; setTextColor(Color.WHITE); setPadding(dp(12),0,dp(12),dp(8)) }
        root.addView(info)
        map = NetworkView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(dp(12),dp(8),dp(12),0) }
        root.addView(status)
        val toolbar = LinearLayout(this)
        play = Button(this).apply {
            text = "Jalankan ×8"; isEnabled = false
            setOnClickListener {
                running = !running; lastTick = SystemClock.elapsedRealtime()
                text = if (running) "Jeda" else "Jalankan ×8"
                if (running) {map.removeCallbacks(tick); map.post(tick)}
            }
        }
        save = Button(this).apply {
            text = "Simpan"; isEnabled = false
            setOnClickListener { writeSave() }
        }
        toolbar.addView(play, LinearLayout.LayoutParams(0,dp(56),1f))
        toolbar.addView(save, LinearLayout.LayoutParams(0,dp(56),1f))
        root.addView(toolbar); setContentView(root)
        executor.execute {
            try {
                val restored = local.baseFile.exists()
                val bytes = if (restored) local.readFully() else assets.open("world-native/nyc.save").use { it.readBytes() }
                val loaded = Engine.open(bytes)
                check(loaded != 0L) { "Format simpanan tidak dikenali" }
                val geometry = JSONObject(Engine.geometry(loaded))
                runOnUiThread {
                    if (closed) { Engine.close(loaded); return@runOnUiThread }
                    handle = loaded; clock = geometry.getDouble("clock")
                    map.load(geometry)
                    info.text = "New York  ·  ${map.routeCount} jalur  ·  ${map.stationCount} stasiun"
                    play.isEnabled = true; save.isEnabled = true
                    refresh()
                    if (restored) status.append("  ·  Simpanan dibuka")
                }
            } catch (e: Exception) {
                runOnUiThread { if (!closed) info.text = "Jaringan gagal dibuka: ${e.message}" }
            }
        }
    }
    private fun dp(value:Int) = (value * resources.displayMetrics.density).toInt()
    private val tick = object : Runnable {
        override fun run() {
            if (!running || handle==0L) return
            val now=SystemClock.elapsedRealtime()
            clock+=(now-lastTick)*0.008; lastTick=now
            refresh(); map.postDelayed(this, 65)
        }
    }
    private fun refresh() {
        val positions=Engine.positions(handle,clock); map.trains=positions; map.invalidate()
        val second=clock.toInt()
        if(second!=lastStatus) {
            lastStatus=second
            status.text = "%02d:%02d:%02d   ·   %d kereta".format(second/3600%24,second/60%60,second%60,positions.size/3)
        }
    }
    private fun writeSave() {
        val bytes = Engine.save(handle,clock)
        val stream = runCatching { local.startWrite() }.getOrElse { status.text="Gagal menyimpan"; return }
        try {
            stream.write(bytes); local.finishWrite(stream); status.append("  ·  Tersimpan")
        } catch (e: Exception) { local.failWrite(stream); status.text="Gagal menyimpan" }
    }
    override fun onPause() {
        super.onPause(); running=false; play.text="Jalankan ×8"; map.removeCallbacks(tick)
    }
    override fun onDestroy() {
        closed=true; map.removeCallbacks(tick); executor.shutdown()
        if(handle!=0L) {Engine.close(handle); handle=0}
        super.onDestroy()
    }
}

private class NetworkView(context: Context):View(context) {
    val lines = mutableListOf<Pair<Int,Path>>()
    data class Station(val x:Float,val y:Float,val name:String)
    val stops=mutableListOf<Station>()
    var trains=FloatArray(0)
    val stationCount get()=stops.size
    val routeCount get()=lines.size
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val water=context.assets.open("world-native/nyc-water.png").use { BitmapFactory.decodeStream(it) }
    private val waterBox=FloatArray(4)

    private var centerX=0f; private var centerY=0f
    private var scale=0.01f; private var homeScale=0.01f
    private fun sx(x:Float)=(x-centerX)*scale+width/2f
    private fun sy(y:Float)=(centerY-y)*scale+height/2f
    fun load(data:JSONObject) {
        val bounds=data.getJSONArray("waterBox")
        for(i in 0..3) waterBox[i]=bounds.getDouble(i).toFloat()
        val routes=data.getJSONArray("routes")
        for(i in 0 until routes.length()) {
            val row=routes.getJSONObject(i);val points=row.getJSONArray("points")
            val path=Path()
            for(j in 0 until points.length() step 2) {
                val x=points.getDouble(j).toFloat();val y=points.getDouble(j+1).toFloat()
                if(j==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            lines.add((row.getInt("color") or Color.BLACK) to path)
        }
        val stations=data.getJSONArray("stations")
        for(i in 0 until stations.length()) {
            val s=stations.getJSONArray(i)
            stops.add(Station(s.getDouble(0).toFloat(),s.getDouble(1).toFloat(),s.getString(2)))
        }
        fit(); invalidate()
    }
    private fun fit() {
        if(stops.isEmpty() || width==0 || height==0) return
        val xs=stops.map{it.x}.sorted();val ys=stops.map{it.y}.sorted()
        val lo=stops.size/8; val hi=stops.size-1-lo
        centerX=(xs[lo]+xs[hi])/2;centerY=(ys[lo]+ys[hi])/2
        homeScale=min(width/(xs[hi]-xs[lo]),height/(ys[hi]-ys[lo]))*.88f
        scale=homeScale
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fit()}
    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(0xffeaf2ee.toInt())
        if(waterBox[0]!=waterBox[2]) {
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE;paint.isFilterBitmap=true
            c.drawBitmap(water,null,RectF(sx(waterBox[0]),sy(waterBox[3]),sx(waterBox[2]),sy(waterBox[1])),paint)
        }
        c.save();c.translate(width/2f,height/2f);c.scale(scale,-scale);c.translate(-centerX,-centerY)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.6f*density/scale;paint.strokeJoin=Paint.Join.ROUND
        for((color,line) in lines){paint.color=color;c.drawPath(line,paint)}
        c.restore()
        paint.style=Paint.Style.FILL
        var labelX=-10000f;var labelY=-10000f
        val labelCells=mutableSetOf<Long>()
        for(s in stops){
            val x=sx(s.x);val y=sy(s.y)
            if(x<0||x>width||y<0||y>height) continue
            paint.color=0xff356367.toInt();c.drawCircle(x,y,2.2f*density,paint)
            paint.color=Color.WHITE;c.drawCircle(x,y,1.3f*density,paint)
            val col=(x/(103*density)).toLong();val row=(y/(60*density)).toLong()
            if(labelCells.add((col shl 32) or row) && (kotlin.math.abs(x-labelX)>78*density || kotlin.math.abs(y-labelY)>20*density)){
                paint.textSize=9*density;paint.color=0xff294849.toInt();c.drawText(s.name,x+4*density,y-3*density,paint);labelX=x;labelY=y
            }
        }
        for(i in trains.indices step 3){
            val x=sx(trains[i+1]);val y=sy(trains[i+2])
            if(x<0||x>width||y<0||y>height)continue
            paint.color=0xff183b46.toInt();c.drawRoundRect(x-3*density,y-2*density,x+3*density,y+2*density,density,density,paint)
            paint.color=0xffffeb85.toInt();c.drawCircle(x,y,1.25f*density,paint)
        }
        paint.color=0xc8ffffff.toInt();c.drawRect(0f,height-17*density,162*density,height.toFloat(),paint)
        paint.textSize=9*density;paint.color=0xff38575c.toInt()
        c.drawText("Perairan · US Census TIGER/Line 2020",5*density,height-5*density,paint)
    }
    private val pinch=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(d:ScaleGestureDetector):Boolean{
            val old=scale
            scale=(scale*d.scaleFactor).coerceIn(homeScale*.25f,max(homeScale*35,homeScale))
            centerX+=(d.focusX-width/2)*(1/old-1/scale)
            centerY-=(d.focusY-height/2)*(1/old-1/scale)
            invalidate();return true
        }
    })
    private val pan=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!pinch.isInProgress){centerX+=dx/scale;centerY-=dy/scale;invalidate()};return true
        }
        override fun onDoubleTap(e:MotionEvent):Boolean{fit();invalidate();return true}
    })
    override fun onTouchEvent(e:MotionEvent):Boolean {pinch.onTouchEvent(e);pan.onTouchEvent(e);return true}
}
