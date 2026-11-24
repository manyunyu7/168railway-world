// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** Cover picture and headline numbers of an archived world, written when the player leaves the map. */
internal object WorldCovers {
    class Cover(val image: Bitmap?, val lines: Int, val stations: Int, val cash: Double, val day: Int, val second: Int) {
        val dayTime get() = "Hari $day · %02d:%02d".format(second / 3600, second % 3600 / 60)
    }

    private fun dir(c: Context) = File(c.filesDir, "world-archive").apply { mkdirs() }
    private fun image(c: Context, id: String) = File(dir(c), "$id.cover.jpg")
    private fun meta(c: Context, id: String) = File(dir(c), "$id.cover.json")

    fun write(c: Context, id: String, bitmap: Bitmap?, lines: Int, stations: Int, cash: Double, day: Int, second: Int) {
        runCatching {
            if (bitmap != null) {
                val tmp = File(dir(c), "$id.cover.jpg.tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 84, it) }
                check(tmp.renameTo(image(c, id)))
            }
            val json = JSONObject().put("lines", lines).put("stations", stations).put("day", day).put("second", second)
            if (cash.isFinite()) json.put("cash", cash)
            val tmp = File(dir(c), "$id.cover.json.tmp")
            tmp.writeText(json.toString())
            check(tmp.renameTo(meta(c, id)))
        }
    }

    fun read(c: Context, id: String): Cover? = runCatching {
        val m = meta(c, id).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: return null
        val bmp = image(c, id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        Cover(bmp, m.optInt("lines"), m.optInt("stations"), m.optDouble("cash", Double.NaN), m.optInt("day", 1), m.optInt("second"))
    }.getOrNull()

    /** A copied world's cover, as the original's. */
    fun copy(c: Context, from: String, to: String) {
        runCatching { image(c, from).takeIf { it.isFile }?.copyTo(image(c, to), overwrite = true) }
        runCatching { meta(c, from).takeIf { it.isFile }?.copyTo(meta(c, to), overwrite = true) }
    }

    fun delete(c: Context, id: String) { image(c, id).delete(); meta(c, id).delete() }
}
