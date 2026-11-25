// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Bitmap
import org.json.JSONObject

internal object CitySurface {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun visit(handle: Long, home: String, zone: String): Boolean
    @JvmStatic external fun worldHome(handle: Long, setting: Int): Boolean
    @JvmStatic external fun fork(handle: Long): Long
    @JvmStatic external fun visitViewport(handle: Long, home: String, zone: String): Boolean
    @JvmStatic external fun copy(source: Long, destination: Long)
    /** A save opened with world [surface]'s city terrain and water, derived once (Engine.open then [copy] derive twice). */
    @JvmStatic external fun open(bytes: ByteArray, surface: Long): Long
    /** `{"key","clock","cash"}`: what the map draws of the world as a short key (Engine.geometry without its clock and cash). */
    @JvmStatic external fun geometryKey(handle: Long): String
    @JvmStatic external fun empty(id: String, header: String): Long
    @JvmStatic external fun attach(handle: Long, header: String, cells: ByteArray, terrain: ByteArray): IntArray?
    @JvmStatic external fun lonLat(handle:Long,x:Float,y:Float):DoubleArray
    @JvmStatic external fun project(handle: Long, lonlat: DoubleArray): FloatArray
    data class Place(val name: String, val kind: Int, val x: Float, val y: Float, val pop: Double = 0.0)
    data class Layer(val bitmap: Bitmap, val bounds: FloatArray, val places: List<Place>) {
        fun overview(): Layer {
            val span = maxOf(bitmap.width,bitmap.height)
            if (span <= 256) return this
            val ratio = 256.0/span
            return copy(bitmap = Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*ratio).toInt()),
                maxOf(1,(bitmap.height*ratio).toInt()),true))
        }
    }
    private fun places(handle: Long, pack: CityPackStore.CityPack): List<Place> {
        if (!pack.places.isFile) return emptyList()
        val rows = JSONObject(pack.places.readText()).getJSONArray("places")
        val coordinates = DoubleArray(rows.length() * 2)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            coordinates[i*2] = row.getDouble(2)
            coordinates[i*2+1] = row.getDouble(3)
        }
        val xy = project(handle, coordinates)
        return List(rows.length()) { i ->
            val row = rows.getJSONArray(i)
            Place(row.getString(0), row.getInt(1), xy[i*2], xy[i*2+1], row.optDouble(4, 0.0).takeIf { it.isFinite() } ?: 0.0)
        }
    }
    fun load(handle: Long, pack: CityPackStore.CityPack, overview: Boolean = false): Layer {
        val pixels = checkNotNull(attach(handle, pack.header.readText(), pack.cells.readBytes(), pack.terrain.readBytes())) { "Medan atau perairan kota gagal dibaca" }
        val width = pixels[0]; val height = pixels[1]
        check(width > 0 && height > 0 && pixels.size == width * height + 6)
        val full = Bitmap.createBitmap(pixels, 6, width, width, height, Bitmap.Config.ARGB_8888)
        val layer = Layer(full, FloatArray(4) { Float.fromBits(pixels[2 + it]) }, places(handle, pack))
        return if (overview) layer.overview().also { if (it !== layer) full.recycle() } else layer
    }
}
