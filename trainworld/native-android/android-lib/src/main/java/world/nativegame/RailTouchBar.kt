// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.PopupMenu

/** Track settings stay on the map; changing elevation updates the waiting end only. */
internal class RailTouchBar(val host: Activity, val change: (ConstructionPlan) -> Unit): LinearLayout(host) {
    private var spec=ConstructionPlan(BuildTool.RAIL)
    private val kinds=listOf("Rel biasa","Rel cepat","Trem jalan","Trem")
    private fun choice(label:String, items:()->List<Pair<String,ConstructionPlan>>) =
        StudioUi.button(host,label) { }.also { button ->
            button.textSize=12f
            button.setPadding(0,0,0,0)
            addView(button, LayoutParams(0,StudioUi.dp(host,46),1f))
            button.setOnClickListener {
                val choices=items()
                PopupMenu(host,button).apply {
                    choices.forEachIndexed { i,pair -> menu.add(0,i,0,pair.first) }
                    setOnMenuItemClickListener { change(choices[it.itemId].second); true }
                }.show()
            }
        }
    private val elevation=choice("Level") {
        (if(spec.kind==2) 0..0 else -3..3).map { z -> val next=spec.copy(level=z); next.grade to next } + if(spec.kind<2) listOf(
            (if(spec.autoRamp) "✓ Ramp otomatis" else "Ramp otomatis") to spec.copy(autoRamp=!spec.autoRamp)
        ) else emptyList()
    }
    private val kind=choice("Jenis") {
        kinds.mapIndexed { i,label -> label to spec.copy(kind=i,tracks=if(i>=2) 2 else spec.tracks,
            level=if(i==2) 0 else spec.level,speed=if(i==1) 320.0 else 160.0) } +
            if(spec.kind<2) listOf((if(spec.snapRoad) "✓ Ikuti jalan utama" else "Ikuti jalan utama") to spec.copy(snapRoad=!spec.snapRoad))
            else emptyList()
    }
    private val tracks=choice("Sepur") {
        (if(spec.kind==2) listOf(2) else if(spec.kind==3) listOf(1,2) else listOf(1,2,4))
            .map { n -> "$n sepur" to spec.copy(tracks=n) }
    }
    private val speed=StudioUi.button(host,"Kecepatan") {}.also { button ->
        button.textSize=12f
        button.setPadding(0,0,0,0)
        addView(button,LayoutParams(0,StudioUi.dp(host,46),1f))
        button.setOnClickListener {
            val panel=LinearLayout(host).apply {
                orientation=VERTICAL
                val pad=StudioUi.dp(host,12)
                setPadding(pad,pad,pad,pad)
                background=StudioUi.card(host,16)
            }
            val popup=PopupWindow(panel,StudioUi.dp(host,240),LayoutParams.WRAP_CONTENT,true)
            fun input(label:String,value:Double):EditText {
                panel.addView(StudioUi.text(host,label,14f,true,StudioUi.INK))
                return EditText(host).apply {
                    inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(); setSelectAllOnFocus(true); setText(value.toString().removeSuffix(".0"))
                    contentDescription=label
                    panel.addView(this)
                }
            }
            val design=input("Kecepatan desain (km/jam)",spec.speed)
            val fly=input("Kecepatan persilangan (km/jam)",spec.flySpeed)
            fun submit() {
                fun speedOf(field:EditText):Double? {
                    val v=field.text.toString().replace(',', '.').toDoubleOrNull()
                    if(v!=null && v in 20.0..320.0) return v
                    field.error="Masukkan 20–320 km/jam"; field.requestFocus()
                    return null
                }
                val designSpeed=speedOf(design) ?: return
                val flySpeed=speedOf(fly) ?: return
                popup.dismiss(); change(spec.copy(speed=designSpeed,flySpeed=flySpeed))
            }
            fly.imeOptions=EditorInfo.IME_ACTION_DONE
            fly.setOnEditorActionListener { _,id,_ -> if(id==EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
            panel.addView(StudioUi.button(host,"Terapkan") { submit() })
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.isOutsideTouchable=true
            popup.showAsDropDown(button)
        }
    }
    fun display(value:ConstructionPlan) {
        spec=value
        elevation.text="Level ${value.level}"
        kind.text=kinds[value.kind]
        tracks.text="${value.tracks} sepur"
        speed.text="${value.speed.toInt()} km/j"
        speed.contentDescription="Kecepatan desain ${value.speed.toInt()} km/jam"
    }
}
