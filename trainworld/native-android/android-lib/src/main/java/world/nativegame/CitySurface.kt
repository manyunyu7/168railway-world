// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Bitmap

internal object CitySurface {
    @JvmStatic external fun attach(handle: Long, header: String, cells: ByteArray, terrain: ByteArray): IntArray?
    data class Layer(val bitmap: Bitmap, val bounds: FloatArray)
    fun load(handle: Long, pack: CityPackStore.CityPack): Layer {
        val pixels = checkNotNull(attach(handle, pack.header.readText(), pack.cells.readBytes(), pack.terrain.readBytes())) { "Medan atau perairan kota gagal dibaca" }
        val width = pixels[0]; val height = pixels[1]
        check(width > 0 && height > 0 && pixels.size == width * height + 6)
        return Layer(Bitmap.createBitmap(pixels, 6, width, width, height, Bitmap.Config.ARGB_8888), FloatArray(4) { Float.fromBits(pixels[2 + it]) })
    }
}
