package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** Engine calls use the same UI-thread ownership as track editing. */
object NativeFinance {
    @JvmStatic external fun fares(handle: Long, now: Double, patch: String): String
    @JvmStatic private external fun query(handle: Long, now: Double, op: Int): String

    fun show(activity: Activity, handle: Long, clock: () -> Double, persist: () -> Unit) {
        if (handle == 0L) return
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,12) }
        val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply { maximumFractionDigits = 2 }
        val status = TextView(activity)
        layout.addView(status)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(actions)
        fun refresh() {
            val state = JSONObject(query(handle, clock(), 0))
            fun usd(value: Double) = "US$ " + number.format(value) + " juta"
            val run = state.getJSONArray("operating")
            status.text = listOf(
                "Kas: " + usd(state.getDouble("cash")),
                "Perawatan per hari: " + usd(state.getDouble("upkeep")),
                "Operasional per jam (sibuk / sedang / sepi): " + (0..2).joinToString(" / ") { number.format(run.getDouble(it)) } + " juta US$",
                "Sisa pinjaman: " + usd(state.getDouble("outstanding")),
                "Angsuran harian: " + usd(state.getDouble("dailyPayment"))
            ).joinToString("\n")
            actions.removeAllViews()
            if (state.getBoolean("sandbox")) return
            val choices = if (state.getDouble("remainingDays") > 0) listOf(-1) else listOf(1000,5000,10000)
            choices.forEach { op ->
                val label = if (op == -1) "Lunasi pinjaman" else "Pinjam " + usd(op.toDouble())
                actions.addView(Button(activity).apply { text = label; setOnClickListener {
                    AlertDialog.Builder(activity).setMessage(label + "?").setNegativeButton("Batal", null)
                        .setPositiveButton("Konfirmasi") { _,_ ->
                            val result = JSONObject(query(handle,clock(),op))
                            if (result.has("error")) Toast.makeText(activity,result.getString("error"),Toast.LENGTH_LONG).show()
                            else persist()
                            refresh()
                        }.show()
                } })
            }
        }
        refresh()
        AlertDialog.Builder(activity).setTitle("Keuangan").setView(layout).setPositiveButton("Tutup",null).show()
    }
}
