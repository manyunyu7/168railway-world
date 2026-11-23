// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

internal data class MapTile(val z:Int,val x:Int,val y:Int) {
    val name get() = "$z-$x-$y.pbf"
    fun parent(): MapTile? = if(z>0) MapTile(z-1,x/2,y/2) else null
}

internal object StudioMapTiles {
    const val release = "20261115_080022_pt"
    private const val limit = 2*1024*1024
    private val lock = Any()
    private var writes = 0
    private val discoveryLock = Any()
    private var checkedAt = 0L
    private var checkedRelease: String? = null
    private fun root(c:Context,version:String) = File(c.cacheDir,"world-basemap/$version")
    private val releaseName=Regex("[0-9]{8}_[0-9]{6}_[A-Za-z0-9_-]{1,12}")
    private val template=Regex("""https://tiles\.openfreemap\.org/planet/([0-9]{8}_[0-9]{6}_[A-Za-z0-9_-]{1,12})/\{z\}/\{x\}/\{y\}\.pbf""")
    private fun prefs(c:Context)=c.getSharedPreferences("studio-basemap-release",Context.MODE_PRIVATE)
    private fun current(c:Context)=prefs(c).getString("current",release)?.takeIf(releaseName::matches) ?: release
    private fun connection(url:String)=(URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=5000;readTimeout=7500;instanceFollowRedirects=false
        setRequestProperty("User-Agent","168Railway Studio (https://168railway.com)")
    }
    private fun discover(c:Context,previous:String):String = synchronized(discoveryLock) {
        val known=current(c)
        if(known!=previous)return@synchronized known
        val now=android.os.SystemClock.elapsedRealtime()
        if(checkedRelease==known && now-checkedAt<180_000L)return@synchronized known
        val conn=connection("https://tiles.openfreemap.org/planet")
        try {
            require(conn.responseCode==200)
            val json=org.json.JSONObject(String(bounded(conn.inputStream),Charsets.UTF_8))
            val tiles=json.getJSONArray("tiles")
            val next=(0 until tiles.length()).firstNotNullOfOrNull { template.matchEntire(tiles.optString(it))?.groupValues?.get(1) }
                ?:error("TileJSON OpenFreeMap tidak memiliki rilis peta yang dikenali")
            checkedAt=now;checkedRelease=next
            if(next!=previous)prefs(c).edit().putString("current",next).putString("previous",previous).apply()
            next
        } finally { conn.disconnect() }
    }
    private fun bounded(raw:InputStream):ByteArray = raw.buffered().use { stream ->
        stream.mark(2)
        val compressed = stream.read()==31 && stream.read()==139
        stream.reset()
        (if(compressed) GZIPInputStream(stream) else stream).use { input ->
            val out=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(16384)
            while(true) { val n=input.read(buffer); if(n<0) break
                require(out.size()+n<=limit); out.write(buffer,0,n) }
            out.toByteArray()
        }
    }
    fun localNames(c:Context):Set<String> {
        val names=HashSet<String>()
        for(version in listOfNotNull(current(c),release,prefs(c).getString("previous",null)?.takeIf(releaseName::matches)).distinct())
            root(c,version).list()?.let { names.addAll(it) }
        RoadTileStore.directory(c).list()?.let { names.addAll(it) }
        return names
    }
    fun local(c:Context,t:MapTile):ByteArray? {
        for(version in listOfNotNull(current(c),release,prefs(c).getString("previous",null)?.takeIf(releaseName::matches)).distinct()) {
            val disk=File(root(c,version),t.name)
            if(disk.isFile && disk.length()<=limit) return runCatching { bounded(AtomicFile(disk).openRead()) }.getOrNull()
        }
        if(t.z==14) {
            val draft=File(RoadTileStore.directory(c),t.name)
            if(draft.isFile && draft.length()<=limit) return runCatching { bounded(AtomicFile(draft).openRead()) }.getOrNull()
        }
        // aapt may unpack .gz during packaging and store the uncompressed file without the suffix.
        for (name in listOf(t.name, "${t.name}.gz")) {
            val bytes = runCatching { bounded(c.assets.open("world-native/basemap/$name")) }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }
    fun fetch(c:Context,t:MapTile):ByteArray {
        local(c,t)?.let { return it }
        fun read(version:String):Pair<Int,ByteArray?> {
            val conn=connection("https://tiles.openfreemap.org/planet/$version/${t.z}/${t.x}/${t.y}.pbf")
            return try {
                val status=conn.responseCode
                status to when(status){200->bounded(conn.inputStream);204->byteArrayOf();else->null}
            } finally { conn.disconnect() }
        }
        var version=current(c)
        var response=read(version)
        if(response.first==404 || response.first==410) {
            val refreshed=discover(c,version)
            if(refreshed!=version){version=refreshed;response=read(version)}
        }
        val bytes=response.second ?: error("Tile HTTP ${response.first}")
        synchronized(lock) {
            val dir=root(c,version); if(!dir.isDirectory) dir.mkdirs()
            val disk=File(dir,t.name)
            if(!disk.isFile) {
                val atomic=AtomicFile(disk); val out=atomic.startWrite()
                try { out.write(bytes); atomic.finishWrite(out) } catch(e:Exception){atomic.failWrite(out);throw e}
                if (++writes%32==0) {
                    val files=dir.listFiles { f->f.name.endsWith(".pbf") }.orEmpty()
                    var size=files.sumOf { it.length() }
                    if(size>128L*1024*1024) for(f in files.sortedBy { it.lastModified() }) {
                        val length=f.length(); if(f.delete()) size-=length
                        if(size<=112L*1024*1024) break
                    }
                }
            }
        }
        return bytes
    }
}
