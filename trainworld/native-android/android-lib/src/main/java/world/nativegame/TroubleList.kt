// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import org.json.JSONArray

/** Web TroubleList (T-1433): what is wrong on the network, worst first. */
internal object TroubleList {
    @JvmStatic private external fun query(handle: Long, now: Double): String
    fun read(handle: Long, now: Double): JSONArray =
        if (handle == 0L) JSONArray() else runCatching { JSONArray(query(handle, now)) }.getOrDefault(JSONArray())

    /** Web capacityKinds.ts titles by kind and whether the place reads "di" (at) or "dekat" (near). */
    fun kindWords(kind: Int, tram: Boolean = false): Pair<String, Boolean> = when (kind) {
        1 -> "Rel tunggal" to false; 2 -> (if (tram) "Halte trem" else "Peron") to true; 3 -> "Putar balik" to true
        4 -> "Persimpangan" to false; 5 -> "Persilangan sebidang" to false
        6 -> (if (tram) "Berbalik di halte" else "Berbalik di peron") to true; 7 -> "Rel bersama lini lain" to false
        8 -> "Berbalik memotong rel" to true; 9 -> "Berbalik arah" to true; 10 -> "Sepur simpan jalur balik" to true
        12 -> "Titik gabung" to false; 14 -> "Stasiun ujung" to true; else -> "Rel" to false
    }

    /** Row text and the advice line, after the web's troubleList strings. */
    fun describe(item: org.json.JSONObject): Pair<String, String> {
        val name = item.optString("name")
        return when (item.optInt("tier")) {
            0 -> "$name rusak" to "Rute lini terputus. Sambungkan lagi relnya atau ubah pemberhentiannya."
            1 -> "Berbalik di ${item.optString("place")}" to "Kereta $name berbalik arah di tengah lintasan. Tambah jalur balik atau ubah rute agar lurus."
            2, 4 -> {
                val (title, at) = kindWords(item.optInt("kind"))
                val place = item.optString("place")
                val where = if (place.isEmpty()) title else "$title ${if (at) "di" else "dekat"} $place"
                val wait = item.optDouble("wait")
                val id = java.util.Locale("id")
                val pct = if (item.has("rho")) " · ${(item.optDouble("rho") * 100).toInt()}%" else ""
                (where + pct) to if (wait > 0.01) "${"%.1f".format(id, wait)} kereta $name menunggu karena relnya penuh. Tambah rel ganda, peron, atau kurangi kereta."
                else if (item.optInt("tier") == 4) "Hampir penuh oleh $name. Satu kereta tambahan bisa membuat kereta menunggu; siapkan rel ganda atau peron."
                else "$name dijadwalkan ${"%.0f".format(id, item.optDouble("tph"))} kereta/jam, relnya hanya muat ${"%.0f".format(id, item.optDouble("most"))}. Selebihnya menunggu di ujung."
            }
            6 -> name to "Lini ini belum punya stasiun."
            else -> name to ""
        }
    }
}
