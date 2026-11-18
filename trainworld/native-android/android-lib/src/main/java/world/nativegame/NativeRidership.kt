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
    @JvmStatic private external fun estimate(save: ByteArray, header: String, cells: String, period: Int, index: String?, drives: String?, zone: String?): String

    private val periods = listOf("06.00–10.00", "10.00–16.00", "16.00–20.00", "20.00–24.00", "00.00–06.00")

    fun show(activity: Activity, currentSave: ByteArray, cityHeader: File, cells: File, period: Int, includeRegion: Boolean = false) {
        require(period in periods.indices)
        val builder = AlertDialog.Builder(activity)
            .setTitle("Perjalanan kota · " + periods[period])
            .setMessage("Menghitung perjalanan dari penduduk, lokasi kerja, dan jadwal layanan…")
            .setPositiveButton("Tutup", null)
        val zone = File(cityHeader.parentFile,"market.json")
        if (!includeRegion && zone.isFile) builder.setNeutralButton("Antarwilayah") { _,_ ->
            AlertDialog.Builder(activity).setMessage("Indeks pasar dan rute jalan berukuran 17,6 MB dan disimpan untuk permainan berikutnya. Hitung perjalanan antarwilayah?").setNegativeButton("Batal",null).setPositiveButton("Hitung") { _,_ -> show(activity,currentSave,cityHeader,cells,period,true) }.show()
        }
        val panel=builder.show()
        val task = executor.submit {
            val message = try {
                val files = if (includeRegion) RegionalData.load(activity.filesDir) else emptyList()
                val result = JSONObject(estimate(currentSave,cityHeader.path,cells.path,period,files.getOrNull(0)?.path,files.getOrNull(1)?.path,zone.path))
                if (result.has("error")) result.getString("error") else {
                    val numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
                    fun n(key: String) = numbers.format(result.getDouble(key))
                    listOf(
                        "Perjalanan kota: " + numbers.format(result.getDouble("trips")-result.optDouble("regionalTrips",0.0)),
                        "Naik angkutan umum: " + n("rail"),
                        "Komuter naik angkutan umum: " + n("commuteRail"),
                        "Perjalanan lain naik angkutan umum: " + n("outingsRail"),
                        "Berjalan kaki: " + n("walk"),
                        "Tanpa akses: " + n("unserved"),
                        if(includeRegion) "Pasar seluruh dunia: " + n("regionalTrips") + " perjalanan, " + n("regionalRail") + " naik transit pada jaringan terbuka" else "",
                        "Jumlah naik kendaraan termasuk transit: " + n("boardings"),
                        "Jaringan: " + n("lines") + " layanan, " + n("stations") + " stasiun"
                    ).filter { it.isNotEmpty() }.joinToString("\n")
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
