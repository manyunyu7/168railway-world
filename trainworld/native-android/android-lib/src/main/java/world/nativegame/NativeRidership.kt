package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/** Read-only analysis of a world snapshot. Serialize expensive city assignments across screens. */
object NativeRidership {
    private val executor = Executors.newSingleThreadExecutor()
    @JvmStatic private external fun estimate(save: ByteArray, header: ByteArray, cells: ByteArray, period: Int): String

    private val periods = listOf("06.00–10.00", "10.00–16.00", "16.00–20.00", "20.00–24.00", "00.00–06.00")

    fun show(activity: Activity, currentSave: ByteArray, cityHeader: File, cells: File, period: Int) {
        require(period in periods.indices)
        val panel = AlertDialog.Builder(activity)
            .setTitle("Komuter · " + periods[period])
            .setMessage("Menghitung perjalanan dari penduduk, lokasi kerja, dan jadwal layanan…")
            .setPositiveButton("Tutup", null).show()
        val task = executor.submit {
            val message = try {
                val result = JSONObject(estimate(currentSave, cityHeader.readBytes(), cells.readBytes(), period))
                if (result.has("error")) result.getString("error") else {
                    val numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
                    fun n(key: String) = numbers.format(result.getDouble(key))
                    listOf(
                        "Perjalanan komuter: " + n("trips"),
                        "Naik angkutan umum: " + n("rail"),
                        "Berjalan kaki: " + n("walk"),
                        "Tanpa akses: " + n("unserved"),
                        "Jumlah naik kendaraan termasuk transit: " + n("boardings"),
                        "Jaringan: " + n("lines") + " layanan, " + n("stations") + " stasiun"
                    ).joinToString("\n")
                }
            } catch (e: Exception) {
                "Perhitungan belum tersedia. " + (e.localizedMessage ?: "")
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && panel.isShowing) panel.setMessage(message)
            }
        }
        panel.setOnDismissListener { task.cancel(false) }
    }
}
