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
            val complete = tasks.count { mask and (1 shl it.bit) != 0 }
            val task = tasks.getOrNull(index)
            body.addView(StudioDecision(activity,task?.title ?: "Latihan selesai", "$complete dari ${tasks.size} langkah").apply {
                if (task != null) addView(StudioUi.text(activity,task.detail,16f))
            })
            fun button(label: String, primary: Boolean = false, click: () -> Unit) {
                body.addView(StudioUi.button(activity,label,if(primary) StudioUi.Tone.PRIMARY else StudioUi.Tone.SURFACE) {
                    dialog.dismiss(); click()
                }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(activity,8) })
            }
            if (task != null) {
                button(task.button,true) { perform(task.action) }
                button("Tandai langkah ini selesai") { render(checkpoint(task.bit,page)) }
            }
            button(if (page == 1) "Panduan operasi layanan" else "Panduan membangun") {
                render(checkpoint(-1,if(page == 1) 2 else 1))
            }
        }
    }
}
