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

    /** Red raised destructive button: #E5484D face over a #B02E33 edge. */
    fun danger(button: android.widget.Button) {
        val c = button.context
        fun layers(pressed: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
            StudioUi.panel(c, 0xffb02e33.toInt(), 20, 0xffb02e33.toInt()),
            StudioUi.panel(c, StudioUi.RED, 20, StudioUi.RED))).apply {
            setLayerInset(1, 0, if (pressed) StudioUi.dp(c, 4) else 0, 0, if (pressed) 0 else StudioUi.dp(c, 5))
        }
        button.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layers(true))
            addState(intArrayOf(), layers(false))
        }
        button.tag = "danger"
        button.setTextColor(StudioUi.WHITE)
    }

    fun show(activity: Activity, handle: Long, clock: Double, commit: (Int, Int, String) -> Unit) {
        val kinds = arrayOf("Layanan", "Stasiun atau peron")
        StudioUi.sheet(activity, "Bongkar") { column, menu ->
            column.addView(StudioUi.text(activity, "Pilih yang ingin dibongkar. Dampaknya ditampilkan sebelum dikonfirmasi.", 14f, true, StudioUi.MUTED))
            kinds.forEachIndexed { kind, label ->
                column.addView(StudioUi.row(activity, if (kind == 0) StudioUi.Icon.ROUTE else StudioUi.Icon.PIN, StudioUi.TINT_RED, label,
                    if (kind == 0) "Hapus layanan beserta jadwalnya" else "Bongkar peron atau stasiun") {
                    menu.dismiss(); choose(activity, handle, clock, kinds, commit, kind)
                }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(activity, 8) })
            }
        }
    }

    private fun choose(activity: Activity, handle: Long, clock: Double, kinds: Array<String>, commit: (Int, Int, String) -> Unit, kind: Int) {
                val rows = JSONArray(targets(handle, kind))
                val choices = List(rows.length()) { rows.getJSONObject(it) }
                    .sortedBy { it.getString("name") }
                if (choices.isEmpty()) {
                    Toast.makeText(activity, "Belum ada objek yang bisa dibongkar.", Toast.LENGTH_LONG).show()
                    return
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
                            val confirm = StudioDialogBuilder(activity).setTitle("Bongkar ${labels[i]}?")
                                .setView(impact(activity,kind,preview))
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Bongkar") { _, _ -> commit(kind, id, labels[i]) }
                                .show()
                            danger(confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
                        }
                    }.setNegativeButton("Batal", null).show()
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
