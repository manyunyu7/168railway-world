// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.widget.LinearLayout
import org.json.JSONObject

internal class StudioOperationsGuide(
    private val activity: Activity,
    private val checkpoint: (Int, Int) -> JSONObject,
    private val perform: (String) -> Unit
) {
    private data class Task(val bit: Int, val title: String, val detail: String, val action: String, val button: String)
    private val build = listOf(
        Task(6,"Perbesar peta","Cari kawasan yang ingin kamu layani.","zoom","Perbesar"),
        Task(14,"Baca pembagian moda","Bandingkan jumlah penumpang angkutan umum dan pejalan kaki pada periode yang sama.","summary","Lihat perjalanan"),
        Task(10,"Cari asal perjalanan","Buka lapisan Asal perjalanan. Lingkaran besar menunjukkan konsentrasi perjalanan yang lebih tinggi.","layers","Pilih lapisan"),
        Task(11,"Telusuri perjalanan","Bandingkan asal dan tujuan untuk mencari dua kawasan yang bisa dihubungkan.","layers","Lihat asal dan tujuan"),
        Task(16,"Cari tujuan perjalanan","Pilih Tujuan perjalanan untuk melihat kawasan yang menarik penumpang.","layers","Pilih lapisan"),
        Task(0,"Gambar rel sepanjang 5 km","Pilih jenis rel dan elevasi, lalu ketuk peta untuk menggambar lintas.","rail","Gambar rel"),
        Task(1,"Tempatkan tiga stasiun","Pilih tiga lokasi di atas rel, masing-masing berjarak setidaknya 500 meter.","station","Pasang stasiun"),
        Task(2,"Konfirmasi pembangunan","Tinjau biaya dan konfirmasi ruas serta stasiun sebelum membuat layanan.","rail","Buka alat rel"),
        Task(3,"Buat layanan","Pilih stasiun yang akan dilayani sesuai urutan perjalanan.","line","Buat layanan"),
        Task(4,"Hubungkan tiga pemberhentian","Periksa urutan stasiun pada layanan yang baru dibuat.","services","Buka jalur")
    )
    private val operate = listOf(
        Task(5,"Siapkan rangkaian","Buka layanan lalu alokasikan sedikitnya satu kereta.","services","Atur layanan"),
        Task(13,"Jalankan waktu","Mulai simulasi agar kereta bergerak dan penumpang bisa naik.","play","Jalankan simulasi"),
        Task(7,"Periksa kepadatan","Buka detail layanan dan periksa beban penumpangnya.","services","Lihat layanan"),
        Task(8,"Atur tiga tingkat permintaan","Sediakan sedikitnya satu kereta untuk setiap tingkat permintaan.","services","Atur armada"),
        Task(15,"Baca lalu lintas rel","Pilih warna jalur Lalu lintas untuk melihat ruas yang sering dilalui.","layers","Pilih lapisan"),
        Task(12,"Capai 1% perjalanan dengan angkutan umum","Periksa pembagian moda kota setelah simulasi berjalan.","summary","Lihat perjalanan")
    )
    fun show() = render(checkpoint(-1,0))
    private fun render(state: JSONObject) {
        val page = state.optInt("card",1)
        val tasks = if (page == 2) operate else build.filter { it.bit != 6 || state.optBoolean("zoom",true) }
        val mask = state.optInt("done",0)
        val index = tasks.indexOfFirst { mask and (1 shl it.bit) == 0 }
        StudioUi.sheet(activity, if (page == 2) "Operasikan layanan" else "Bangun lintas pertama") { body,dialog ->
            fun dp(n: Int) = StudioUi.dp(activity,n)
            val complete = tasks.count { mask and (1 shl it.bit) != 0 }
            val task = tasks.getOrNull(index)
            // Step card: "LANGKAH n DARI m", display title, purple progress segments.
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.card(activity, 26, 0xf2ffffff.toInt())
                setPadding(dp(18), dp(16), dp(18), dp(18))
            }
            card.addView(StudioUi.text(activity,
                if (task != null) "LANGKAH ${complete + 1} DARI ${tasks.size}" else "${tasks.size} DARI ${tasks.size} LANGKAH",
                12f, true, StudioUi.BLUE).apply { letterSpacing = .06f })
            card.addView(StudioUi.title(activity, task?.title ?: "Latihan selesai", 22f),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
            card.addView(StudioUi.text(activity, task?.detail ?: "Semua langkah di panduan ini sudah kamu selesaikan.", 15f, true, StudioUi.MUTED).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
            val segments = LinearLayout(activity)
            tasks.forEachIndexed { i, t ->
                val done = mask and (1 shl t.bit) != 0
                segments.addView(android.view.View(activity).apply {
                    background = StudioUi.pill(activity, if (done || i == index) StudioUi.BLUE else 0x1f22204a, 4)
                    alpha = if (i == index && !done) .55f else 1f
                }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (i > 0) marginStart = dp(4) })
            }
            card.addView(segments, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
            body.addView(card)
            if (task != null) {
                body.addView(StudioUi.button(activity,task.button,StudioUi.Tone.BLUE) {
                    dialog.dismiss(); perform(task.action)
                }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            }
            val footer = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            fun textButton(label: String, click: () -> Unit) = StudioUi.text(activity,label,15f,true,StudioUi.MUTED).apply {
                gravity = android.view.Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(8),0,dp(8),0)
                background = StudioDepot.touch(activity, 0x00ffffff, 16)
                isClickable = true
                setOnClickListener { dialog.dismiss(); click() }
            }
            if (task != null) footer.addView(textButton("Lewati langkah") { render(checkpoint(task.bit,page)) })
            footer.addView(android.view.View(activity), LinearLayout.LayoutParams(0,1,1f))
            footer.addView(textButton(if (page == 1) "Panduan operasi →" else "Panduan membangun →") {
                render(checkpoint(-1,if(page == 1) 2 else 1))
            })
            body.addView(footer, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        }
    }
}
