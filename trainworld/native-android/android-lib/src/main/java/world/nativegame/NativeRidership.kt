package world.nativegame

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.net.URL
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/** Read-only analysis of a world snapshot. Serialize expensive city assignments across screens. */
object NativeRidership {
    private val executor = Executors.newSingleThreadExecutor()
    @JvmStatic external fun tripCells(save: ByteArray, manifest: String, city: String): FloatArray
    @JvmStatic external fun trainLoads(save: ByteArray, manifest: String, time: Double): IntArray?
    @JvmStatic private external fun plan(save: ByteArray, boundaries: String): String
    @JvmStatic private external fun estimate(save: ByteArray, manifest: String, period: Int): String

    internal fun inputs(context: Context, save: ByteArray, cityHeader: File, cells: File, regional: Boolean): JSONObject {
        val local = JSONArray().put(JSONArray().put(cityHeader.path).put(cells.path))
        val markets = JSONArray()
        val files = JSONObject().put("local",local).put("markets",markets)
        if (!regional) return files
        val store = StudioCatalog.get(context)
        val reference = File(context.filesDir,"world-data/${CityPackStore.RELEASE}/reference")
        check(reference.isDirectory || reference.mkdirs())
        fun ids(obj: JSONObject, key: String): List<String> {
            val list = obj.getJSONArray(key)
            return List(list.length()) { list.getString(it) }
        }
        fun coverage(): JSONObject = JSONObject(plan(save,reference.path)).also { check(!it.has("error")) { it.optString("error") } }
        var zones = coverage()
        val missing = ids(zones,"missing")
        for (id in missing) {
            store.city(id)
            val name = "$id.boundary.geojson"
            val tmp = File.createTempFile("boundary-", ".part",reference)
            try {
                val source = URL(CityPackStore.ORIGIN + name).openConnection().apply { connectTimeout=15000; readTimeout=30000 }
                source.getInputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                JSONObject(tmp.readText()).getJSONArray("features")
                check(tmp.renameTo(File(reference,name))) { "Batas wilayah gagal disimpan" }
            } finally { tmp.delete() }
        }
        if (missing.isNotEmpty()) zones = coverage()
        files.put("boundaries",reference.path)
        val home = JSONObject(cityHeader.readText()).getString("city")
        for (id in ids(zones,"local")) {
            if (id != home) local.put(JSONArray().put(store.demandAsset(id,"header.json").path).put(store.demandAsset(id,"cells.bin").path))
        }
        for (id in (ids(zones,"markets") + home).distinct()) markets.put(store.demandAsset(id,"market.json").path)
        val global = RegionalData.load(context.filesDir)
        return files.put("index",global[0].path).put("drives",global[1].path)
    }

    private val periods = listOf("06.00–10.00", "10.00–16.00", "16.00–20.00", "20.00–24.00", "00.00–06.00")

    fun show(activity: Activity, currentSave: ByteArray, cityHeader: File, cells: File, period: Int, includeRegion: Boolean = false) {
        require(period in periods.indices)
        val zone = File(cityHeader.parentFile,"market.json")
        lateinit var body: android.widget.LinearLayout
        val panel = StudioUi.sheet(activity,"Perjalanan kota · "+periods[period]) { column, dialog ->
            body = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            body.addView(android.widget.ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(StudioUi.BLUE)
            },android.widget.LinearLayout.LayoutParams(-1,StudioUi.dp(activity,40)))
            body.addView(StudioUi.text(activity,"Menghitung perjalanan dari penduduk, lokasi kerja, dan jadwal layanan…",15f,true,StudioUi.MUTED).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
            column.addView(body,android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,10) })
            if (!includeRegion && zone.isFile) column.addView(StudioUi.row(activity,StudioUi.Icon.ROUTE,StudioUi.TINT_PURPLE,"Antarwilayah","Hitung juga perjalanan dari luar kota") {
                StudioDialogBuilder(activity).setTitle("Hitung perjalanan antarwilayah?")
                    .setView(StudioDecision(activity,"Data pasar dan rute jalan","Disimpan di perangkat untuk permainan berikutnya.").apply {
                        fact("Data global","17,6 MB ditambah paket wilayah yang dijangkau")
                        fact("Periode",periods[period])
                    }).setNegativeButton("Batal",null).setPositiveButton("Hitung") { _,_ ->
                        dialog.dismiss()
                        show(activity,currentSave,cityHeader,cells,period,true)
                    }.show()
            }, android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,10) })
            column.addView(StudioUi.button(activity,"Tutup") { dialog.dismiss() }, android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,10) })
        }
        val task = executor.submit {
            val result = runCatching {
                val manifest = inputs(activity,currentSave,cityHeader,cells,includeRegion)
                JSONObject(estimate(currentSave,manifest.toString(),period))
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || !panel.isShowing) return@runOnUiThread
                body.removeAllViews()
                val data = result.getOrNull()
                if (data == null || data.has("error")) {
                    val error = data?.optString("error") ?: result.exceptionOrNull()?.localizedMessage.orEmpty()
                    body.addView(StudioUi.row(activity,StudioUi.Icon.HELP,StudioUi.TINT_YELLOW,"Perhitungan belum tersedia",error.ifBlank { null }))
                    return@runOnUiThread
                }
                val numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
                fun n(key: String) = numbers.format(data.getDouble(key))
                val total = data.getDouble("trips")
                val chart = StudioDecision(activity,numbers.format(total)+" perjalanan",
                    if (includeRegion) "Kota dan antarwilayah" else "Perjalanan dalam kota")
                fun mode(key: String,label: String,color: Int) {
                    chart.bar(label,n(key),if(total>0) data.getDouble(key)/total else 0.0,color)
                }
                mode("rail","Angkutan umum",StudioUi.BLUE)
                mode("walk","Jalan kaki",StudioUi.LEAF)
                mode("unserved","Tanpa akses",StudioUi.RED)
                body.addView(chart)
                val network = StudioDecision(activity,"Jaringan angkutan umum",n("lines")+" layanan · "+n("stations")+" stasiun").apply {
                    fact("Komuter",n("commuteRail"))
                    fact("Perjalanan lain",n("outingsRail"))
                    fact("Naik kendaraan, termasuk transit",n("boardings"))
                    if (includeRegion) {
                        fact("Perjalanan kota",numbers.format(total-data.getDouble("regionalTrips")))
                        fact("Perjalanan antarwilayah",n("regionalTrips"))
                        fact("Transit antarwilayah",n("regionalRail"))
                    }
                }
                body.addView(network,android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,8) })
                body.announceForAccessibility("Ringkasan perjalanan selesai dihitung")
            }
        }
        panel.setOnDismissListener { task.cancel(false) }
    }
}
