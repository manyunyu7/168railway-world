// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

internal object DemolitionPanel {
    @JvmStatic private external fun targets(handle: Long, kind: Int): String
    @JvmStatic external fun remove(handle: Long, kind: Int, id: Int, clock: Double, commit: Boolean): String

    fun show(activity: Activity, handle: Long, clock: Double, commit: (Int, Int, String) -> Unit) {
        val kinds = arrayOf("Layanan", "Stasiun atau peron")
        StudioDialogBuilder(activity).setTitle("Bongkar")
            .setItems(kinds) { _, kind ->
                val rows = JSONArray(targets(handle, kind))
                val choices = List(rows.length()) { rows.getJSONObject(it) }
                    .sortedBy { it.getString("name") }
                if (choices.isEmpty()) {
                    Toast.makeText(activity, "Belum ada objek yang bisa dibongkar.", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                val labels = choices.map { row ->
                    val name = row.getString("name").ifBlank { kinds[kind] }
                    "$name · #${row.getInt("id")}"
                }
                StudioDialogBuilder(activity).setTitle(kinds[kind])
                    .setItems(labels.toTypedArray()) { _, i ->
                        val id = choices[i].getInt("id")
                        val preview = JSONObject(remove(handle, kind, id, clock, false))
                        if (preview.has("error")) {
                            Toast.makeText(activity, preview.getString("error"), Toast.LENGTH_LONG).show()
                        } else {
                            StudioDialogBuilder(activity).setTitle("Bongkar ${labels[i]}?")
                                .setView(impact(activity,kind,preview))
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Bongkar") { _, _ -> commit(kind, id, labels[i]) }
                                .show()
                        }
                    }.setNegativeButton("Batal", null).show()
            }.setNegativeButton("Tutup", null).show()
    }

    fun impact(activity: Activity, kind: Int, preview: JSONObject): StudioDecision {
        val affected = preview.getJSONArray("affected")
        val cashChange = preview.getDouble("cashChange")
        return StudioDecision(activity,"Dampak pembongkaran",when(kind) { 0 -> "Layanan dan jadwalnya akan dihapus."; 1 -> "Peron yang dipilih akan dibongkar."; else -> "Ruas rel yang dipilih akan dihapus dari jaringan." }).apply {
            val amount = StudioCurrency.current(activity).compact(abs(cashChange))
            fact(if(cashChange<0) "Biaya" else "Pengembalian dana",amount)
            fact("Layanan terdampak",affected.length().toString())
            for (i in 0 until affected.length()) {
                val line = affected.getJSONObject(i)
                val effect = when (line.getString("effect")) {
                    "removed" -> "Dihapus"
                    "broken" -> "Rute perlu diperbaiki"
                    else -> "Kehilangan pemberhentian"
                }
                fact(line.getString("name"),effect)
            }
        }
    }
}
