package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object ServicePanel {
    @JvmStatic private external fun services(handle: Long): String
    @JvmStatic private external fun inspect(handle: Long, id: Int, now: Double): String
    @JvmStatic private external fun update(handle: Long, id: Int, now: Double, params: String): String

    fun open(host: Activity, handle: Long, clock: Double, editable: Boolean, changed: (String) -> Unit) {
        val all = JSONArray(services(handle))
        if (all.length() == 0) {
            AlertDialog.Builder(host).setTitle("Layanan").setMessage("Bangun jalur pertama untuk mengatur layanannya.")
                .setPositiveButton("Tutup", null).show()
            return
        }
        val names = Array(all.length()) { all.getJSONObject(it).getString("name") }
        AlertDialog.Builder(host).setTitle("Pilih layanan").setItems(names) { _, index ->
            val id = all.getJSONObject(index).getInt("id")
            details(host, handle, id, clock, editable, changed)
        }.setNegativeButton("Tutup", null).show()
    }

    private fun details(host: Activity, handle: Long, id: Int, clock: Double, editable: Boolean, changed: (String) -> Unit) {
        val service = JSONObject(inspect(handle,id,clock))
        val stations = service.getJSONArray("stops")
        val departures = service.getJSONArray("depart")
        val departureTimes = (0 until departures.length()).joinToString("  ·  ") {
            val t = departures.getDouble(it).toLong()
            "%02d:%02d".format(Locale.ROOT,t/3600%24,t/60%60)
        }
        val summary = buildString {
            append(service.getString("vehicle") + "\n")
            append(String.format(Locale.ROOT,"%.1f km  ·  %d stasiun  ·  %.0f menit per putaran\n",
                service.getDouble("length")/1000,stations.length(),service.getDouble("cycle")/60))
            if (stations.length()>0) {
                append("\nKeberangkatan dari ${stations.getString(0)}\n")
                append(if (departureTimes.isEmpty()) "Belum ada keberangkatan dalam satu jam." else departureTimes)
                append("\n\n")
                append((0 until stations.length()).joinToString("  →  ") { stations.getString(it) })
            }
        }
        val dialog = AlertDialog.Builder(host).setTitle(service.getString("name")).setMessage(summary).setNegativeButton("Tutup", null)
        if (editable) dialog.setPositiveButton("Atur jadwal") { _,_ -> edit(host,handle,id,clock,service,changed) }
        dialog.show()
    }

    private fun edit(host: Activity, handle: Long, id: Int, clock: Double, service: JSONObject, changed: (String) -> Unit) {
        val pad=(18 * host.resources.displayMetrics.density).toInt()
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(pad,pad/2,pad,pad) }
        fun label(text: String) { body.addView(TextView(host).apply {this.text=text;setTypeface(typeface,Typeface.BOLD)}) }
        fun input(title: String, value: String, numeric: Boolean = true):EditText {
            label(title)
            return EditText(host).apply {
                setSingleLine()
                if(numeric)inputType=InputType.TYPE_CLASS_NUMBER
                setText(value)
                body.addView(this)
            }
        }
        val name=input("Nama layanan",service.getString("name"),false)
        label("Jadwal hari kerja")
        val mode=Spinner(host).apply {
            adapter=ArrayAdapter(host,android.R.layout.simple_spinner_dropdown_item,
                listOf("Jumlah kereta", "Selang keberangkatan (detik)"))
            body.addView(this)
        }
        val auto=service.getJSONArray("headway")
        val counts=service.getJSONArray("count")
        val values=listOf("Sibuk","Sedang","Sepi").map {input(it,"0")}
        mode.onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position:Int,itemId:Long) {
                values.forEachIndexed {i,field ->
                    val n=if(position==0) counts.optInt(i,0) else auto.optInt(i,listOf(180,360,720)[i])
                    field.setText(n.toString())
                }
            }
        }
        mode.setSelection(if(auto.length()==3) 1 else 0)
        val weekend=input("Akhir pekan (% armada hari kerja)",service.getInt("weekend").toString())
        val cars=input("Kereta per rangkaian (0 = otomatis)",service.getInt("cars").toString())
        val dwell=input("Berhenti di stasiun (detik)",service.getInt("dwell").toString())
        val turn=input("Balik di terminal (detik)",service.getInt("turn").toString())
        val error=TextView(host).apply {setTextColor(0xffaa342b.toInt());body.addView(this)}
        val scroll=ScrollView(host).apply{addView(body)}
        val dialog=AlertDialog.Builder(host).setTitle("Atur jadwal").setView(scroll)
            .setNegativeButton("Batal",null).setPositiveButton("Terapkan",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val params=JSONObject().put("name",name.text.toString()).put("mode",if(mode.selectedItemPosition==0)"count" else "headway")
            val fields = listOf("peak","day","night","weekend","cars","dwell","turn").zip(values+listOf(weekend,cars,dwell,turn))
            for ((key,field) in fields) {
                val n=field.text.toString().toIntOrNull()
                if (n==null) {field.error="Masukkan angka.";return@setOnClickListener}
                params.put(key,n)
            }
            val result=JSONObject(update(handle,id,clock,params.toString()))
            if (result.has("error")) {error.text=result.getString("error");scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
            else {dialog.dismiss();changed(result.getString("name")); details(host,handle,id,clock,true,changed)}
        }
    }
}
