// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Path
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Coarse web outlines, shared by the map at country scale and the offline place lookup. */
internal class ViewportZones(context: Context) {
    class Zone(val id: String, val center: DoubleArray, val box: DoubleArray, val rings: List<IntArray>)
    private val zones = LinkedHashMap<String, Zone>()
    private val q: Double
    init {
        DataInputStream(GZIPInputStream(context.assets.open("world-native/zone-outlines.bin")).buffered()).use { stream ->
            q = stream.readInt().toDouble()
            repeat(stream.readInt()) {
                val id = stream.readUTF()
                val center = DoubleArray(2) { stream.readDouble() }
                val box = DoubleArray(4) { stream.readDouble() }
                val rings = List(stream.readInt()) {
                    val previous = IntArray(2)
                    IntArray(stream.readInt()) { i ->
                        previous[i % 2] += stream.readInt()
                        previous[i % 2]
                    }
                }
                zones[id] = Zone(id, center, box, rings)
            }
        }
    }

    fun center(id: String) = zones[id]?.center
    fun visible(w: Double, s: Double, e: Double, n: Double) = zones.values.filter { z ->
        val b = z.box
        b[1] <= n && b[3] >= s && (if (w <= e) b[0] <= e && b[2] >= w else b[0] <= e || b[2] >= w)
    }

    private val exactRings = android.util.LruCache<String,List<IntArray>>(24)
    private val reference = File(context.filesDir,"world-data/${CityPackStore.RELEASE}/reference")
    private fun boundary(zone: Zone): List<IntArray> {
        exactRings.get(zone.id)?.let { return it }
        val features = JSONObject(NativeRidership.boundary(reference,zone.id).readText()).getJSONArray("features")
        val rings = mutableListOf<IntArray>()
        fun polygon(poly: JSONArray) {
            for (j in 0 until poly.length()) {
                val ring = poly.getJSONArray(j)
                rings.add(IntArray(ring.length()*2) { i -> (ring.getJSONArray(i/2).getDouble(i%2)*1e7).roundToInt() })
            }
        }
        for (i in 0 until features.length()) {
            val g = features.getJSONObject(i).getJSONObject("geometry")
            val coords = g.getJSONArray("coordinates")
            when (g.getString("type")) {
                "Polygon" -> polygon(coords)
                "MultiPolygon" -> for (j in 0 until coords.length()) polygon(coords.getJSONArray(j))
            }
        }
        exactRings.put(zone.id,rings)
        return rings
    }
    fun place(lon: Double, lat: Double, exact: Boolean = false): String? {
        val dx = 111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(.01)
        val dy = 111320.0
        var best = 40000.0 * 40000.0
        var nearest: String? = null
        for (z in visible(lon - 40000/dx, lat - 40000/dy, lon + 40000/dx, lat + 40000/dy)) {
            var contains = false
            val precise = if (exact) runCatching { boundary(z) }.getOrNull() else null
            val rings = precise ?: z.rings
            val units = if (precise == null) q else 1e7
            for (ring in rings) {
                if (ring.size < 6) continue
                var ax = (ring[ring.size-2]/units - lon)*dx
                var ay = (ring.last()/units - lat)*dy
                var inside = false
                for (i in ring.indices step 2) {
                    val bx = (ring[i]/units - lon)*dx
                    val by = (ring[i+1]/units - lat)*dy
                    if ((ay > 0) != (by > 0) && ax - ay*(bx-ax)/(by-ay) > 0) inside = !inside
                    val x = bx-ax
                    val y = by-ay
                    val denom = x*x + y*y
                    val t = if (denom > 0) (-(ax*x+ay*y)/denom).coerceIn(0.0,1.0) else 0.0
                    val d = (ax+t*x).pow(2) + (ay+t*y).pow(2)
                    if (d < best) { best = d; nearest = z.id }
                    ax = bx
                    ay = by
                }
                if (inside) contains = !contains
            }
            if (contains) return z.id
        }
        return nearest
    }

    private val paths = android.util.LruCache<String, Path>(96)
    fun path(handle: Long, zone: Zone): Path {
        val precise = exactRings.get(zone.id)
        val key = if (precise == null) zone.id else "${zone.id}:exact"
        paths.get(key)?.let { return it }
        val path = Path()
        val units = if (precise == null) q else 1e7
        for (ring in precise ?: zone.rings) {
            if (ring.size < 6) continue
            var at = 0
            while (at < ring.size) {
                val start = at
                val count = minOf(65536,ring.size-at)
                val xy = CitySurface.project(handle, DoubleArray(count) { ring[start+it]/units })
                for (i in xy.indices step 2) {
                    if (start == 0 && i == 0) path.moveTo(xy[i],xy[i+1]) else path.lineTo(xy[i],xy[i+1])
                }
                at += count
            }
            path.close()
        }
        paths.put(key,path)
        return path
    }
}
