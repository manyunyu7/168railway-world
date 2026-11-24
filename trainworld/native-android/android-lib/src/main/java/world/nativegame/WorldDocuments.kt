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
    /** `at`: when the save file was last written, ms since the epoch (web `slot.at`). */
    data class Slot(val id: String, val title: String, val city: String, val workshop: Boolean, val at: Long = 0)
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
            val file = path(context,id)
            check(file.isFile)
            Slot(id, h.getString("name"), h.getString("city"), h.optBoolean("workshop"), file.lastModified())
        }.getOrNull()
    }.sortedWith(compareByDescending<Slot> { it.at }.thenBy { it.title }) // web saveFile.ts: the most recent first

    /**
     * Web `savedWhen` (SavesPanel.tsx): "Baru saja disimpan", "Disimpan 4 mnt lalu", "Disimpan hari ini
     * pukul 14.05", "Disimpan 12 Okt pukul 14.05".
     */
    fun savedWhen(at: Long, now: Long = System.currentTimeMillis()): String {
        val min = Math.floorDiv(now - at, 60000L)
        if (min < 1) return "Baru saja disimpan"
        if (min < 60) return "Disimpan $min mnt lalu"
        val locale = java.util.Locale.forLanguageTag("id-ID")
        val time = java.text.SimpleDateFormat("HH.mm", locale).format(java.util.Date(at))
        val day = java.text.SimpleDateFormat("yyyyMMdd", locale)
        if (day.format(java.util.Date(at)) == day.format(java.util.Date(now))) return "Disimpan hari ini pukul $time"
        val date = java.text.SimpleDateFormat("d MMM", locale).format(java.util.Date(at)).removeSuffix(".")
        return "Disimpan $date pukul $time"
    }

    /** Web `TYPE_TO_DELETE` (SavesPanel.tsx): from this many stations up, deleting asks for the count typed. */
    const val TYPE_TO_DELETE = 100

    /**
     * Deletes a world from this phone (web: "Hapus … dari browser ini"): its save, cover and index
     * entry, and its link to the account copy, which stays in the account.
     */
    fun delete(context: Context, id: String) {
        val file = path(context,id)
        AtomicFile(file).delete()
        WorldExtras.delete(file)
        check(!file.exists() && !File(file.path + ".bak").exists()) { "Berkas dunia gagal dihapus." }
        WorldCovers.delete(context,id)
        check(prefs(context).edit().remove(id).commit()) { "Indeks arsip gagal disimpan." }
        StudioPendingSaves.forget(context,id)
    }
    /**
     * Web SavesPanel NameField: a saved world's new name, trimmed, at most 80 characters (empty or
     * unchanged left as it is); a world linked to the account takes it there on its next sync.
     */
    fun rename(context: Context, id: String, name: String): Boolean {
        val title = name.trim().take(80)
        val meta = JSONObject(prefs(context).getString(id, null) ?: error("Dunia tidak ditemukan."))
        if (title.isEmpty() || title == meta.optString("name")) return false
        check(prefs(context).edit().putString(id, meta.put("name", title).toString()).commit()) { "Indeks arsip gagal disimpan." }
        StudioPendingSaves.rename(context, id, title)
        return true
    }

    /**
     * Web SavesPanel copy ("Buat salinan"): the save as a new world on this phone named
     * "Salinan {name}" (`copyName`), its cover with it; the copy is not linked to the account.
     */
    fun copy(context: Context, slot: Slot): Slot {
        val bytes = AtomicFile(path(context, slot.id)).openRead().use { read(it) }
        val meta = JSONObject(prefs(context).getString(slot.id,null) ?: error("Dunia tidak ditemukan."))
        val owner = meta.optString("hostOwner").takeIf { it.isNotBlank() }
        val copy = import(context, bytes, "Salinan ${slot.title}", slot.city, owner)
        WorldCovers.copy(context, slot.id, copy.id)
        WorldExtras.copy(path(context, slot.id), path(context, copy.id))
        return copy
    }
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
        return Slot(id,title,city,workshop,file.lastModified())
    }
}


/** Account metadata is available before the player chooses to download a world. */
internal fun android.content.Context.saveDetails(row: JSONObject): String {
    val parts = mutableListOf("Versi ${row.getInt("version")}")
    row.optLong("size").takeIf { it > 0 }?.let { parts += android.text.format.Formatter.formatShortFileSize(this,it) }
    return parts.joinToString(" · ")
}
