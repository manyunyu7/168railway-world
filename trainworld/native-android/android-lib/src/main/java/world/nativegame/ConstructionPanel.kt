// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.InputFilter
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

internal object NativeConstruction {
    @JvmStatic external fun rail(handle: Long, points: DoubleArray, level: Int, kind: Int, tracks: Int, clock: Double, commit: Boolean, designSpeed: Double, flyoverSpeed: Double, autoRamp: Boolean, flying: Boolean, radius: Double, deferred: Boolean, previewOnly: Boolean, profile: DoubleArray): String
    @JvmStatic external fun station(handle: Long, x: Double, y: Double, radius: Double, name: String, platform: Int, clock: Double, commit: Boolean, deferred: Boolean): String
    @JvmStatic external fun buildPlans(handle: Long, clock: Double, commit: Boolean): String
    @JvmStatic external fun platformPoint(handle: Long,x:Double,y:Double,radius:Double,plans:Boolean):String
}

internal enum class BuildTool(val key: String,val label: String,val review: String,val action: String) {
    RAIL("track","Rel","Tinjau rel","Bangun rel"),
    STATION("station","Stasiun","Tinjau stasiun","Bangun stasiun"),
    BUS("bus","Bus","Tinjau bus","Bangun rute bus"),
    FERRY("ferry","Feri","Tinjau feri","Bangun rute feri"),
    LINE("line","Layanan kereta","Tinjau layanan","Buat layanan");
    val minPoints get() = if (this == STATION) 1 else 2
}

internal data class ConstructionPlan(
    val tool: BuildTool, val name: String = "", val level: Int = 1, val kind: Int = 0,
    val tracks: Int = 2, val platform: Int = 160, val vehicle: Int = -1,
    val speed: Double = 160.0, val flySpeed: Double = 80.0, val autoRamp: Boolean = true, val flying: Boolean = false, val snapRoad: Boolean = false, val radius: Double = 0.0, val deferred: Boolean = false, val connect: Boolean = true
) {
    val wireKind get() = if(snapRoad && kind in 0..1) kind+10 else kind
    val grade get() = when { level > 0 -> "Viaduk +$level"; level < 0 -> "Terowongan $level"; else -> "Permukaan" }
    val specification get() = when(tool) {
        BuildTool.RAIL -> listOf(when(kind){ 1 -> "Cepat"; 2 -> "Trem jalan"; 3 -> "Trem"; else -> "Konvensional" },grade,"$tracks sepur").joinToString(" · ")
        BuildTool.STATION -> "$name · peron $platform m"
        else -> name
    }
    fun save(out: Bundle) {
        out.putBoolean("connectRail",connect); out.putDouble("curveRadius",radius); out.putBoolean("deferBuild",deferred)
        out.putString("constructionTool",tool.name)
        out.putString("constructionName",name)
        out.putInt("constructionLevel",level)
        out.putInt("railKind",kind)
        out.putInt("railTracks",tracks)
        out.putInt("platformLength",platform)
        out.putInt("lineVehicle",vehicle)
        out.putDouble("designSpeed",speed); out.putDouble("flySpeed",flySpeed)
        out.putBoolean("autoRamp",autoRamp); out.putBoolean("flying",flying); out.putBoolean("snapRoad",snapRoad)
    }
    companion object {
        fun restore(state: Bundle): ConstructionPlan? {
            val tool = BuildTool.values().find { it.name == state.getString("constructionTool") } ?: return null
            return ConstructionPlan(tool,state.getString("constructionName").orEmpty(),state.getInt("constructionLevel"),
                state.getInt("railKind"),state.getInt("railTracks",2),state.getInt("platformLength",160),state.getInt("lineVehicle",-1),
                state.getDouble("designSpeed",160.0),state.getDouble("flySpeed",80.0),state.getBoolean("autoRamp",true),state.getBoolean("flying",false),state.getBoolean("snapRoad",false),state.getDouble("curveRadius"),state.getBoolean("deferBuild"),state.getBoolean("connectRail",true))
        }
    }
}

/** Each tool keeps its own options while the player is drawing its map geometry. */
internal object ConstructionPanel {
    fun options(host: Activity, initial: ConstructionPlan, selected: (ConstructionPlan) -> Unit) {
        val tool = initial.tool
        var plan = initial
        lateinit var go: Button
        StudioUi.sheet(host,tool.label,pinnedAction={go}) { body, dialog ->
            fun dp(n:Int)=StudioUi.dp(host,n)
            fun label(s:String) { body.addView(StudioUi.section(host,s),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4); bottomMargin=dp(2) }) }
            fun choices(title:String, items:List<Pair<Int,String>>,current:Int,perRow:Int,change:(Int)->Unit): (Int,Boolean)->Unit {
                label(title)
                val buttons=mutableListOf<Pair<Int,Button>>()
                var active=current
                // Segmented pill: the chosen option is a white tab on a soft track.
                fun paint() { buttons.forEach { (id,b) ->
                    b.isSelected=id==active
                    b.background=if(id==active) StudioUi.pill(host,StudioUi.WHITE,14) else android.graphics.drawable.ColorDrawable(0)
                    b.elevation=if(id==active) dp(2).toFloat() else 0f
                    b.setTextColor(if(id==active) StudioUi.INK else StudioUi.MUTED)
                    b.alpha=if(b.isEnabled) 1f else .4f
                } }
                items.chunked(perRow).forEach { part ->
                    val row=LinearLayout(host).apply {
                        background=StudioUi.pill(host,0x1422204a,18)
                        setPadding(dp(4),dp(4),dp(4),dp(4))
                    }
                    part.forEach { (id,text) ->
                        val b=StudioUi.button(host,text) { active=id; change(id); paint() }
                        b.textSize=13f
                        b.minHeight=dp(40); b.minimumHeight=dp(40)
                        b.setPadding(dp(6),0,dp(6),0)
                        buttons.add(id to b)
                        row.addView(b,LinearLayout.LayoutParams(0,dp(40),1f).apply { marginStart=dp(2); marginEnd=dp(2) })
                    }
                    body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                }
                paint()
                return { selected, enabled ->
                    active=selected
                    buttons.forEach { (id, button) -> button.isEnabled=enabled || id==selected }
                    paint()
                }
            }
            val name=if(tool!=BuildTool.RAIL) {
                label(if(tool==BuildTool.STATION) "Nama stasiun" else "Nama layanan")
                EditText(host).apply {
                    setSingleLine(); filters=arrayOf(InputFilter.LengthFilter(48))
                    if(tool==BuildTool.STATION) hint="Otomatis dari lokasi"
                    setText(plan.name); setSelectAllOnFocus(true); textSize=17f; typeface=StudioUi.face(host)
                    setTextColor(StudioUi.INK); setPadding(dp(18),dp(4),dp(18),dp(4)); minHeight=dp(52)
                    background=StudioUi.pill(host,StudioUi.WHITE,26)
                    body.addView(this,LinearLayout.LayoutParams(-1,-2))
                }
            } else null
            if(tool in setOf(BuildTool.RAIL,BuildTool.STATION)) choices("Pembangunan",listOf(0 to "Bangun sekarang",1 to "Simpan rencana"),if(plan.deferred) 1 else 0,2) { plan=plan.copy(deferred=it==1) }
            var radiusField:EditText?=null
            var designField:EditText?=null
            var flyField:EditText?=null
            fun speedInput(title:String, value:Double, parent:LinearLayout): EditText {
                parent.addView(StudioUi.text(host,title,13f,true,StudioUi.MUTED).apply { setPadding(dp(4),dp(8),0,dp(4)) })
                return EditText(host).apply {
                    inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(); setText(value.toString().removeSuffix(".0")); setSelectAllOnFocus(true)
                    typeface=StudioUi.face(host,true); setTextColor(StudioUi.INK); minHeight=dp(48); setPadding(dp(16),0,dp(16),0)
                    background=StudioUi.pill(host,StudioUi.WHITE,24); parent.addView(this)
                }
            }
            when(tool) {
                BuildTool.RAIL -> {
                    choices("Sambungan ujung",listOf(1 to "Sambung ke rel",0 to "Ujung bebas"),if(plan.connect) 1 else 0,2) { plan=plan.copy(connect=it==1) }
                    var levelControl: (Int,Boolean)->Unit = { _,_ -> }
                    var trackControl: (Int,Boolean)->Unit = { _,_ -> }
                    var roadOption:android.widget.CheckBox?=null
                    choices("Jenis rel",listOf(0 to "Biasa",1 to "Cepat",3 to "Trem",2 to "Trem jalan"),plan.kind,2) { next ->
                        if(plan.kind!=next && (next==1 || plan.kind==1)) designField?.setText(if(next==1) "320" else "160")
                        plan=plan.copy(kind=next,
                            level=if(next==2 || next==3 && plan.kind!=next) 0 else plan.level,
                            tracks=if(next==2) 2 else plan.tracks)
                        roadOption?.visibility=if(next in 0..1) android.view.View.VISIBLE else android.view.View.GONE
                        levelControl(plan.level,next!=2)
                        trackControl(plan.tracks,next!=2)
                    }
                    levelControl=choices("Elevasi",listOf(0 to "Permukaan",1 to "Jembatan +1",2 to "Viaduk +2",3 to "Viaduk +3",
                        -1 to "Terowongan −1",-2 to "Terowongan −2",-3 to "Terowongan −3"),plan.level, 2) { plan=plan.copy(level=it) }
                    trackControl=choices("Jumlah sepur",listOf(1 to "Tunggal",2 to "Ganda",4 to "Empat"),plan.tracks,3) { plan=plan.copy(tracks=it) }
                    levelControl(plan.level,plan.kind!=2)
                    trackControl(plan.tracks,plan.kind!=2)
                    val advanced=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL; visibility=android.view.View.GONE }
                    body.addView(StudioUi.button(host,"Pengaturan lanjutan") {
                        advanced.visibility=if(advanced.visibility==android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                    })
                    body.addView(advanced)
                    radiusField=speedInput("Radius lengkung (m, 0 = otomatis)",plan.radius,advanced)
                    designField=speedInput("Kecepatan desain (km/jam)",plan.speed,advanced)
                    flyField=speedInput("Kecepatan persilangan (km/jam)",plan.flySpeed,advanced)
                    fun toggle(text:String, checked:Boolean, change:(Boolean)->Unit): android.widget.CheckBox {
                        return android.widget.CheckBox(host).apply {
                            this.text=text; typeface=StudioUi.face(host,true); setTextColor(StudioUi.INK); minHeight=dp(48); isChecked=checked
                            buttonTintList=android.content.res.ColorStateList.valueOf(StudioUi.LEAF)
                            setOnCheckedChangeListener { _,on -> change(on) }
                            advanced.addView(this)
                        }
                    }
                    roadOption=toggle("Ikuti jalan utama",plan.snapRoad) { plan=plan.copy(snapRoad=it) }
                    roadOption.visibility=if(plan.kind in 0..1) android.view.View.VISIBLE else android.view.View.GONE
                    toggle("Ramp otomatis",plan.autoRamp) { plan=plan.copy(autoRamp=it) }
                    toggle("Persilangan layang",plan.flying) { plan=plan.copy(flying=it) }
                }
                BuildTool.STATION -> {
                    label("Panjang peron")
                    val row=LinearLayout(host).apply {
                        gravity=Gravity.CENTER_VERTICAL
                        background=StudioUi.card(host,24)
                        setPadding(dp(8),dp(8),dp(8),dp(8))
                    }
                    val value=StudioUi.title(host,"",22f).apply { gravity=Gravity.CENTER }
                    lateinit var minus: Button; lateinit var plus: Button
                    fun update(n:Int) {
                        plan=plan.copy(platform=n.coerceIn(20,400))
                        value.text="${plan.platform} m"
                        minus.isEnabled=plan.platform>20
                        plus.isEnabled=plan.platform<400
                    }
                    minus=StudioUi.iconButton(host,"Perpendek peron",StudioUi.Icon.MINUS) { update(plan.platform-20) }
                    plus=StudioUi.iconButton(host,"Perpanjang peron",StudioUi.Icon.PLUS) { update(plan.platform+20) }
                    row.addView(minus,LinearLayout.LayoutParams(dp(52),dp(52))); row.addView(value,LinearLayout.LayoutParams(0,-2,1f)); row.addView(plus,LinearLayout.LayoutParams(dp(52),dp(52)))
                    body.addView(row); update(plan.platform)
                }
                BuildTool.LINE -> choices("Jenis kereta",listOf(-1 to "Otomatis",0 to "Komuter",1 to "Cepat",4 to "Trem"),plan.vehicle,2) { plan=plan.copy(vehicle=it) }
                BuildTool.BUS, BuildTool.FERRY -> Unit
            }
            go=StudioUi.button(host,when(tool) { BuildTool.STATION -> "Pilih lokasi"; BuildTool.LINE -> "Pilih stasiun"; else -> "Gambar di peta" },StudioUi.Tone.PRIMARY) {
                fun speed(field:EditText):Double? {
                    val value=field.text.toString().replace(',','.').toDoubleOrNull()
                    if(value!=null && value in 20.0..320.0) return value
                    (field.parent as android.view.View).visibility=android.view.View.VISIBLE
                    field.error="Masukkan kecepatan 20–320 km/jam."; field.requestFocus()
                    return null
                }
                if(tool==BuildTool.RAIL) {
                    val design=speed(requireNotNull(designField)) ?: return@button
                    val fly=speed(requireNotNull(flyField)) ?: return@button
                    val radius=radiusField?.text.toString().replace(',','.').toDoubleOrNull()
                    if(radius==null || radius !in 0.0..100000.0) { radiusField?.let { (it.parent as android.view.View).visibility=android.view.View.VISIBLE; it.error="Masukkan radius 0–100.000 m."; it.requestFocus() }; return@button }
                    plan=plan.copy(speed=design,flySpeed=fly,radius=radius)
                }
                val n=name?.text?.toString()?.trim().orEmpty()
                if(name!=null && n.isEmpty() && tool!=BuildTool.STATION) { name.error="Isi nama terlebih dahulu."; name.requestFocus() }
                else { dialog.dismiss(); selected(plan.copy(name=n)) }
            }
        }
    }
}
