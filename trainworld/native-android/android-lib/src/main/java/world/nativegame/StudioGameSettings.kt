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
        val contrast:Float, val buses:Boolean, val bubbleSize:Float, val bubbleOpacity:Float, val bubbleScheme:String, val bubbleDetail:Int)

    private fun prefs(c:Context) = c.getSharedPreferences("studio-display", Context.MODE_PRIVATE)
    fun read(c:Context):Display = with(prefs(c)) {
        Display(getBoolean("trains",true),getBoolean("capacity",true),getBoolean("fullMarks",true),
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
                    background = StudioUi.card(host, 22)
                    setPadding(dp(12),dp(4),dp(12),dp(6))
                }
                body.addView(group,LinearLayout.LayoutParams(-1,-2))
            }
            fun toggle(key:String,label:String,default:Boolean=true) {
                group!!.addView(Switch(host).apply {
                    text = label
                    setTextColor(StudioUi.INK)
                    typeface=StudioUi.face(host,true)
                    textSize=15f
                    isChecked=prefs.getBoolean(key,default)
                    minHeight=dp(52)
                    thumbTintList = android.content.res.ColorStateList.valueOf(StudioUi.WHITE)
                    trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(StudioUi.LEAF, 0x4d22204a))
                    trackTintMode = android.graphics.PorterDuff.Mode.SRC
                    StudioUi.styleSwitch(this)
                    setOnCheckedChangeListener { _,checked ->
                        prefs.edit().putBoolean(key,checked).apply()
                        changed()
                    }
                })
            }
            fun slider(key:String,label:String,min:Int,max:Int,default:Float=1f) {
                val row=StudioUi.text(host,"",15f,true).apply { setPadding(0,dp(10),0,0) }
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
                group!!.addView(seek,LinearLayout.LayoutParams(-1,dp(44)))
            }
            heading("Tampilan peta")
            toggle("trains","Tampilkan kendaraan")
            toggle("buses","Tampilkan kendaraan bus dan feri")
            toggle("capacity","Tampilkan keterisian kendaraan")
            toggle("fullMarks","Tandai kendaraan penuh")
            toggle("stationNames","Nama stasiun dan halte",false)
            toggle("basemapLabels","Nama jalan dan kawasan")
            toggle("zoomButtons","Tombol perbesar dan perkecil")
            heading("Ukuran dan kontras")
            val schemes = linkedMapOf("mode" to "Campuran moda","ramp" to "Pangsa kereta","colorblind" to "Ramah buta warna")
            lateinit var scheme: android.widget.Button
            scheme = StudioUi.button(host,"") {
                StudioDialogBuilder(host).setTitle("Warna gelembung").setItems(schemes.values.toTypedArray()) { _,index ->
                    prefs.edit().putString("bubbleScheme",schemes.keys.elementAt(index)).apply()
                    changed()
                    scheme.text = "Warna gelembung · " + schemes.values.elementAt(index)
                }.setNegativeButton("Batal",null).show()
            }
            scheme.text = "Warna gelembung · " + (schemes[read(host).bubbleScheme] ?: "Campuran moda")
            scheme.minHeight = dp(48)
            group!!.addView(scheme)
            lateinit var detail: android.widget.Button
            detail = StudioUi.button(host,"") {
                val levels = arrayOf("−1 · Paling renggang","0","1","2 · Paling rapat")
                StudioDialogBuilder(host).setTitle("Detail gelembung").setItems(levels) { _,index ->
                    prefs.edit().putInt("bubbleDetail",index-1).apply()
                    changed()
                    detail.text = "Detail gelembung · " + (index-1)
                }.setNegativeButton("Batal",null).show()
            }
            detail.minHeight = dp(48)
            detail.text = "Detail gelembung · " + read(host).bubbleDetail
            group!!.addView(detail)
            slider("bubbleSize","Ukuran gelembung",25,200)
            slider("bubbleOpacity","Opasitas gelembung",20,100,.7f)
            slider("lineWidth","Ketebalan garis lini",50,200)
            slider("stationCircleSize","Ukuran lingkaran stasiun",50,200)
            slider("basemapContrast","Kontras peta dasar",0,100)
            body.addView(StudioUi.section(host,"Keuangan"))
            val code = StudioCurrency.current(host).code
            body.addView(StudioUi.row(host,StudioUi.Icon.WALLET,StudioUi.TINT_YELLOW,"Mata uang","Ketuk untuk mengganti",
                StudioUi.text(host,code,13f,color = StudioUi.WHITE).apply {
                    typeface = StudioUi.heavy(host)
                    background = StudioUi.pill(host, StudioUi.INK, 16)
                    setPadding(dp(12),dp(6),dp(12),dp(7))
                }) {
                dialog.dismiss()
                StudioCurrency.picker(host,changed)
            })
        }
    }
}
