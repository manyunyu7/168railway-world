// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object StationInspector {
    /** Daily riders for a station node and its share of the busiest stop, when the passenger layer has data. */
    var riders: ((Int) -> Pair<Int, Float>?)? = null
    /** The simulation's riders at a station complex (train_riders.rs `station`); null before they are in. */
    var passengers: ((Int, (String?) -> Unit) -> Unit)? = null
    /** Whether a simulation is open or being prepared (so the riders card can say Putar starts the count). */
    var simulating: (() -> Boolean)? = null
    /** The map the station design draws on (StationDesign.kt); without it the old sheets open. */
    var map: NetworkView? = null
    @JvmStatic external fun structure(handle: Long, node: Int, clock: Double, command: String): String
    @JvmStatic external fun upgrade(handle:Long,node:Int,bit:Int,on:Int,clock:Double):String
    @JvmStatic external fun read(handle: Long, node: Int, line: Int, run: Int, clock: Double): String
    /**
     * Removes a station (a platform node) as the demolition panel commits one: the web inspector's
     * "Hapus stasiun" (StationInspector.tsx `remove`). The button shows only while it is set; the
     * host records the undo step and redraws (`commitDemolition(1, node, label)`).
     */

    /** Web `levelLabel` (format.ts): "+1", "0", "−2". */
    private fun levelLabel(l: Int) = if (l > 0) "+$l" else if (l < 0) "−${-l}" else "0"
    /** Web `levelName` (format.ts LEVEL_NAMES). */
    private fun levelName(l: Int) = when {
        l >= 3 -> "Viaduk tinggi"; l > 0 -> "Viaduk"; l == 0 -> "Permukaan tanah"
        l == -1 -> "Galian terbuka"; l == -2 -> "Terowongan"; else -> "Terowongan dalam"
    }
    /** Web `CAR_M` (boarding.ts): a car's length along a platform. */
    private const val CAR_M = 20.0
    /** Web upgrade names and what each does (id.json `upgrades.*`), by facility bit. */
    internal val UPGRADE_NAMES = mapOf(1 to "Jalur balik", 2 to "Tempat parkir", 4 to "Ruang tunggu", 8 to "Rel penyusulan")
    internal val UPGRADE_DOES = mapOf(
        1 to "Kereta yang berbalik di sini memakai kedua peron. Kapasitas putar balik naik dari 15 kereta per jam jadi 30.",
        2 to "Datang ke stasiun ini dari jauh terasa 10 menit lebih singkat.",
        4 to "Menunggu kereta di sini terasa 20% lebih singkat.",
        8 to "Kereta yang lebih cepat bisa menyusul kereta lebih lambat yang berhenti di sini.",
    )
    private fun JSONArray.rows() = List(length()) { getJSONObject(it) }
    private fun clock(seconds: Double): String {
        val minute = Math.floorMod(kotlin.math.floor(seconds/60).toLong(),1440L)
        return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60)
    }
    /** Coloured line dot used beside departures and route rows. */
    private fun dot(activity: Activity, color: Int, size: Int = 12) = android.view.View(activity).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.BLACK or color)
        }
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(StudioUi.dp(activity, size), StudioUi.dp(activity, size))
    }
    private class Sheet(val activity: Activity,val title: String?) {
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
        }
        private var dialog: android.app.Dialog?=null
        fun text(value: String, heading: Boolean=false) {
            root.addView(if (heading) StudioUi.section(activity,value) else StudioUi.text(activity,value,14f,true,StudioUi.MUTED).apply {
                setPadding(StudioUi.dp(activity,4),StudioUi.dp(activity,4),StudioUi.dp(activity,4),StudioUi.dp(activity,4))
            })
        }
        fun add(view: android.view.View, top: Int = 8) =
            root.addView(view, LinearLayout.LayoutParams(view.layoutParams?.width ?: -1, -2).apply { topMargin = StudioUi.dp(activity,top) })
        /** A white glass row; [color] shows a line dot, [icon] a drawable tile. */
        fun action(value: String,color: Int?=null,icon: Int?=null,subtitle: String?=null,trailing: android.view.View?=null,onClick: ()->Unit) {
            val row = StudioUi.row(activity,null,0,value,subtitle,trailing) { dialog?.dismiss(); onClick() }
            when {
                icon != null -> row.addView(android.widget.ImageView(activity).apply {
                    setImageResource(icon)
                    background = StudioUi.panel(activity,StudioUi.TINT_BLUE,12,StudioUi.TINT_BLUE)
                    val pad = StudioUi.dp(activity,7); setPadding(pad,pad,pad,pad)
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, 0, LinearLayout.LayoutParams(StudioUi.dp(activity,38),StudioUi.dp(activity,38)))
                color != null -> row.addView(dot(activity,color).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = StudioUi.dp(activity,6)
                }, 0)
            }
            add(row,6)
        }
        fun dismiss() { dialog?.dismiss() }
        fun show() {
            dialog = StudioUi.sheet(activity,title ?: "",heightFraction = if (title == null) .6f else .78f) { column, _ -> column.addView(root) }
        }
    }
    /** Dark price pill for facility rows, green when the facility is already installed. */
    private fun priceTag(activity: Activity, label: String, installed: Boolean) =
        StudioUi.text(activity,label,13f,true,if (installed) StudioUi.GOOD else StudioUi.WHITE).apply {
            background = StudioUi.pill(activity,if (installed) StudioUi.TINT_GREEN else StudioUi.INK,19)
            setPadding(StudioUi.dp(activity,12),StudioUi.dp(activity,8),StudioUi.dp(activity,12),StudioUi.dp(activity,8))
        }
    fun show(activity: Activity,handle: Long,node: Int,time: Double, transaction: PanelEdit, changed: () -> Unit) {
        val payload=read(handle,node,-1,0,time)
        if(payload=="null") return
        val station=JSONObject(payload)
        val sheet=Sheet(activity,null)
        val platforms=station.getJSONArray("platforms").rows()
        val lines = station.getJSONArray("lines").rows()
        val byId = lines.associateBy { it.getInt("id") }
        val departures = station.getJSONArray("departures").rows()
        fun dp(n: Int) = StudioUi.dp(activity,n)
        // Header: thick blue station tile, name in display type, platforms and services.
        val header = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(StudioDepot.symbol(activity,StudioUi.Icon.TRAIN,20f,StudioUi.WHITE).apply {
            background = StudioUi.panel(activity,0xff3e7bfa.toInt(),12,0xff3e7bfa.toInt())
        }, LinearLayout.LayoutParams(dp(40),dp(40)))
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        names.addView(StudioUi.title(activity,station.getString("name"),20f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // web: "Stasiun, level +1" in Indonesian words, or "Kompleks 3 stasiun" for a complex
        val here = platforms.firstOrNull { it.getInt("id") == node } ?: platforms.firstOrNull()
        names.addView(StudioUi.text(activity,
            if (platforms.size > 1) "Kompleks ${platforms.size} stasiun" else "Stasiun · ${levelName(here?.getInt("level") ?: 0)} (tingkat ${levelLabel(here?.getInt("level") ?: 0)})",
            12f,true,StudioUi.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        header.addView(names, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(10) })
        sheet.add(header,0)
        // the station's actions in one row of small equal buttons under the name (filled further down)
        val bar = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun barAction(label: String, icon: StudioUi.Icon, ink: Int = StudioUi.INK, description: String = label, click: () -> Unit) =
            StudioUi.text(activity, label, 13f, true, ink).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = StudioUi.panel(activity, StudioUi.WHITE, 12)
                minHeight = dp(40)
                setPadding(dp(10), 0, dp(12), 0)
                compoundDrawablePadding = dp(4)
                setCompoundDrawablesRelative(StudioUi.glyph(activity, icon, ink), null, null, null)
                isClickable = true; isFocusable = true
                contentDescription = description
                setOnClickListener { click() }
                bar.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); topMargin = dp(4); bottomMargin = dp(4) })
            }
        sheet.add(android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(bar) }, 4)
        // riders a day on, off and changing here, always at the top (StationRiders.kt)
        sheet.add(StationRiders.section(activity, node, passengers, simulating), 6)
        // web NameEdit: "Ganti nama" beside the name (a tap on the name too), the station tapped
        val rename = {
            sheet.dismiss()
            rename(activity,handle,node,station.getString("name"),time,transaction) { renamed ->
                if (renamed) changed()
                show(activity,handle,node,time,transaction,changed)
            }
        }
        (names.getChildAt(0) as TextView).apply {
            isClickable = true
            contentDescription = station.getString("name") + ". Ketuk untuk ganti nama"
            setOnClickListener { rename() }
        }
        barAction("Ganti nama", StudioUi.Icon.EDIT) { rename() }
        // Platform crowding, only when the engine reports who is waiting.
        val waiting = station.optInt("waiting",-1)
        val daily = if (waiting < 0) riders?.invoke(node) else null
        if (waiting >= 0 || daily != null) {
            val capacity = station.optInt("capacity",0).takeIf { it > 0 } ?: maxOf(1,platforms.sumOf { it.getInt("meters") } * 4)
            val share = if (daily != null) daily.second.toDouble() else (waiting.toDouble()/capacity).coerceIn(0.0,1.0)
            val count = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("id-ID")).format(daily?.first ?: waiting)
            val unit = if (daily != null) "penumpang/hari" else "orang"
            val crowd = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.card(activity,14)
                setPadding(dp(10),dp(6),dp(10),dp(8))
            }
            // label above the value so neither is squeezed into a narrow column
            crowd.addView(StudioUi.text(activity,if (daily != null) "Keramaian stasiun" else "Keramaian peron",12f,true,StudioUi.MUTED))
            crowd.addView(StudioUi.text(activity,when { share > .75 -> "Padat"; share > .4 -> "Ramai"; else -> "Lengang" } + " · $count $unit",
                14f,true,if (share > .75) 0xffb4500f.toInt() else StudioUi.INK))
            val dots = LinearLayout(activity).apply { setPadding(0,dp(6),0,0) }
            val filled = Math.round(share*12).toInt()
            repeat(12) { k ->
                dots.addView(android.view.View(activity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (k < filled) StudioUi.YELLOW else 0x1a22204a)
                    }
                }, LinearLayout.LayoutParams(dp(14),dp(14)).apply { marginEnd = dp(4) })
            }
            crowd.addView(dots)
            crowd.contentDescription = "Keramaian, $count $unit"
            sheet.add(crowd,8)
        }
        val upcoming = departures.filter { byId.containsKey(it.getInt("line")) }
        fun destination(d: JSONObject): String {
            val line = byId.getValue(d.getInt("line"))
            return line.getJSONArray("runs").rows().firstOrNull { it.getInt("run")==d.getInt("run") }?.optString("to")
                ?.takeIf { it.isNotBlank() } ?: line.getString("name")
        }
        val first = upcoming.firstOrNull()
        if (first != null) {
            val card = LinearLayout(activity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = StudioUi.pill(activity,StudioUi.INK,14)
                setPadding(dp(12),dp(8),dp(12),dp(8))
            }
            card.addView(StudioUi.title(activity,clock(first.getDouble("at")+first.optDouble("shift",0.0)),22f,Color.WHITE))
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(StudioUi.text(activity,"Kereta berikutnya",11f,true,0xffbeb6e8.toInt()))
            labels.addView(StudioUi.text(activity,"ke "+destination(first),14f,color = Color.WHITE).apply {
                typeface = StudioUi.heavy(activity); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            labels.addView(StudioUi.text(activity,byId.getValue(first.getInt("line")).getString("name"),11f,true,0xffbeb6e8.toInt()).apply { maxLines = 1 })
            card.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(12) })
            sheet.add(card,8)
        } else sheet.text("Belum ada keberangkatan dalam dua jam ke depan.")
        if (upcoming.size>1) {
            sheet.text("Jadwal berikutnya",true)
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            sheet.add(rows,0)
            fun appendDeparture(d: JSONObject) {
                val line = byId.getValue(d.getInt("line"))
                val row = LinearLayout(activity).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = StudioUi.card(activity,12)
                    setPadding(dp(10),dp(5),dp(10),dp(5))
                    minimumHeight = dp(44)
                }
                row.addView(StudioUi.text(activity,clock(d.getDouble("at")+d.optDouble("shift",0.0)),14f).apply { typeface = StudioUi.heavy(activity) },
                    LinearLayout.LayoutParams(dp(50),-2))
                row.addView(dot(activity,line.getInt("color"),10).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
                val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                labels.addView(StudioUi.text(activity,destination(d),14f,true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                labels.addView(StudioUi.text(activity,line.getString("name"),11.5f,true,StudioUi.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                rows.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
            }
            upcoming.drop(1).take(2).forEach(::appendDeparture)
            if(upcoming.size>3) {
                var shown = 3
                val more = StudioUi.button(activity,"Lihat jadwal lainnya (${upcoming.size-shown})") {}
                more.setOnClickListener {
                    val end = minOf(shown+10,upcoming.size)
                    upcoming.subList(shown,end).forEach(::appendDeparture)
                    shown=end
                    if (shown == upcoming.size) more.visibility=android.view.View.GONE
                    else more.text = "Lihat jadwal lainnya (${upcoming.size-shown})"
                }
                sheet.add(more,8)
            }
        }
        // web StationDetails: each station's length in cars and its level, then the upkeep a day
        sheet.text(if (platforms.size > 1) "Stasiun" else "Panjang stasiun",true)
        val currency = StudioCurrency.current(activity)
        for (platform in platforms) {
            val level = platform.getInt("level")
            val cars = Math.round(platform.getInt("meters") / CAR_M)
            sheet.add(StudioUi.row(activity,StudioUi.Icon.PIN,StudioUi.TINT_BLUE,platform.getString("name"),
                "$cars gerbong · ${levelName(level)} (tingkat ${levelLabel(level)})"),4)
        }
        if (platforms.isNotEmpty()) {
            val upkeep = platforms.sumOf { it.optDouble("upkeep",0.0) }
            val rates = station.optJSONArray("upkeepRates")
            val value = StudioUi.text(activity,StudioFormat.money(upkeep,currency)+"/hari",13f,color = StudioUi.BAD).apply {
                typeface = StudioUi.heavy(activity)
            }
            sheet.add(StudioUi.row(activity,null,StudioUi.TINT_GREY,"Perawatan",
                rates?.let { "Perawatan stasiun bawah tanah ${StudioFormat.money(it.getDouble(0),currency)} per hari, yang lain ${StudioFormat.money(it.getDouble(1),currency)}." },
                value),4)
        }
        // 168Railway's own paid parking and station adverts (WorldExtras.kt; not in the web)
        sheet.add(WorldExtras.stationSection(activity,handle,node,time,transaction,changed),4)
        if(lines.isNotEmpty()) sheet.text("Diagram rute",true)
        for(line in lines) for(direction in line.getJSONArray("runs").rows()) {
            sheet.action("${line.getString("name")} → ${direction.getString("to")}",line.getInt("color")) {
                route(activity,handle,line.getInt("id"),direction.getInt("run"),node,time,transaction,changed)
            }
        }
        // web ClosestStations: the 3 nearest on foot, each opening that station's view
        val near = station.optJSONArray("closest")?.rows().orEmpty()
        if (near.isNotEmpty()) {
            sheet.text("Stasiun terdekat",true)
            val metres = java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
            for (r in near) {
                val built = r.optBoolean("built")
                val name = if (built) r.getString("name") else r.getString("name") + " (rencana)"
                val lines = r.getJSONArray("lines").rows()
                val chips = lines.take(6).joinToString(" · ") { it.getString("name") } + if (lines.size > 6) " +${lines.size - 6}" else ""
                val minutes = maxOf(1, Math.round(r.getDouble("minutes")).toInt())
                val m = r.getDouble("metres")
                val walk = StudioUi.text(activity,"$minutes mnt",13f,color = if (built) StudioUi.INK else StudioUi.MUTED).apply {
                    typeface = StudioUi.heavy(activity)
                    contentDescription = "${if (m >= 1000) String.format(Locale.forLanguageTag("id-ID"),"%.1f km",m/1000) else metres.format(Math.round(m)) + " m"} jalan kaki, $minutes menit"
                }
                // a planned station has no inspector of its own here: listed, not opened
                if (built) sheet.action(name, color = lines.firstOrNull()?.getInt("color"), subtitle = chips.ifEmpty { null }, trailing = walk) {
                    show(activity,handle,r.getInt("id"),time,transaction,changed)
                } else sheet.add(StudioUi.row(activity,null,0,name,chips.ifEmpty { null },walk),6)
            }
        }
        // web StationLinks and the upgrade row's passage button (PassagePick.kt)
        val reopen: (Double) -> Unit = { now -> sheet.dismiss(); show(activity,handle,node,now,transaction,changed) }
        PassagePick.section(activity,handle,station.optJSONArray("passages"),platforms.size,node,transaction,changed,reopen)?.let { sheet.add(it,12) }
        sheet.action("Lorong",icon=R.drawable.studio_upgrade_passage,subtitle=PassagePick.tip(activity)) {
            PassagePick.start(activity,handle,node,station.getString("name"),transaction,changed) { now -> show(activity,handle,node,now,transaction,changed) }
        }
        // Actions in the row under the name: Rancang (length, tracks, upgrades) and Gedung, both on the
        // map (StationDesign.kt), with short labels so neither is cut.
        val design = map?.takeIf { platforms.any { it.getInt("id") == node } }
        barAction("Rancang", StudioUi.Icon.BUILD, description = "Rancang stasiun: panjang, jalur dan peningkatan") {
            sheet.dismiss()
            if (design != null) StationDesign.start(activity,design,handle,node,time,transaction,changed,
                { now -> show(activity,handle,node,now,transaction,changed) })
            else facilities(activity,handle,node,time,platforms,transaction,changed)
        }
        barAction("Gedung", StudioUi.Icon.LAYERS, description = "Gedung stasiun") {
            sheet.dismiss()
            if (design != null) StationDesign.start(activity,design,handle,node,time,transaction,changed,
                { now -> show(activity,handle,node,now,transaction,changed) }, building = true)
            else StationStructurePanel(activity,handle,node,time,transaction,changed).show()
        }
        // web: "Hapus stasiun", asking first with what it takes; last in the row, only its text red
        val host = activity as? NativeWorldActivity
        if (host != null && platforms.any { it.getInt("id") == node }) {
            val name = station.getString("name")
            barAction("Hapus", StudioUi.Icon.DELETE, StudioUi.BAD, "Hapus stasiun") {
                val preview = JSONObject(DemolitionPanel.remove(handle,1,node,time,false))
                if (preview.has("error")) {
                    WorldSound.refused(activity, preview.getString("error"))
                    return@barAction
                }
                val confirm = StudioDialogBuilder(activity).setTitle("Hapus $name?")
                    .setView(DemolitionPanel.impact(activity,1,preview))
                    .setNegativeButton("Batal",null)
                    .setPositiveButton("Hapus") { _,_ -> sheet.dismiss(); host.removeInspectedStation(node,name) }
                    .show()
                DemolitionPanel.danger(confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
            }
        }
        sheet.show()
    }
    /**
     * Web NameEdit on a station (StationInspector.tsx `rename`): the name typed, trimmed, empty or
     * unchanged left as it is; `done(true)` once the world took the new name.
     */
    private fun rename(activity: Activity, handle: Long, node: Int, current: String, time: Double,
                       transaction: PanelEdit, done: (Boolean) -> Unit) {
        val field = android.widget.EditText(activity).apply {
            setText(current)
            setSingleLine()
            setSelectAllOnFocus(true)
            StudioUi.field(this)
            contentDescription = "Nama"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        // web StationInspector's cycle button beside the name: the next suggested name, wrapping round
        // same look as the line's rename (ServicePanel.askRename): field + cycle icon pinned above the ideas
        val suggest = StudioUi.iconButton(activity, "Nama usulan berikutnya", StudioUi.Icon.UNDO) {
            val next = StationNames.nextFor(handle, node, field.text.toString().trim())
            if (next != null) { field.setText(next); field.setSelection(next.length) }
            else android.widget.Toast.makeText(activity, "Tidak ada usulan nama lain di sini.", android.widget.Toast.LENGTH_SHORT).show()
        }.apply { rotationY = 180f }
        val top = LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0,StudioUi.dp(activity,2),0,StudioUi.dp(activity,4))
            addView(field,LinearLayout.LayoutParams(0,-2,1f))
            addView(suggest,LinearLayout.LayoutParams(StudioUi.dp(activity,48),StudioUi.dp(activity,48)).apply { marginStart = StudioUi.dp(activity,6) })
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun dp(n: Int) = StudioUi.dp(activity, n)
        // At least five names ready to tap: the web generator's suggestions in order, then the
        // nearest place with the usual Indonesian station words (owner: "minimal 5").
        val seen = linkedSetOf<String>()
        var probe = current
        repeat(8) { StationNames.nextFor(handle, node, probe)?.let { n -> if (!seen.add(n)) return@repeat; probe = n } }
        val base = (seen.firstOrNull() ?: current).replace(Regex(" (Baru|Timur|Barat|Utara|Selatan|\\d+)$"), "")
        for (w in listOf("$base Baru", "$base Timur", "$base Barat", "$base Raya", "Pasar $base")) if (seen.size < 8) seen.add(w)
        val ready = seen.filter { it != current }.take(6)
        if (ready.isNotEmpty()) {
            box.addView(StudioUi.text(activity, "Usulan nama", 12f, true, StudioUi.MUTED).apply { setPadding(0, dp(6), 0, dp(2)) })
            // one neat column: every idea one line high, cut with "…" when long
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ready.forEach { n ->
                list.addView(StudioUi.text(activity, n, 14f, true, StudioUi.INK).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, dp(12), 0); background = StudioUi.pill(activity, 0x1422204a, 12); isClickable = true
                    setOnClickListener { field.setText(n); field.setSelection(n.length) }
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
            }
            box.addView(list)
        }
        // suffixes to add to whatever is typed
        box.addView(StudioUi.text(activity, "Tambah akhiran", 12f, true, StudioUi.MUTED).apply { setPadding(0, dp(8), 0, dp(2)) })
        val sufs = LinearLayout(activity)
        for (x in listOf("Baru", "Timur", "Barat", "Utara", "Selatan", "Kota", "Raya")) sufs.addView(StudioUi.text(activity, x, 13f, true).apply {
            gravity = android.view.Gravity.CENTER; minHeight = dp(36); setPadding(dp(12), 0, dp(12), 0)
            background = StudioUi.pill(activity, 0x1422204a, 14); isClickable = true
            setOnClickListener {
                val t = field.text.toString().trim().replace(Regex(" (Baru|Timur|Barat|Utara|Selatan|Kota|Raya)$"), "")
                val n = "$t $x".take(100); field.setText(n); field.setSelection(n.length)
            }
        }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6); topMargin = dp(2); bottomMargin = dp(2) })
        box.addView(android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(sufs) })
        var answered = false
        fun commit() {
            if (answered) return
            answered = true
            val name = field.text.toString().trim()
            if (name.isEmpty() || name == current) { done(false); return }
            transaction { now ->
                val result = JSONObject(structure(handle,node,now,JSONObject().put("action","rename").put("name",name).toString()))
                if (result.has("error")) WorldSound.refused(activity, result.getString("error"))
                done(result.optBoolean("changed"))
            }
        }
        val dialog = StudioDialogBuilder(activity).setTitle("Ganti nama stasiun").setPinned(top).setView(box)
            .setNegativeButton("Batal") { _,_ -> if (!answered) { answered = true; done(false) } }
            .setOnCancelListener { if (!answered) { answered = true; done(false) } }
            .setPositiveButton("Selesai") { _,_ -> commit() }
            .show()
        field.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); commit(); true }
        field.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    private fun facilities(activity: Activity,handle: Long,node: Int,time: Double,platforms: List<JSONObject>,
                           transaction: PanelEdit, changed: () -> Unit) {
        val names = UPGRADE_NAMES
        val symbols = mapOf(1 to R.drawable.studio_upgrade_terminal, 2 to R.drawable.studio_upgrade_park_ride,
            4 to R.drawable.studio_upgrade_waiting_room, 8 to R.drawable.studio_upgrade_passing)
        val effects = UPGRADE_DOES
        fun reopen() {
            val station = JSONObject(read(handle,node,-1,0,time))
            facilities(activity,handle,node,time,station.getJSONArray("platforms").rows(),transaction,changed)
        }
        val sheet = Sheet(activity,"Peningkatan stasiun")
        fun dp(n: Int) = StudioUi.dp(activity,n)
        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                StudioUi.panel(activity,StudioUi.BLUE_EDGE,24,StudioUi.BLUE_EDGE),
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(StudioUi.BLUE,0xff9a7bff.toInt())).apply { cornerRadius = dp(24).toFloat() })).apply {
                setLayerInset(1,0,0,0,dp(5))
            }
            setPadding(dp(16),dp(14),dp(16),dp(18))
        }
        banner.addView(StudioUi.text(activity,"${platforms.size} peron · pilih fasilitas",14f,color = Color.WHITE).apply { typeface = StudioUi.heavy(activity) })
        banner.addView(StudioUi.text(activity,"Fasilitas membuat penumpang betah dan kereta lebih lancar.",12f,true,0xd9ffffff.toInt()))
        sheet.add(banner,0)
        for (platform in platforms) {
            sheet.text(platform.getString("name")+" · "+platform.getInt("meters")+" m",true)
            val options = platform.getJSONArray("facilities").rows()
            if (options.isEmpty()) sheet.text("Tidak ada fasilitas tambahan untuk peron ini.")
            for (option in options) {
                val bit = option.getInt("bit")
                val name = names[bit] ?: continue
                val installed = option.getBoolean("on")
                // web stationUpgrades: "Biaya {price}"; a built one removed gives no money back
                val price = StudioFormat.money(option.getDouble("price") * 1e6, StudioCurrency.current(activity))
                sheet.action(name, icon=symbols[bit], subtitle=effects[bit],
                    trailing=priceTag(activity,if (installed) "Dibangun" else price,installed).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
                    }) {
                    StudioDialogBuilder(activity).setTitle(if (installed) "Hapus $name?" else "Bangun $name?")
                        .setView(StudioDecision(activity,platform.getString("name"),effects[bit]).apply {
                            fact("Panjang peron",platform.getInt("meters").toString()+" m")
                            fact("Status setelah konfirmasi",if(installed) "Dihapus" else "Dibangun")
                            if(installed) fact("Pengembalian dana","Uang tidak kembali")
                            else fact("Biaya",price)
                        })
                        .setNegativeButton("Batal") { _,_ -> reopen() }
                        .setOnCancelListener { reopen() }
                        .setPositiveButton(if (installed) "Hapus" else "Bangun") { _,_ ->
                            transaction { now ->
                                val result = JSONObject(upgrade(handle,platform.getInt("id"),bit,if (installed) 0 else 1,now))
                                if (result.optBoolean("changed")) { WorldSound.cueBuilt(result, !installed); changed() }
                                if (result.has("error")) WorldSound.refused(activity, result.getString("error"))
                                reopen()
                            }
                        }.show()
                }
            }
        }
        sheet.text("Gedung stasiun",true)
        sheet.action("Gedung stasiun dan lorong",icon=R.drawable.studio_upgrade_passage,subtitle="Denah bangunan dan lorong antarperon",
            trailing=StudioDepot.symbol(activity,StudioUi.Icon.ROUTE,18f,StudioUi.MUTED)) { StationStructurePanel(activity,handle,node,time,transaction,changed).show() }
        sheet.show()
    }
    private fun route(activity: Activity,handle: Long,line: Int,run: Int,selected: Int,time: Double, transaction: PanelEdit, changed: () -> Unit) {
        val payload=read(handle,-1,line,run,time)
        if(payload=="null") return
        val route=JSONObject(payload)
        val shift = route.optDouble("shift",0.0)
        val sheet=Sheet(activity,route.getString("name"))
        if(route.isNull("base")) sheet.text("Belum ada perjalanan yang berangkat dalam 24 jam.")
        else sheet.add(StudioUi.chip(activity,"Berangkat ${clock(route.getDouble("base")+shift)}",StudioUi.TINT_PURPLE,StudioUi.BLUE_EDGE).apply {
            layoutParams = LinearLayout.LayoutParams(-2,-2)
        },0)
        val stops=route.getJSONArray("stops").rows()
        for((index,stop) in stops.withIndex()) {
            val node=stop.getInt("node")
            val name=stop.getString("name")
            val at=if(stop.isNull("arrival")) "" else clock(stop.getDouble("arrival")+shift)
            val departure=if(stop.isNull("departure")) "" else clock(stop.getDouble("departure")+shift)
            val times=if(departure.isNotEmpty() && departure!=at) "$at–$departure" else at
            val terminal = index==0 || index==stops.lastIndex
            sheet.action(name+if(node==selected) " · Dipilih" else "",route.getInt("color"),
                subtitle=(if (terminal) "Ujung · " else "")+times.ifEmpty { "—" }) {
                show(activity,handle,node,time,transaction,changed)
            }
        }
        sheet.show()
    }
}
