// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import kotlin.math.*

internal object StudioNetworkDiagram {
    private const val EXPORT=4732
    private fun staging(host:Activity)=File(host.cacheDir,"studio-network-export-${host.taskId}.png")

    fun finishExport(host:Activity, request:Int, result:Int, data:Intent?):Boolean {
        if(request!=EXPORT)return false
        val source=staging(host)
        try {
            val uri=data?.data
            if(result==Activity.RESULT_OK && uri!=null) {
                checkNotNull(host.contentResolver.openOutputStream(uri)).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(host,"Diagram tersimpan.",Toast.LENGTH_SHORT).show()
            }
        } catch (e:Exception) {
            Toast.makeText(host,"Gagal menyimpan diagram. ${e.localizedMessage.orEmpty()}",Toast.LENGTH_LONG).show()
        } finally { source.delete() }
        return true
    }

    fun show(host:Activity,data:JSONObject,city:String,station:(Int)->Unit,layer:CitySurface.Layer?=null,service:(Int)->Unit) {
        val diagram=Diagram(host,data,layer)
        StudioUi.sheet(host,"Diagram jaringan",.94f) { col,dialog ->
            if(!diagram.hasNetwork) {
                col.addView(StudioUi.text(host,"Diagram masih kosong. Stasiun dan jalur yang kamu bangun akan muncul di sini.",16f))
                col.addView(StudioUi.button(host,"Kembali ke peta"){ dialog.dismiss() })
                return@sheet
            }
            fun dp(n:Int)=StudioUi.dp(host,n)
            val selection=StudioUi.text(host,city,16f,true)
            val details=StudioUi.button(host,"Buka detail"){
                dialog.dismiss()
                diagram.selectedStation?.let(station) ?: diagram.selectedService?.let(service)
            }.apply { visibility=View.GONE }
            diagram.selectionChanged={ label,canOpen ->
                selection.text=label ?: if (diagram.allCities) "Seluruh jaringan" else city
                details.visibility=if(canOpen) View.VISIBLE else View.GONE
            }
            col.addView(selection)
            col.addView(diagram,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.47).roundToInt()))
            val tools=LinearLayout(host)
            col.addView(tools)
            fun tool(label:String,icon:StudioUi.Icon,click:()->Unit){
                tools.addView(StudioUi.iconButton(host,label,icon,click=click),LinearLayout.LayoutParams(0,dp(48),1f))
            }
            tool("Perkecil diagram",StudioUi.Icon.MINUS){diagram.zoom(.7f)}
            tool("Perbesar diagram",StudioUi.Icon.PLUS){diagram.zoom(1.5f)}
            tool("Pusatkan diagram",StudioUi.Icon.CENTER){diagram.fit();diagram.invalidate()}
            tool("Simpan diagram PNG",StudioUi.Icon.SAVE){
                val source=staging(host)
                runCatching {
                    source.outputStream().use(diagram::export)
                    host.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type="image/png";addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_TITLE,if (diagram.allCities) "Diagram seluruh jaringan.png" else "Diagram jaringan $city.png")
                    },EXPORT)
                }.onFailure {
                    source.delete();Toast.makeText(host,"Ekspor belum berhasil. ${it.localizedMessage.orEmpty()}",Toast.LENGTH_LONG).show()
                }
            }
            col.addView(details)
            if (diagram.hasInspectableStops) {
                col.addView(StudioUi.button(host,"Cari stasiun di diagram",icon=StudioUi.Icon.PIN) {
                    diagram.chooseStation(host)
                })
            }
            if(layer!=null) col.addView(android.widget.Switch(host).apply {
                text="Tampilkan perairan"
                minHeight=dp(48);setTextColor(StudioUi.INK);typeface=StudioUi.face(host)
                isChecked=diagram.waterVisible
                setOnCheckedChangeListener { _,on -> diagram.setWater(on) }
            })
            val thickness=StudioUi.text(host,"",14f,true)
            col.addView(thickness)
            col.addView(SeekBar(host).apply {
                max=10
                progress=((diagram.multiplier-1)*10).roundToInt()
                contentDescription="Ketebalan garis diagram"
                fun label() { thickness.text="Ketebalan garis · ${100+progress*10}%" }
                label()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean) {
                        label()
                        if(user) diagram.setWidth(1f+p/10f)
                    }
                    override fun onStartTrackingTouch(b:SeekBar){}
                    override fun onStopTrackingTouch(b:SeekBar){}
                })
            },LinearLayout.LayoutParams(-1,dp(48)))
            val routeRows=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL }
            fun refreshRoutes() {
                routeRows.removeAllViews()
                val routes=diagram.visibleRoutes
                if(routes.isEmpty()) {
                    routeRows.addView(StudioUi.text(host,"Belum ada layanan di area ini.",15f))
                    return
                }
                routeRows.addView(StudioUi.text(host,"Jalur",17f,true))
                routes.forEach { route ->
                    val row=StudioUi.button(host,route.name,icon=StudioUi.Icon.ROUTE) {
                        diagram.selectRoute(route); diagram.requestFocus()
                        (col.parent as? android.widget.ScrollView)?.smoothScrollTo(0,0)
                    }
                    row.setCompoundDrawables(StudioUi.glyph(host,StudioUi.Icon.ROUTE,route.color),null,null,null)
                    row.gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    routeRows.addView(row)
                }
            }
            if(diagram.hasOtherCities) {
                col.addView(android.widget.Switch(host).apply {
                    text="Seluruh jaringan · semua kota"
                    minHeight=dp(48);setTextColor(StudioUi.INK);typeface=StudioUi.face(host)
                    setOnCheckedChangeListener { _,on ->
                        diagram.changeScope(on)
                        refreshRoutes()
                    }
                },1)
            }
            col.addView(routeRows)
            refreshRoutes()
        }
    }

    private data class Route(val id:Int,val name:String,val color:Int,val xy:FloatArray,val path:Path)
    private data class Stop(val id:Int,val name:String,val x:Float,val y:Float)
    private class Diagram(context:Context,data:JSONObject,private val layer:CitySurface.Layer?):View(context) {
        val routes=mutableListOf<Route>()
        private val stops=mutableListOf<Stop>()
        private val density=resources.displayMetrics.density
        private val prefs=context.getSharedPreferences("studio-display",Context.MODE_PRIVATE)
        var multiplier=prefs.getFloat("diagramWidth",1f).coerceIn(1f,2f)
            private set
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND;typeface=StudioUi.face(context,true)}
        var waterVisible=prefs.getBoolean("diagramWater",true)
            private set
        private val water by lazy {
            layer?.bitmap?.let { src ->
                val pixels=IntArray(src.width*src.height)
                src.getPixels(pixels,0,src.width,0,0,src.width,src.height)
                for(i in pixels.indices) pixels[i]=if(pixels[i]==0xffaccfdc.toInt()) 0xffbbd9de.toInt() else Color.TRANSPARENT
                Bitmap.createBitmap(pixels,src.width,src.height,Bitmap.Config.ARGB_8888)
            }
        }
        // Geometry is shared by all cities of a save. The terrain bounds are in the
        // same projected frame as its stations, including when another city is visited.
        private val region:RectF? = (layer?.bounds ?: data.optJSONArray("waterBox")?.let { b ->
            if (b.length()==4) FloatArray(4){b.getDouble(it).toFloat()} else null
        })?.let { b -> RectF(b[0],-b[3],b[2],-b[1]).takeIf { it.width()>0 && it.height()>0 } }
        private class Extent {
            var left=Float.POSITIVE_INFINITY; var top=Float.POSITIVE_INFINITY
            var right=Float.NEGATIVE_INFINITY; var bottom=Float.NEGATIVE_INFINITY
            val populated get()=left.isFinite()
            fun add(x:Float,y:Float) {
                left=min(left,x);top=min(top,y);right=max(right,x);bottom=max(bottom,y)
            }
        }
        private val whole=Extent()
        private val local=Extent()
        private val localRouteIds=mutableSetOf<Int>()
        var allCities=false
            private set
        var hasOtherCities=false
            private set
        private val area get()=if(allCities || region==null) whole else local
        private val minX get()=area.left
        private val minY get()=area.top
        private val maxX get()=area.right
        private val maxY get()=area.bottom
        val visibleRoutes get()=if(allCities || region==null) routes else routes.filter { it.id in localRouteIds }
        private fun inCity(x:Float,y:Float)=region?.let{ x>=it.left && x<=it.right && y>=it.top && y<=it.bottom } ?: true
        private fun include(x:Float,y:Float):Boolean {
            whole.add(x,y)
            if(inCity(x,y)){local.add(x,y);return true}
            hasOtherCities=true;return false
        }
        // Record the visible part of any segment crossing the edge of this city.
        private fun citySegment(ax:Float,ay:Float,bx:Float,by:Float):Boolean {
            val b=region ?: return true
            var lo=0f;var hi=1f
            val dx=bx-ax;val dy=by-ay
            fun bound(p:Float,q:Float):Boolean {
                if(p==0f)return q>=0f
                if(p<0)lo=max(lo,q/p) else hi=min(hi,q/p)
                return lo<=hi
            }
            if (!bound(-dx,ax-b.left) || !bound(dx,b.right-ax) ||
                !bound(-dy,ay-b.top) || !bound(dy,b.bottom-ay)) return false
            local.add(ax+lo*dx,ay+lo*dy);local.add(ax+hi*dx,ay+hi*dy);return true
        }
        private var cx=0f;private var cy=0f;private var scale=1f
        private var baseScale=1f
        private var currentStop:Stop?=null
        private var currentRoute:Route?=null
        val selectedStation get()=currentStop?.id?.takeIf{it>=0}
        val selectedService get()=currentRoute?.id
        val hasNetwork get()=whole.populated
        var selectionChanged:(String?,Boolean)->Unit={_,_->}
        init {
            isFocusable=true;isClickable=true
            contentDescription="Diagram jaringan. Geser untuk menjelajah atau ketuk jalur dan stasiun."
            val rows=data.getJSONArray("routes")
            for(i in 0 until rows.length()){
                val obj=rows.getJSONObject(i)
                val points=obj.getJSONArray("points")
                val xy=FloatArray(points.length()){ points.getDouble(it).toFloat()*(if(it%2==0)1 else -1) }
                if(xy.size<2)continue
                val path=Path()
                val id=obj.getInt("id")
                var previousInside=false
                for(j in 0 until xy.size-1 step 2){
                    val inside=include(xy[j],xy[j+1])
                    if(inside) localRouteIds.add(id)
                    if(j>0 && !(inside && previousInside) && citySegment(xy[j-2],xy[j-1],xy[j],xy[j+1])) localRouteIds.add(id)
                    previousInside=inside
                    if(j==0)path.moveTo(xy[j],xy[j+1])else path.lineTo(xy[j],xy[j+1])
                }
                routes.add(Route(obj.getInt("id"),obj.optString("name").ifBlank{"Jalur ${i+1}"},obj.getInt("color") or Color.BLACK,xy,path))
            }
            val stations=data.getJSONArray("stations")
            for(i in 0 until stations.length()){
                val r=stations.getJSONArray(i)
                val s=Stop(r.getInt(3),r.getString(2),r.getDouble(0).toFloat(),-r.getDouble(1).toFloat())
                stops.add(s);include(s.x,s.y)
            }
            if(!local.populated && region!=null) {
                local.add(region.left,region.top)
                local.add(region.right,region.bottom)
            }
        }
        fun changeScope(all:Boolean) {
            allCities=all;currentStop=null;currentRoute=null;selectionChanged(null,false)
            fit();invalidate()
        }
        val hasInspectableStops get()=stops.any { it.id>=0 }
        fun chooseStation(host:Activity) {
            val options=stops.filter{it.id>=0 && (allCities || inCity(it.x,it.y))}.sortedBy{it.name}
            StudioUi.sheet(host,"Cari stasiun di diagram") { body,menu ->
                val search=android.widget.EditText(host).apply {
                    hint="Nama stasiun"
                    setSingleLine()
                }
                body.addView(search)
                val list=android.widget.ListView(host)
                body.addView(list,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.45f).roundToInt()))
                val empty=StudioUi.text(host,"Tidak ada stasiun dengan nama itu.",14f)
                body.addView(empty)
                fun populate(query:String) {
                    val found=options.filter{it.name.contains(query.trim(),true)}
                    list.adapter=android.widget.ArrayAdapter(host,android.R.layout.simple_list_item_1,found.map{it.name})
                    empty.visibility=if(found.isEmpty()) View.VISIBLE else View.GONE
                    list.setOnItemClickListener { _,_,position,_ ->
                        val s=found[position];currentStop=s;currentRoute=null
                        cx=s.x;cy=s.y;scale=max(scale,baseScale*4)
                        selectionChanged(s.name,true);invalidate();menu.dismiss()
                    }
                }
                populate("")
                search.addTextChangedListener(object:android.text.TextWatcher{
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun afterTextChanged(s:android.text.Editable?) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=populate(s.toString())
                })
            }
        }
        fun setWater(on:Boolean) { waterVisible=on;prefs.edit().putBoolean("diagramWater",on).apply();invalidate() }
        fun setWidth(value:Float) { multiplier=value;prefs.edit().putFloat("diagramWidth",value).apply();invalidate() }
        fun selectRoute(route:Route) {
            currentStop=null;currentRoute=route;selectionChanged(route.name,true);invalidate()
        }
        private fun fitted(w:Int,h:Int,pad:Float):Float = min(
            (w-2*pad).coerceAtLeast(1f)/(maxX-minX).coerceAtLeast(100f),
            (h-2*pad).coerceAtLeast(1f)/(maxY-minY).coerceAtLeast(100f))
        fun fit(){
            if(!hasNetwork)return
            cx=minX+(maxX-minX)/2;cy=minY+(maxY-minY)/2
            baseScale=fitted(width,height,22*density);scale=baseScale
        }
        fun zoom(f:Float,x:Float=width*.5f,y:Float=height*.5f){
            val next=(scale*f).coerceIn(baseScale*.35f,baseScale*256)
            cx+=(x-width*.5f)*(1/scale-1/next);cy+=(y-height*.5f)*(1/scale-1/next);scale=next;invalidate()
        }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){fit()}
        private fun render(c:Canvas,w:Int,h:Int,centerX:Float,centerY:Float,k:Float,d:Float,selected:Boolean) {
            c.drawColor(0xfffaf7ed.toInt())
            val lineWidth=3*d*multiplier
            c.save(); c.translate(w*.5f,h*.5f);c.scale(k,k);c.translate(-centerX,-centerY)
            if (waterVisible && layer!=null) water?.let { bmp ->
                val bounds=layer.bounds
                paint.color=Color.WHITE;paint.isFilterBitmap=true
                c.drawBitmap(bmp,null,RectF(bounds[0],-bounds[3],bounds[2],-bounds[1]),paint)
            }
            paint.style=Paint.Style.STROKE
            if(!allCities && region!=null) c.clipRect(region)
            for(r in visibleRoutes){
                paint.strokeWidth=lineWidth/k
                paint.color=r.color
                if(selected && currentRoute!=null && r!=currentRoute)paint.alpha=75
                c.drawPath(r.path,paint)
            }
            c.restore()
            paint.style=Paint.Style.FILL
            val labels=HashSet<Long>()
            val selectedPoint=if(selected)currentStop else null
            fun point(s:Stop){
                val x=(s.x-centerX)*k+w*.5f;val y=(s.y-centerY)*k+h*.5f
                if(x< -8*d || x>w+8*d || y< -8*d || y>h+8*d)return
                val chosen=s===selectedPoint
                val size=(if(chosen) 6f else 3f)*d*sqrt(multiplier)
                paint.color=if(chosen)0xffdf7233.toInt() else 0xff273f38.toInt()
                c.drawCircle(x,y,size,paint);paint.color=Color.WHITE;c.drawCircle(x,y,size*.56f,paint)
                val col=floor(x/(91*d)).toLong();val row=floor(y/(28*d)).toLong()
                if(chosen || labels.add((col shl 32) xor row)){
                    paint.textSize=(if(chosen)13f else 10f)*d
                    val tx=x+size+2*d;val ty=y-3*d
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f*d;paint.color=0xfffaf7ed.toInt()
                    c.drawText(s.name,tx,ty,paint);paint.style=Paint.Style.FILL;paint.color=0xff24463d.toInt()
                    c.drawText(s.name,tx,ty,paint)
                }
            }
            stops.forEach { if(it!==selectedPoint && (allCities || inCity(it.x,it.y)))point(it) }
            selectedPoint?.let(::point)
        }
        override fun onDraw(c:Canvas) { render(c,width,height,cx,cy,scale,density,true) }
        fun export(out:java.io.OutputStream) {
            val side=2400
            val dx=(maxX-minX).coerceAtLeast(100f)
            val dy=(maxY-minY).coerceAtLeast(100f)
            val w=(side*dx/max(dx,dy)).roundToInt().coerceAtLeast(480)
            val h=(side*dy/max(dx,dy)).roundToInt().coerceAtLeast(480)
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try{
                render(Canvas(bitmap),w,h,minX+(maxX-minX)*.5f,minY+(maxY-minY)*.5f,fitted(w,h,64f),2f,false)
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
            }finally{bitmap.recycle()}
        }
        private fun pick(x:Float,y:Float){
            val wx=(x-width*.5f)/scale+cx;val wy=(y-height*.5f)/scale+cy
            val stop=stops.asSequence().filter{allCities || inCity(it.x,it.y)}.minByOrNull { (it.x-wx).toDouble().pow(2)+(it.y-wy).toDouble().pow(2) }
            if(stop!=null && hypot(stop.x-wx,stop.y-wy)*scale<14*density){
                currentStop=stop;currentRoute=null;selectionChanged(stop.name,selectedStation!=null)
            }else{
                var best=14*density/scale
                var hit:Route?=null
                for(r in visibleRoutes)for(i in 2 until r.xy.size-1 step 2){
                    if(!allCities && !inCity(wx,wy))continue
                    val ax=r.xy[i-2];val ay=r.xy[i-1];val dx=r.xy[i]-ax;val dy=r.xy[i+1]-ay
                    val length=dx*dx+dy*dy
                    val t=if(length==0f)0f else (((wx-ax)*dx+(wy-ay)*dy)/length).coerceIn(0f,1f)
                    val dist=hypot(wx-ax-t*dx,wy-ay-t*dy)
                    if(dist<best){best=dist;hit=r}
                }
                currentStop=null;currentRoute=hit;selectionChanged(hit?.name,hit!=null)
            }
            invalidate()
        }
        private val pinch=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(detector:ScaleGestureDetector):Boolean{
                zoom(detector.scaleFactor,detector.focusX,detector.focusY);return true
            }
        })
        private val drag=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{pick(e.x,e.y);performClick();return true}
            override fun onDoubleTap(e:MotionEvent):Boolean{zoom(1.6f,e.x,e.y);return true}
            override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean{
                if(!pinch.isInProgress){cx+=dx/scale;cy+=dy/scale;invalidate()};return true
            }
        })
        override fun performClick():Boolean{super.performClick();return true}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(e.actionMasked==MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            pinch.onTouchEvent(e);drag.onTouchEvent(e)
            if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL)
                parent.requestDisallowInterceptTouchEvent(false)
            return true
        }
    }
}
