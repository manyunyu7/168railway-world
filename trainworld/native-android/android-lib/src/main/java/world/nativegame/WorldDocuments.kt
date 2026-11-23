// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.GZIPInputStream

internal object WorldDocuments {
    const val LIMIT = 8 * 1024 * 1024
    data class Slot(val id: String, val title: String, val city: String, val workshop: Boolean)
    fun read(input: InputStream, limit: Int = LIMIT): ByteArray = input.use { source ->
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(32768)
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            require(bytes.size() + n <= limit) { "Ukuran berkas melebihi batas." }
            bytes.write(chunk, 0, n)
        }
        bytes.toByteArray()
    }
    fun header(bytes: ByteArray): JSONObject {
        require(bytes.size <= LIMIT) { "Ukuran berkas melebihi batas." }
        val raw = read(GZIPInputStream(bytes.inputStream()), 32 * 1024 * 1024)
        require(raw.size >= 32 && String(raw, 0, 4, Charsets.US_ASCII) == "TWG1") { "Format dunia tidak dikenali." }
        val n = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(n in 2..(raw.size - 28)) { "Header dunia rusak." }
        val h = JSONObject(String(raw, 8, n, Charsets.UTF_8))
        require(h.optString("game") == "anitabuilder" && h.optInt("format") == 1)
        return h
    }
    fun startingCity(header: JSONObject, cities: List<CityPackStore.City>): String? {
        val preferred = listOf("home", "start", "nativeArchiveCity")
            .map { header.optString(it) }.filter { it.isNotEmpty() }
        preferred.firstOrNull { id -> cities.any { it.id == id } }?.let { return it }
        val opened = header.optJSONArray("opened")
        if (opened != null && opened.length() == 1) {
            val one = opened.optString(0)
            if (cities.any { it.id == one }) return one
        }
        // Modern world saves use NYC as the coordinate frame even for networks in other cities.
        if (preferred.isNotEmpty() || (opened != null && opened.length() > 1)) return null
        val legacy = header.optString("city")
        return legacy.takeIf { id -> cities.any { it.id == id } && (!header.has("region") || id != "nyc") }
    }

    fun path(context: Context, id: String) = File(context.filesDir, "world-archive/${UUID.fromString(id)}.save")
    private fun prefs(context: Context) = context.getSharedPreferences("studio-archive-index", 0)
    fun list(context: Context): List<Slot> = prefs(context).all.mapNotNull { (id, value) ->
        runCatching {
            val h = JSONObject(value as String)
            if (h.has("hostOwner")) {
                val profile = context.getSharedPreferences("FlutterSharedPreferences", 0)
                    .getString("flutter.railway_user_data", null) ?: return@mapNotNull null
                if (JSONObject(profile).optString("id") != h.getString("hostOwner")) return@mapNotNull null
            }
            check(path(context,id).isFile)
            Slot(id, h.getString("name"), h.getString("city"), h.optBoolean("workshop"))
        }.getOrNull()
    }.sortedBy { it.title }
    fun import(context: Context, bytes: ByteArray, name: String, city: String, owner: String? = null): Slot {
        val h = header(bytes)
        val handle = Engine.open(bytes)
        require(handle != 0L) { "Mesin tidak dapat membuka dunia." }
        Engine.close(handle)
        val id = UUID.randomUUID().toString()
        val file = path(context,id)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
        val workshop = h.optBoolean("nativeWorkshop")
        val title = name.trim().take(100).ifBlank { "Dunia impor" }
        val meta = JSONObject().put("name",title).put("city",city).put("workshop",workshop)
        if (owner != null) meta.put("hostOwner",owner)
        check(prefs(context).edit().putString(id,meta.toString()).commit()) { "Indeks arsip gagal disimpan." }
        return Slot(id,title,city,workshop)
    }
}


/** Account metadata is available before the player chooses to download a world. */
internal fun android.content.Context.saveDetails(row: JSONObject): String {
    val parts = mutableListOf("Versi ${row.getInt("version")}")
    row.optLong("size").takeIf { it > 0 }?.let { parts += android.text.format.Formatter.formatShortFileSize(this,it) }
    return parts.joinToString(" · ")
}
