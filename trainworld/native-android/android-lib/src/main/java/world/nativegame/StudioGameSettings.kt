// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch

internal object StudioGameSettings {
    data class Display(val trains:Boolean, val capacity:Boolean, val fullMarks:Boolean, val names:Boolean,
        val mapNames:Boolean, val zoomButtons:Boolean, val lineWidth:Float, val circleSize:Float,
        val contrast:Float, val buses:Boolean)

    private fun prefs(c:Context) = c.getSharedPreferences("studio-display", Context.MODE_PRIVATE)
    fun read(c:Context):Display = with(prefs(c)) {
        Display(getBoolean("trains",true),getBoolean("capacity",true),getBoolean("fullMarks",true),
            getBoolean("stationNames",true),getBoolean("basemapLabels",true),getBoolean("zoomButtons",true),
            getFloat("lineWidth",1f),getFloat("stationCircleSize",1f),getFloat("basemapContrast",1f),getBoolean("buses",true))
    }

    fun show(host:Activity, changed:()->Unit) {
        val prefs=prefs(host)
        StudioUi.sheet(host,"Pengaturan permainan") { body,dialog ->
            fun heading(label:String) = body.addView(StudioUi.text(host,label,17f,true).apply {
                setPadding(0,StudioUi.dp(host,12),0,StudioUi.dp(host,4))
            })
            fun toggle(key:String,label:String) {
                body.addView(Switch(host).apply {
                    text = label
                    setTextColor(StudioUi.INK)
                    typeface=StudioUi.face(host)
                    textSize=16f
                    isChecked=prefs.getBoolean(key,true)
                    minHeight=StudioUi.dp(host,48)
                    setOnCheckedChangeListener { _,checked ->
                        prefs.edit().putBoolean(key,checked).apply()
                        changed()
                    }
                })
            }
            fun slider(key:String,label:String,min:Int,max:Int) {
                val row=StudioUi.text(host,"",15f,true)
                body.addView(row)
                val seek=SeekBar(host)
                fun update(progress:Int) {
                    val percent=min+progress*5
                    row.text="$label · $percent%"
                    seek.contentDescription=row.text
                }
                seek.max=(max-min)/5
                seek.progress=((prefs.getFloat(key,1f)*100).toInt()-min)/5
                update(seek.progress)
                seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar, progress:Int, fromUser:Boolean) {
                        update(progress)
                        if(fromUser) {
                            prefs.edit().putFloat(key,(min+progress*5)/100f).apply()
                            changed()
                        }
                    }
                    override fun onStartTrackingTouch(bar:SeekBar) {}
                    override fun onStopTrackingTouch(bar:SeekBar) {}
                })
                body.addView(seek,LinearLayout.LayoutParams(-1,StudioUi.dp(host,48)))
            }
            heading("Tampilan peta")
            toggle("trains","Tampilkan kendaraan")
            toggle("buses","Tampilkan kendaraan bus dan feri")
            toggle("capacity","Tampilkan keterisian kendaraan")
            toggle("fullMarks","Tandai kendaraan penuh")
            toggle("stationNames","Nama stasiun dan halte")
            toggle("basemapLabels","Nama jalan dan kawasan")
            toggle("zoomButtons","Tombol perbesar dan perkecil")
            heading("Ukuran dan kontras")
            slider("lineWidth","Ketebalan jalur",50,200)
            slider("stationCircleSize","Ukuran lingkaran stasiun",50,200)
            slider("basemapContrast","Kontras peta dasar",0,100)
            heading("Keuangan")
            body.addView(StudioUi.button(host,"Mata uang · ${StudioCurrency.current(host).code}",icon=StudioUi.Icon.WALLET) {
                dialog.dismiss()
                StudioCurrency.picker(host,changed)
            })
        }
    }
}
