// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch

internal object StudioGameSettings {
    /** `capacity` and `fullMarks` are the web's switches of the same keys ("Rel penuh kapasitas", "Kereta padat",
     *  CrowdLens.kt); `vehicleLoads` and `fullVehicles` are Android's own: each vehicle's fill and a red rim when packed. */
    data class Display(val trains:Boolean, val capacity:Boolean, val fullMarks:Boolean, val vehicleLoads:Boolean, val fullVehicles:Boolean, val names:Boolean,
        val mapNames:Boolean, val zoomButtons:Boolean, val lineWidth:Float, val circleSize:Float,
        val contrast:Float, val buses:Boolean, val bubbleSize:Float, val bubbleOpacity:Float, val bubbleScheme:String, val bubbleDetail:Int)

    private fun prefs(c:Context) = c.getSharedPreferences("studio-display", Context.MODE_PRIVATE)
    fun read(c:Context):Display = with(prefs(c)) {
        Display(getBoolean("trains",true),getBoolean("capacity",true),getBoolean("fullMarks",true),
            getBoolean("vehicleLoads",true),getBoolean("fullVehicles",true),
            getBoolean("stationNames",false),getBoolean("basemapLabels",true),getBoolean("zoomButtons",true),
            getFloat("lineWidth",1f),getFloat("stationCircleSize",1f),getFloat("basemapContrast",1f),getBoolean("buses",true),
            getFloat("bubbleSize",1f),getFloat("bubbleOpacity",.7f),getString("bubbleScheme","mode") ?: "mode",getInt("bubbleDetail",1))
    }

    fun show(host:Activity, changed:()->Unit) {
        val prefs=prefs(host)
        fun dp(n:Int)=StudioUi.dp(host,n)
        StudioUi.sheet(host,"Tampilan peta") { body,dialog ->
            var group: LinearLayout? = null
            fun heading(label:String) {
                body.addView(StudioUi.section(host,label))
                group = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = StudioUi.card(host, 14)
                    setPadding(dp(12),dp(0),dp(8),dp(2))
                }
                body.addView(group,LinearLayout.LayoutParams(-1,-2))
            }
            fun toggle(key:String,label:String,default:Boolean=true,onToggle:(Boolean)->Unit={}):Switch =
                Switch(host).apply {
                    text = label
                    setTextColor(StudioUi.INK)
                    typeface=StudioUi.face(host,true)
                    textSize=14f
                    isChecked=prefs.getBoolean(key,default)
                    minHeight=dp(48)
                    thumbTintList = android.content.res.ColorStateList.valueOf(StudioUi.WHITE)
                    trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(StudioUi.LEAF, 0x4d22204a))
                    trackTintMode = android.graphics.PorterDuff.Mode.SRC
                    StudioUi.styleSwitch(this)
                    setOnCheckedChangeListener { _,checked ->
                        prefs.edit().putBoolean(key,checked).apply()
                        changed()
                        onToggle(checked)
                    }
                }.also { group!!.addView(it) }
            fun slider(key:String,label:String,min:Int,max:Int,default:Float=1f):List<android.view.View> {
                val row=StudioUi.text(host,"",14f,true).apply { setPadding(0,dp(6),0,0) }
                group!!.addView(row)
                val seek=SeekBar(host).apply {
                    progressTintList = android.content.res.ColorStateList.valueOf(StudioUi.INK)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x3322204a)
                    thumbTintList = android.content.res.ColorStateList.valueOf(StudioUi.INK)
                    StudioUi.styleSeek(this)
                }
                fun update(progress:Int) {
                    val percent=min+progress*5
                    row.text="$label · $percent%"
                    seek.contentDescription=row.text
                }
                seek.max=(max-min)/5
                seek.progress=(Math.round(prefs.getFloat(key,default)*100)-min)/5
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
                group!!.addView(seek,LinearLayout.LayoutParams(-1,dp(36)))
                return listOf(row,seek)
            }
            // the sheet's title already says "Tampilan peta"; the first group is just the map
            heading("Peta")
            toggle("trains","Tampilkan kendaraan")
            toggle("buses","Tampilkan kendaraan bus dan feri")
            toggle("vehicleLoads","Tampilkan keterisian kendaraan")
            toggle("fullVehicles","Tandai kendaraan penuh")
            // web settingsTab.capacity and .crowded: the same keys and meaning as the web's (CrowdLens.kt)
            fun hint(text:String) = group!!.addView(StudioUi.text(host,text,12f,true,StudioUi.MUTED).apply { setPadding(0,0,0,dp(6)) })
            toggle("capacity","Rel penuh kapasitas").contentDescription = "Rel penuh kapasitas. Rel atau stasiun yang tidak muat untuk kereta yang dijadwalkan"
            hint("Rel atau stasiun yang tidak muat untuk kereta yang dijadwalkan")
            toggle("fullMarks","Kereta padat").contentDescription = "Kereta padat. Ruas tempat kereta membawa lebih dari muatan normalnya di jam tersibuk"
            hint("Ruas tempat kereta membawa lebih dari muatan normalnya di jam tersibuk (tampilan Lalu lintas)")
            toggle("stationNames","Nama stasiun dan halte",false)
            toggle("basemapLabels","Nama jalan dan kawasan")
            toggle("zoomButtons","Tombol perbesar dan perkecil")
            // web settingsTab.upClose: the sound switch, its volume shown only while it is on
            heading("Stasiun dari dekat")
            toggle("faces","Wajah penumpang")
            var volume: List<android.view.View> = emptyList()
            val sound = toggle("sound","Suara") { on -> volume.forEach { it.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE } }
            volume = slider("soundVolume","Volume",10,100,.6f)
            if (!sound.isChecked) volume.forEach { it.visibility = android.view.View.GONE }
            heading("Ukuran dan kontras")
            val schemes = linkedMapOf("mode" to "Campuran moda","ramp" to "Pangsa kereta","colorblind" to "Ramah buta warna")
            // a setting picked from a list: one row, its value on the right (was a two-line button)
            fun choiceRow(label: String, value: String, pick: (android.widget.TextView) -> Unit): android.widget.TextView {
                val shown = StudioUi.text(host, value, 13f, true, StudioUi.MUTED)
                group!!.addView(LinearLayout(host).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    isClickable = true
                    background = StudioDepot.touch(host, 0x00ffffff, 10)
                    addView(StudioUi.text(host, label, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(shown)
                    addView(StudioUi.text(host, " ›", 16f, true, StudioUi.MUTED))
                    contentDescription = "$label, $value"
                    setOnClickListener { pick(shown) }
                })
                return shown
            }
            choiceRow("Warna gelembung", schemes[read(host).bubbleScheme] ?: "Campuran moda") { shown ->
                StudioDialogBuilder(host).setTitle("Warna gelembung").setItems(schemes.values.toTypedArray()) { _,index ->
                    prefs.edit().putString("bubbleScheme",schemes.keys.elementAt(index)).apply()
                    changed()
                    shown.text = schemes.values.elementAt(index)
                }.setNegativeButton("Batal",null).show()
            }
            choiceRow("Detail gelembung", read(host).bubbleDetail.toString()) { shown ->
                val levels = arrayOf("−1 · Paling renggang","0","1","2 · Paling rapat")
                StudioDialogBuilder(host).setTitle("Detail gelembung").setItems(levels) { _,index ->
                    prefs.edit().putInt("bubbleDetail",index-1).apply()
                    changed()
                    shown.text = (index-1).toString()
                }.setNegativeButton("Batal",null).show()
            }
            slider("bubbleSize","Ukuran gelembung",25,200)
            slider("bubbleOpacity","Opasitas gelembung",20,100,.7f)
            slider("lineWidth","Ketebalan garis line",50,200)
            slider("stationCircleSize","Ukuran lingkaran stasiun",50,200)
            slider("basemapContrast","Kontras peta dasar",0,100)
            body.addView(StudioUi.section(host,"Keuangan"))
            val code = StudioCurrency.current(host).code
            body.addView(StudioUi.row(host,StudioUi.Icon.WALLET,StudioUi.TINT_YELLOW,"Mata uang","Ketuk untuk mengganti",
                StudioUi.text(host,code,13f,color = StudioUi.WHITE).apply {
                    typeface = StudioUi.heavy(host)
                    background = StudioUi.pill(host, StudioUi.INK, 16)
                    setPadding(dp(10),dp(4),dp(10),dp(4))
                }) {
                dialog.dismiss()
                StudioCurrency.picker(host,changed)
            })
        }
    }
}
