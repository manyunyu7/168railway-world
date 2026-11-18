// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import kotlin.math.max

/** Embedded game surface. All host controls and typography are supplied by the host's shell. */
class JakartaMapController(context: Context, private val onState: (Map<String,Any?>)->Unit) {
    private val viewMap=NetworkView(context)
    val view: View get() = viewMap
    private val app=context.applicationContext
    private val ui=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val slot=AtomicFile(File(app.filesDir,"world-native-jak.save"))
    private var handle=0L
    private var clock=0.0
    private var running=false
    private var drawing=false
    private var busy=true
    private var disposed=false
    private var lastTick=0L
    private var lastEvent=0L
    private var message="Memuat peta Jakarta…"
    private var availablePack: CityPackStore.CityPack?=null

    init {
        viewMap.workshop=true
        viewMap.onStationTap={ id ->
            val inspect = !drawing && !busy && !disposed && handle!=0L
            if(inspect) onState(snapshot()+("inspectStation" to id))
            inspect
        }
        viewMap.onLocation={p->
            if(!busy && drawing && viewMap.draft.size<24){
                viewMap.draft.add(p);viewMap.invalidate()
                message=if(viewMap.draft.size==1)"Ketuk lokasi stasiun kedua." else "Rute siap dibangun."
                publish()
            }
        }
        viewMap.onBend={p->
            if(!busy && drawing && viewMap.draft.isNotEmpty() && viewMap.bends.size<64){
                viewMap.bends.add(viewMap.draft.lastIndex to p);viewMap.invalidate()
                message="Titik lengkung ditambahkan.";publish()
            }
        }
        openWorld(false,null)
    }

    private fun snapshot(): Map<String,Any?> = mapOf("ready" to (handle!=0L && !disposed),"busy" to busy,
        "city" to "Jakarta","clock" to clock,"running" to running,"drawing" to drawing,
        "points" to viewMap.draft.size,"curves" to viewMap.bends.size,"lines" to viewMap.routeCount,
        "stations" to viewMap.stationCount,"trains" to (viewMap.trains.size/3),"message" to message,
        "hasSave" to slot.baseFile.exists())

    private fun publish(){if(!disposed)onState(snapshot())}

    private fun openWorld(requireSave:Boolean, done:((Map<String,Any?>)->Unit)?){
        busy=true;pause();message=if(requireSave)"Membuka simpanan Jakarta…" else "Memuat peta Jakarta…"
        ui.post { publish() }
        val cached=availablePack
        worker.execute {
            var loaded=0L
            try {
                val pack=cached ?: CityPackStore(app.filesDir,app.assets.open("world-native/cities.tsv").bufferedReader()).load("jak")
                val saved=try {slot.readFully()}catch(e:FileNotFoundException){if(requireSave)throw e else null}
                loaded=if(saved!=null)Engine.open(saved) else CitySurface.empty("jak",pack.header.readText())
                check(loaded!=0L){"Format simpanan Jakarta tidak dikenali."}
                val geo=JSONObject(Engine.geometry(loaded))
                val terrain=CitySurface.load(loaded,pack)
                val incoming=loaded
                ui.post {
                    if(disposed){Engine.close(incoming);done?.invoke(snapshot());return@post}
                    if(handle!=0L)Engine.close(handle)
                    handle=incoming;availablePack=pack;clock=geo.getDouble("clock")
                    clearDraft();viewMap.cityLayer=terrain;viewMap.load(geo)
                    busy=false;refresh()
                    message=if(saved==null)"Pilih lokasi stasiun pertamamu." else "Simpanan Jakarta dibuka."
                    publish();done?.invoke(snapshot())
                }
            } catch(e:Exception){
                if(loaded!=0L)Engine.close(loaded)
                ui.post {busy=false;message=e.localizedMessage?:"Peta Jakarta gagal dibuka.";publish();done?.invoke(snapshot())}
            }
        }
    }

    fun command(name:String,args:Map<String,Any?>,done:(Map<String,Any?>)->Unit){
        if(name=="upgradeStation" || name=="stations" || name=="station" || name=="diagram"){
            if(disposed || busy || handle==0L){done(mapOf("error" to "Peta belum siap."));return}
            val result=runCatching {
                when(name){
                    "stations" -> org.json.JSONArray().apply {
                        for(stop in viewMap.stops) put(JSONObject().put("id",stop.id).put("name",stop.name))
                    }.toString()
                    "upgradeStation" -> StationInspector.upgrade(handle,(args["id"] as Number).toInt(),(args["bit"] as Number).toInt(),(args["on"] as Number).toInt(),clock)
                    "station" -> StationInspector.read(handle,(args["id"] as Number).toInt().also { require(it>=0) },-1,0,clock)
                    else -> StationInspector.read(handle,-1,(args["line"] as Number).toInt(),(args["run"] as Number).toInt(),clock)
                }
            }.getOrNull()
            done(if(result==null || result=="null") mapOf("error" to "Jadwal tidak tersedia.") else mapOf("data" to result))
            return
        }
        if(name=="state" || disposed){done(snapshot());return}
        if(busy){done(snapshot());return}
        if(name=="restore"){openWorld(true,done);return}
        if(handle==0L){
            if(name=="retry")openWorld(false,done) else done(snapshot())
            return
        }
        try{
            when(name){
                "beginBuild"->{pause();clearDraft();drawing=true;message="Ketuk peta untuk menempatkan stasiun."}
                "cancelBuild"->{clearDraft();message="Rancangan dibatalkan."}
                "build"->{
                    check(drawing && viewMap.draft.size>=2){"Pilih sedikitnya dua stasiun."}
                    val service=(args["name"] as? String)?.trim().orEmpty()
                    check(service.isNotEmpty()){"Isi nama layanan."}
                    val mode=(args["mode"] as? Number)?.toInt()?:0
                    check(mode in listOf(0,1,3,4,5)){"Moda belum tersedia."}
                    val coords=viewMap.draft.flatMap {listOf(it.x.toDouble(),it.y.toDouble())}.toDoubleArray()
                    val bends=viewMap.bends.flatMap {(leg,p)->listOf(leg.toDouble(),p.x.toDouble(),p.y.toDouble())}.toDoubleArray()
                    if(mode==4 || mode==5) {
                        val directory=RoadTileStore.directory(app)
                        val candidate=Engine.forkCandidate(handle,clock,directory.absolutePath)
                        check(candidate!=0L){"Gagal menyiapkan salinan dunia."}
                        val via=buildList {
                            viewMap.draft.forEachIndexed { i,p->
                                add(p.x.toDouble());add(p.y.toDouble())
                                viewMap.bends.filter { it.first==i }.forEach { (_,q)->add(q.x.toDouble());add(q.y.toDouble()) }
                            }
                        }.toDoubleArray()
                        val now=clock
                        busy=true; message=if(mode==5)"Memuat jaringan jalan OpenStreetMap…" else "Mencari lintasan perairan…";publish()
                        worker.execute {
                            val r=runCatching {
                                if(mode==5) RoadTileStore.prepare(directory,Engine.requiredRoads(candidate,via))
                                JSONObject(Engine.build(candidate,coords,service,now,mode,bends)).also {
                                    check(!it.has("error")){it.optString("error")}
                                }
                            }
                            ui.post {
                                if(disposed){Engine.close(candidate);done(snapshot());return@post}
                                busy=false
                                r.onSuccess {
                                    Engine.close(handle);handle=candidate
                                    clock=max(clock,it.getDouble("departure"))
                                    clearDraft();viewMap.load(JSONObject(Engine.geometry(handle)),false);refresh()
                                    message="Layanan " + it.getString("name") + " siap dijalankan."
                                }.onFailure { Engine.close(candidate);message=it.localizedMessage ?: "Layanan belum dapat dibangun." }
                                publish();done(snapshot())
                            }
                        }
                        return
                    }
                    val r=JSONObject(Engine.build(handle,coords,service,clock,mode,bends))
                    check(!r.has("error")){r.optString("error")}
                    clock=max(clock,r.getDouble("departure"))
                    clearDraft();viewMap.load(JSONObject(Engine.geometry(handle)),false);refresh()
                    message="Jalur " + r.getString("name") + " siap dijalankan."
                }
                "play"->{check(viewMap.routeCount>0){"Bangun jalur terlebih dahulu."};if(!running){running=true;lastTick=SystemClock.elapsedRealtime();ui.post(tick)};message="Kereta berjalan."}
                "pause"->{pause();message="Permainan dijeda."}
                "save"->{
                    val bytes=Engine.save(handle,clock);check(bytes.isNotEmpty()){"Simpanan kosong."}
                    val stream=slot.startWrite()
                    try{stream.write(bytes);slot.finishWrite(stream)}catch(e:Exception){slot.failWrite(stream);throw e}
                    message="Jakarta tersimpan di perangkat."
                }
                else->error("Perintah tidak dikenali.")
            }
        }catch(e:Exception){message=e.localizedMessage?:"Perintah gagal."}
        publish();done(snapshot())
    }
    private fun clearDraft(){drawing=false;viewMap.draft.clear();viewMap.bends.clear();viewMap.invalidate()}
    private fun pause(){running=false;ui.removeCallbacks(tick)}
    private fun refresh(){viewMap.trains=Engine.positions(handle,clock);viewMap.invalidate()}
    private val tick=object:Runnable{
        override fun run(){
            if(!running || disposed || handle==0L)return
            val now=SystemClock.elapsedRealtime()
            clock+=(now-lastTick)*0.008;lastTick=now;refresh()
            if(now-lastEvent>=250){lastEvent=now;publish()}
            ui.postDelayed(this,65)
        }
    }
    fun dispose(){
        if(disposed)return
        disposed=true;pause();worker.shutdownNow()
        if(handle!=0L){Engine.close(handle);handle=0}
    }
}
