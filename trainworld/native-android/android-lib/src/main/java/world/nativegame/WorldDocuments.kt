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
    fun path(context: Context, id: String) = File(context.filesDir, "world-archive/${UUID.fromString(id)}.save")
    private fun prefs(context: Context) = context.getSharedPreferences("studio-archive-index", 0)
    fun list(context: Context): List<Slot> = prefs(context).all.mapNotNull { (id, value) ->
        runCatching {
            val h = JSONObject(value as String)
            check(path(context,id).isFile)
            Slot(id, h.getString("name"), h.getString("city"), h.optBoolean("workshop"))
        }.getOrNull()
    }.sortedBy { it.title }
    fun import(context: Context, bytes: ByteArray, name: String, city: String): Slot {
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
        check(prefs(context).edit().putString(id,meta.toString()).commit()) { "Indeks arsip gagal disimpan." }
        return Slot(id,title,city,workshop)
    }
}
