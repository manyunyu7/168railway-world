package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.PointF
import android.widget.EditText
import android.text.InputFilter
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
    @JvmStatic external fun workshop(): Long
    @JvmStatic external fun build(handle: Long, points: DoubleArray, name: String, clock: Double, mode: Int, bends: DoubleArray): String
    @JvmStatic external fun open(bytes: ByteArray): Long
    @JvmStatic external fun geometry(handle: Long): String
    @JvmStatic external fun positions(handle: Long, time: Double): FloatArray
    @JvmStatic external fun save(handle: Long, time: Double): ByteArray
    @JvmStatic external fun close(handle: Long)
}

class NativeWorldActivity : Activity() {
    private var handle = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val archiveSlot by lazy {
        intent.getStringExtra("archiveSlot")?.let { id -> WorldDocuments.list(this).firstOrNull { it.id == id } }
    }
    private val legacyWorkshop by lazy { archiveSlot == null && intent.getBooleanExtra("workshop", false) }
    private val workshop get() = archiveSlot?.workshop ?: (legacyWorkshop || !city.hasNetwork)
    private val cityStore by lazy { CityPackStore(filesDir,assets.open("world-native/cities.tsv").bufferedReader()) }
    private val city by lazy {
        val selected = archiveSlot?.city ?: if(legacyWorkshop) "nyc" else intent.getStringExtra("city") ?: getSharedPreferences("world-native",0).getString("last-city","nyc") ?: "nyc"
        cityStore.city(selected)
    }
    private var activePack: CityPackStore.CityPack? = null
    private val local by lazy {
        AtomicFile(archiveSlot?.let { WorldDocuments.path(this,it.id) } ?: File(filesDir, if (legacyWorkshop) "world-native-workshop.save" else "world-native-${city.id}.save"))
    }
    private lateinit var build: Button
    private var draftMode = 0
    private var draftName = ""
    private var drawing = false
    private var lastService = ""
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
            text = if (workshop) "Dunia bangun · ${city.name}" else "168Railway World · Native"; textSize = 20f
            setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(Button(this).apply {
            text = "Kota"
            setOnClickListener { showCityPicker(this@NativeWorldActivity,cityStore,city.id) { handle==0L || !save.isEnabled || writeSave() } }
        }, LinearLayout.LayoutParams(-2,dp(56)))
        root.addView(header)
        info = TextView(this).apply { text = "Membuka jaringan ${city.name}…"; setTextColor(Color.WHITE); setPadding(dp(12),0,dp(12),dp(8)) }
        root.addView(info)
        map = NetworkView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(dp(12),dp(8),dp(12),0) }
        root.addView(status)
        build = Button(this).apply {
            text = if (workshop) "Bangun jalur" else "Buka dunia bangun"
            isEnabled = false
            setOnClickListener {
                if (!workshop) {
                    startActivity(Intent(this@NativeWorldActivity, NativeWorldActivity::class.java).putExtra("workshop", true))
                } else if (drawing) {
                    cancelDrawing()
                } else {
                    beginDrawing()
                }
            }
        }
        val tools = LinearLayout(this)
        tools.addView(build, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(Button(this).apply {
            text = "Layanan"
            setOnClickListener {
                if (handle == 0L) return@setOnClickListener
                running = false
                map.removeCallbacks(tick)
                play.text = "Jalankan ×8"
                ServicePanel.open(this@NativeWorldActivity,handle,clock,workshop) { name ->
                    lastService = name
                    lastStatus = -1
                    refresh()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(Button(this).apply {
            text = "Penumpang"
            setOnClickListener {
                if (handle == 0L || closed) return@setOnClickListener
                val snapshot = Engine.save(handle, clock)
                val hour = (clock.toLong() / 3600 % 24).toInt()
                val period = when (hour) {
                    in 6..9 -> 0
                    in 10..15 -> 1
                    in 16..19 -> 2
                    in 20..23 -> 3
                    else -> 4
                }
                fun show(pack: CityPackStore.CityPack) {
                    NativeRidership.show(this@NativeWorldActivity, snapshot, pack.header, pack.cells, period)
                }
                val pack = activePack
                if (pack != null) show(pack) else {
                    isEnabled = false
                    text = "Memuat…"
                    executor.execute {
                        val result = runCatching { cityStore.load(city.id) { _, _ -> } }
                        runOnUiThread {
                            if (!closed) {
                                isEnabled = true
                                text = "Penumpang"
                                result.onSuccess { activePack = it; show(it) }.onFailure {
                                    AlertDialog.Builder(this@NativeWorldActivity)
                                        .setTitle("Data penumpang ${city.name} belum tersedia")
                                        .setMessage(it.localizedMessage).setPositiveButton("Tutup", null).show()
                                }
                            }
                        }
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tools)
        map.onLocation = { point -> if (drawing) selectPoint(point) }
        map.onBend = { point ->
            if (drawing && map.draft.isNotEmpty() && map.bends.size < 64) {
                map.bends.add(Pair(map.draft.lastIndex,point))
                map.invalidate()
                status.text = "Titik lengkung ditambahkan. Ketuk stasiun berikutnya atau tahan untuk titik berikutnya."
            }
        }
        map.workshop = workshop
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
            text = "Simpan ▾"; isEnabled = false
            setOnClickListener {
                AlertDialog.Builder(this@NativeWorldActivity).setTitle("Simpanan")
                    .setItems(arrayOf("Simpan di perangkat","Arsip & akun")) { _,index ->
                        if(index==0) writeSave()
                        else if(writeSave()) {
                            startActivity(Intent(this@NativeWorldActivity,WorldArchiveActivity::class.java)
                                .putExtra("archiveSource",archiveSlot?.id ?: local.baseFile.name).putExtra("city",city.id))
                        }
                    }.show()
            }
        }
        toolbar.addView(play, LinearLayout.LayoutParams(0,dp(56),1f))
        toolbar.addView(Button(this).apply {
            text = "Keuangan"
            setOnClickListener {
                if (handle == 0L || closed) return@setOnClickListener
                NativeFinance.show(this@NativeWorldActivity, handle, { clock }) { writeSave() }
            }
        }, LinearLayout.LayoutParams(0,dp(56),1f))
        toolbar.addView(save, LinearLayout.LayoutParams(0,dp(56),1f))
        root.addView(toolbar); setContentView(root)
        executor.execute {
            try {
                var progressPercent = -1
                val pack = try {
                    cityStore.load(city.id) { done,total ->
                        val percent=(done*100/total).toInt()
                        if (percent!=progressPercent) {
                            progressPercent=percent
                            runOnUiThread { if(!closed) info.text="Mengunduh ${city.name} · $percent%" }
                        }
                    }
                } catch (e: Exception) {
                    if(city.id!="nyc") throw e
                    null // Bundled legacy starter remains available on a first offline launch.
                }
                val snapshot = try { local.readFully() } catch (_: java.io.FileNotFoundException) { null }
                val restored = snapshot != null
                val loaded = when {
                    snapshot != null -> Engine.open(snapshot)
                    legacyWorkshop -> Engine.workshop()
                    pack != null && !city.hasNetwork -> CitySurface.empty(city.id,pack.header.readText())
                    pack != null -> Engine.open(pack.network.readBytes())
                    else -> assets.open("world-native/nyc.save").use { Engine.open(it.readBytes()) }
                }
                check(loaded != 0L) { "Format simpanan tidak dikenali" }
                val geometry: JSONObject
                val layer: CitySurface.Layer?
                try {
                    geometry=JSONObject(Engine.geometry(loaded))
                    layer=pack?.let { CitySurface.load(loaded,it) }
                } catch (e: Throwable) {
                    Engine.close(loaded)
                    throw e
                }
                runOnUiThread {
                    if (closed) { Engine.close(loaded); return@runOnUiThread }
                    handle = loaded; activePack=pack; clock = geometry.getDouble("clock")
                    map.cityLayer=layer
                    map.load(geometry)
                    info.text = "${city.name}  ·  ${map.routeCount} jalur  ·  ${map.stationCount} stasiun"
                    if(!legacyWorkshop) getSharedPreferences("world-native",0).edit().putString("last-city",city.id).apply()
                    build.isEnabled = true
                    play.isEnabled = !workshop || map.routeCount > 0
                    save.isEnabled = !workshop || map.routeCount > 0
                    if (workshop && map.routeCount > 0) {
                        val routes = geometry.getJSONArray("routes")
                        lastService = routes.getJSONObject(routes.length()-1).getString("name")
                    }
                    refresh()
                    if (restored) status.append("  ·  Simpanan dibuka")
                    if (workshop && map.routeCount == 0) beginDrawing()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!closed) {
                        info.text = "Jaringan ${city.name} gagal dibuka."
                        AlertDialog.Builder(this).setTitle(info.text).setMessage(e.message)
                            .setPositiveButton("Coba lagi") { _, _ -> recreate() }
                            .setNegativeButton("Kembali") { _, _ -> finish() }.show()
                    }
                }
            }
        }
    }
    private fun beginDrawing() {
        running = false
        map.removeCallbacks(tick)
        play.text = "Jalankan ×8"
        play.isEnabled = false
        save.isEnabled = false
        drawing = true
        draftName = "Lintas " + (map.routeCount + 1)
        draftMode = 0
        map.draft.clear(); map.bends.clear()
        map.invalidate()
        build.text = "Batalkan"
        status.text = "Ketuk peta untuk menempatkan stasiun awal A."
    }

    private fun cancelDrawing() {
        drawing = false
        map.draft.clear(); map.bends.clear()
        map.invalidate()
        build.text = "Bangun jalur"
        play.isEnabled = map.routeCount > 0
        save.isEnabled = map.routeCount > 0
        lastStatus = -1
        refresh()
    }

    private fun selectPoint(point: PointF) {
        if (map.draft.size >= 24) return
        map.draft.add(point)
        map.invalidate()
        if (map.draft.size == 1) {
            status.text = "Ketuk stasiun B (minimal 350 m), atau tahan peta untuk menambah titik lengkung."
            return
        }
        val input = EditText(this).apply {
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(48))
            setText(draftName)
            selectAll()
        }
        val vehicle = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@NativeWorldActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Kereta komuter", "Kereta cepat", "Trem · rel khusus"))
            setSelection(draftMode)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),0,dp(20),0)
            addView(input, LinearLayout.LayoutParams(-1,-2))
            addView(vehicle)
        }
        var keepStops = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Buat layanan")
            .setMessage("${map.draft.size} stasiun · Ketuk dekat stasiun lama untuk memakai rel yang sudah dibangun.")
            .setView(form)
            .setPositiveButton("Bangun & jalankan", null)
            .setNeutralButton(if (map.draft.size<24) "Tambah stasiun" else null) { _,_ ->
                keepStops = true
                status.text = "Ketuk lokasi stasiun ${('A'.code + map.draft.size).toChar()}."
            }
            .setNegativeButton("Pilih ulang", null)
            .create()
        dialog.setOnDismissListener {
            draftMode = vehicle.selectedItemPosition
            draftName = input.text.toString()
            if (drawing && !keepStops && map.draft.size >= 2) {
                map.draft.removeAt(map.draft.lastIndex)
                map.bends.removeAll { it.first >= map.draft.lastIndex }
                map.invalidate()
                status.text = "Ketuk lokasi lain untuk stasiun ${('A'.code + map.draft.size).toChar()}."
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val coords = map.draft.flatMap { listOf(it.x.toDouble(),it.y.toDouble()) }.toDoubleArray()
            val bends = map.bends.flatMap { (leg,p) -> listOf(leg.toDouble(),p.x.toDouble(),p.y.toDouble()) }.toDoubleArray()
            val result = JSONObject(Engine.build(handle, coords, input.text.toString(), clock, listOf(0,1,3)[vehicle.selectedItemPosition], bends))
            if (result.has("error")) {
                input.error = result.getString("error")
            } else {
                drawing = false
                dialog.dismiss()
                map.draft.clear(); map.bends.clear()
                lastService = result.getString("name")
                clock = max(clock, result.getDouble("departure"))
                map.load(JSONObject(Engine.geometry(handle)))
                info.text = "${city.name}  ·  ${map.routeCount} jalur  ·  ${map.stationCount} stasiun"
                build.text = "Bangun jalur"
                play.isEnabled = true
                save.isEnabled = true
                lastTick = SystemClock.elapsedRealtime()
                running = true
                play.text = "Jeda"
                map.post(tick)
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
            val service = if (workshop && lastService.isNotEmpty()) "  ·  $lastService" else ""
            status.text = "%02d:%02d:%02d   ·   %d kereta%s".format(second/3600%24,second/60%60,second%60,positions.size/3,service)
        }
    }
    private fun writeSave(): Boolean {
        val bytes = Engine.save(handle,clock)
        val stream = runCatching { local.startWrite() }.getOrElse { status.text="Gagal menyimpan"; return false }
        try {
            if(bytes.isEmpty())throw java.io.IOException("Simpanan kosong")
            stream.write(bytes); local.finishWrite(stream); status.append("  ·  Tersimpan"); return true
        } catch (e: Exception) { local.failWrite(stream); status.text="Gagal menyimpan"; return false }
    }
    override fun onResume() {
        super.onResume()
        if (!legacyWorkshop && handle != 0L) getSharedPreferences("world-native",0).edit().putString("last-city",city.id).apply()
    }
    override fun onPause() {
        super.onPause(); running=false; play.text="Jalankan ×8"; map.removeCallbacks(tick)
    }
    override fun onDestroy() {
        closed=true; map.removeCallbacks(tick); executor.shutdownNow()
        if(handle!=0L) {Engine.close(handle); handle=0}
        super.onDestroy()
    }
}

internal class NetworkView(context: Context):View(context) {
    var cityLayer: CitySurface.Layer? = null
    var workshop = false
    var onLocation: ((PointF) -> Unit)? = null
    var onBend: ((PointF) -> Unit)? = null
    val bends = mutableListOf<Pair<Int,PointF>>()
    val draft = mutableListOf<PointF>()
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
    fun load(data:JSONObject, refit:Boolean = true) {
        lines.clear()
        stops.clear()
        val bounds=data.getJSONArray("waterBox")
        for(i in 0..3) waterBox[i]=cityLayer?.bounds?.get(i) ?: bounds.getDouble(i).toFloat()
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
        if(refit)fit(); invalidate()
    }
    private fun fit() {
        if (width == 0 || height == 0) return
        if (stops.isEmpty()) {
            centerX = 0f
            centerY = 0f
            homeScale = width / 9000f
            scale = homeScale
            return
        }
        val xs=stops.map{it.x}.sorted();val ys=stops.map{it.y}.sorted()
        val lo=stops.size/8; val hi=stops.size-1-lo
        centerX=(xs[lo]+xs[hi])/2;centerY=(ys[lo]+ys[hi])/2
        homeScale=min(width/max(600f,xs[hi]-xs[lo]),height/max(600f,ys[hi]-ys[lo]))*.7f
        scale=homeScale
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fit()}
    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(0xffeaf2ee.toInt())
        if(waterBox[0]!=waterBox[2]) {
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE;paint.isFilterBitmap=true
            c.drawBitmap(cityLayer?.bitmap ?: water,null,RectF(sx(waterBox[0]),sy(waterBox[3]),sx(waterBox[2]),sy(waterBox[1])),paint)
        }
        cityLayer?.let { layer ->
            val cells = mutableSetOf<Long>()
            paint.style=Paint.Style.FILL
            for (place in layer.places) {
                val x=sx(place.x); val y=sy(place.y)
                if (x<0 || x>=width || y<14*density || y>=height) continue
                val cell=((x/(95*density)).toLong() shl 32) or (y/(30*density)).toLong()
                if(!cells.add(cell)) continue
                paint.textSize=(if(place.kind <= 1) 12f else 10f)*density
                paint.color=0xff5a6c64.toInt()
                c.drawText(place.name,x,y,paint)
            }
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
            val radius = (if (workshop) 5f else 2f) * density
            paint.color=0xff183b46.toInt()
            c.drawRoundRect(x-1.5f*radius,y-radius,x+1.5f*radius,y+radius,density,density,paint)
            paint.color=0xffffeb85.toInt();c.drawCircle(x,y,1.25f*density,paint)
        }
        if (draft.isNotEmpty()) {
            paint.color=0xffdd692e.toInt()
            paint.strokeWidth=3*density
            draft.forEachIndexed { i,start ->
                var last=start
                for ((leg,p) in bends) if (leg==i) {
                    c.drawLine(sx(last.x),sy(last.y),sx(p.x),sy(p.y),paint)
                    c.drawCircle(sx(p.x),sy(p.y),3*density,paint)
                    last=p
                }
                draft.getOrNull(i+1)?.let { c.drawLine(sx(last.x),sy(last.y),sx(it.x),sy(it.y),paint) }
            }
        }
        for ((index,p) in draft.withIndex()) {
            val x=sx(p.x);val y=sy(p.y)
            paint.color=0xffcf5823.toInt();c.drawCircle(x,y,10*density,paint)
            paint.textSize=12*density;paint.color=Color.WHITE
            c.drawText(('A'.code+index).toChar().toString(),x-4*density,y+4*density,paint)
        }
        paint.color=0xc8ffffff.toInt();c.drawRect(0f,height-17*density,162*density,height.toFloat(),paint)
        paint.textSize=9*density;paint.color=0xff38575c.toInt()
        val credit = if (cityLayer == null) "Perairan · US Census TIGER/Line 2020" else "© OpenStreetMap contributors · ODbL"
        c.drawText(credit,5*density,height-5*density,paint)
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
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
            onLocation?.invoke(PointF(centerX+(e.x-width/2f)/scale,centerY-(e.y-height/2f)/scale))
            return true
        }
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!pinch.isInProgress){centerX+=dx/scale;centerY-=dy/scale;invalidate()};return true
        }
        override fun onLongPress(e:MotionEvent) {
            onBend?.invoke(PointF(centerX+(e.x-width/2f)/scale,centerY-(e.y-height/2f)/scale))
        }
        override fun onDoubleTap(e:MotionEvent):Boolean{fit();invalidate();return true}
    })
    override fun onTouchEvent(e:MotionEvent):Boolean {pinch.onTouchEvent(e);pan.onTouchEvent(e);return true}
}
