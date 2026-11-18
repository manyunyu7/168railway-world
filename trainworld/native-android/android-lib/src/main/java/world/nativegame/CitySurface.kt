// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Bitmap
import org.json.JSONObject

internal object CitySurface {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun empty(id: String, header: String): Long
    @JvmStatic external fun attach(handle: Long, header: String, cells: ByteArray, terrain: ByteArray): IntArray?
    @JvmStatic external fun project(handle: Long, lonlat: DoubleArray): FloatArray
    data class Place(val name: String, val kind: Int, val x: Float, val y: Float)
    data class Layer(val bitmap: Bitmap, val bounds: FloatArray, val places: List<Place>)
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
            Place(row.getString(0), row.getInt(1), xy[i*2], xy[i*2+1])
        }
    }
    fun load(handle: Long, pack: CityPackStore.CityPack): Layer {
        val pixels = checkNotNull(attach(handle, pack.header.readText(), pack.cells.readBytes(), pack.terrain.readBytes())) { "Medan atau perairan kota gagal dibaca" }
        val width = pixels[0]; val height = pixels[1]
        check(width > 0 && height > 0 && pixels.size == width * height + 6)
        return Layer(Bitmap.createBitmap(pixels, 6, width, width, height, Bitmap.Config.ARGB_8888), FloatArray(4) { Float.fromBits(pixels[2 + it]) }, places(handle, pack))
    }
}
