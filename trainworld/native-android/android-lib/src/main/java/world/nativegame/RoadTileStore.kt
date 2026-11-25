// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Immutable OpenFreeMap/OSM release shared between drafts in the application's private cache. */
internal object RoadTileStore {
    private const val release = StudioMapTiles.release
    private const val tileLimit = 2 * 1024 * 1024
    fun directory(context: Context): File = File(context.cacheDir, "world-roads/$release")
    fun prepare(root:File, json:String) {
        val result = JSONObject(json)
        require(!result.has("error")) { result.optString("error") }
        val tiles=result.getJSONArray("tiles")
        require(tiles.length() <= 64)
        check(root.isDirectory || root.mkdirs()) { "Cache jalan tidak dapat dibuka." }
        for (i in 0 until tiles.length()) {
            val pos=tiles.getJSONArray(i); val x=pos.getInt(0); val y=pos.getInt(1)
            require(x in 0..16383 && y in 0..16383)
            val dest=File(root,"14-$x-$y.pbf")
            if (dest.isFile && dest.length()<=tileLimit) continue
            val conn=URL("https://tiles.openfreemap.org/planet/$release/14/$x/$y.pbf").openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent","168Railway World (https://168railway.com)")
                conn.connectTimeout=8000; conn.readTimeout=10000;conn.instanceFollowRedirects=false
                val bytes=when(conn.responseCode) {
                    204 -> byteArrayOf()
                    200 -> conn.inputStream.buffered().use {raw->
                        raw.mark(2); val gz=raw.read()==31 && raw.read()==139; raw.reset()
                        val data=if(gz) GZIPInputStream(raw) else raw
                        val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(8192)
                        while(true){val n=data.read(buf);if(n<0)break
                            check(out.size()+n<=tileLimit){"Paket jalan terlalu besar."};out.write(buf,0,n)}
                        out.toByteArray()
                    }
                    else -> error("Data jalan belum tersedia (HTTP ${conn.responseCode}). Coba lagi saat terhubung.")
                }
                synchronized(this) {
                    val file=AtomicFile(dest);val stream=file.startWrite()
                    try {stream.write(bytes);file.finishWrite(stream)} catch(e:Exception){file.failWrite(stream);throw e}
                }
            } finally {conn.disconnect()}
        }
    }
}
