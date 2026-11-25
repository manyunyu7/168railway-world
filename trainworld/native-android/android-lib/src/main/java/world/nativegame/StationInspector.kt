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
    @JvmStatic external fun structure(handle: Long, node: Int, clock: Double, command: String): String
    @JvmStatic external fun upgrade(handle:Long,node:Int,bit:Int,on:Int,clock:Double):String
    @JvmStatic external fun read(handle: Long, node: Int, line: Int, run: Int, clock: Double): String
    private fun JSONArray.rows() = List(length()) { getJSONObject(it) }
    private fun clock(seconds: Double): String {
        val minute = Math.floorMod(kotlin.math.floor(seconds/60).toLong(),1440L)
        return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60)
    }
    private class Sheet(val activity: Activity,val title: String) {
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
        }
        private var dialog: android.app.Dialog?=null
        fun text(value: String, heading: Boolean=false) {
            root.addView(StudioUi.text(activity,value,if(heading) 18f else 15f,heading).apply {
                setPadding(0,StudioUi.dp(activity,if(heading) 14 else 3),0,StudioUi.dp(activity,3))
            })
        }
        fun action(value: String,color: Int?=null,icon: Int?=null,onClick: ()->Unit) {
            root.addView(StudioUi.button(activity,value) { dialog?.dismiss(); onClick() }.apply {
                gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                if(color!=null) setCompoundDrawablesWithIntrinsicBounds(android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.BLACK or color)
                    val dp=(11*resources.displayMetrics.density).toInt(); setSize(dp,dp)
                },null,null,null)
                if(icon!=null) setCompoundDrawablesWithIntrinsicBounds(icon,0,0,0)
                compoundDrawablePadding=(9*resources.displayMetrics.density).toInt()
            })
        }
        fun dismiss() { dialog?.dismiss() }
        fun show() {
            dialog = StudioUi.sheet(activity,title,heightFraction = .72f) { column, _ -> column.addView(root) }
        }
    }
    fun show(activity: Activity,handle: Long,node: Int,time: Double, changed: () -> Unit) {
        val payload=read(handle,node,-1,0,time)
        if(payload=="null") return
        val station=JSONObject(payload)
        val sheet=Sheet(activity,station.getString("name"))
        val platforms=station.getJSONArray("platforms").rows()
        val lines = station.getJSONArray("lines").rows()
        val byId = lines.associateBy { it.getInt("id") }
        val departures = station.getJSONArray("departures").rows()
        fun dp(n: Int) = StudioUi.dp(activity,n)
        sheet.root.addView(StudioUi.text(activity,"${platforms.size} peron   ·   ${lines.size} layanan",15f,false,StudioDepot.SECONDARY))
        val upcoming = departures.filter { byId.containsKey(it.getInt("line")) }
        fun destination(d: JSONObject): String {
            val line = byId.getValue(d.getInt("line"))
            return line.getJSONArray("runs").rows().firstOrNull { it.getInt("run")==d.getInt("run") }?.optString("to")
                ?.takeIf { it.isNotBlank() } ?: line.getString("name")
        }
        val first = upcoming.firstOrNull()
        if (first != null) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.panel(activity,StudioDepot.TEAL,18,StudioDepot.TEAL)
                setPadding(dp(16),dp(12),dp(16),dp(14))
            }
            card.addView(StudioUi.text(activity,"Keberangkatan berikutnya",14f,true,StudioDepot.MINT))
            card.addView(StudioUi.text(activity,clock(first.getDouble("at")+first.optDouble("shift",0.0)),31f,true,Color.WHITE))
            card.addView(StudioUi.text(activity,"Ke "+destination(first),19f,true,Color.WHITE))
            card.addView(StudioUi.text(activity,byId.getValue(first.getInt("line")).getString("name"),14f,false,StudioDepot.MINT))
            sheet.root.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        } else sheet.text("Belum ada keberangkatan dalam dua jam ke depan.")
        sheet.root.addView(StudioDepot.ticket(activity,"Fasilitas & upgrade stasiun",
            "Peron, ruang tunggu, bangunan dan lorong",StudioUi.Icon.TRAIN) {
            sheet.dismiss()
            facilities(activity,handle,node,time,platforms,changed)
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        if (upcoming.size>1) {
            sheet.text("Jadwal berikutnya",true)
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            sheet.root.addView(rows)
            fun appendDeparture(d: JSONObject) {
                val line = byId.getValue(d.getInt("line"))
                val row = LinearLayout(activity).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0,dp(6),0,dp(6))
                }
                row.addView(StudioUi.text(activity,clock(d.getDouble("at")+d.optDouble("shift",0.0)),17f,true,StudioDepot.TEAL),
                    LinearLayout.LayoutParams(dp(59),-2))
                val stripe = android.view.View(activity).apply { setBackgroundColor(line.getInt("color") or Color.BLACK) }
                row.addView(stripe,LinearLayout.LayoutParams(dp(4),dp(30)).apply { marginEnd=dp(10) })
                val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                labels.addView(StudioUi.text(activity,destination(d),16f,true))
                labels.addView(StudioUi.text(activity,line.getString("name"),13f,false,StudioDepot.SECONDARY))
                row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                rows.addView(row)
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
                sheet.root.addView(more)
            }
        }
        sheet.text("Peron",true)
        for (platform in platforms) {
            val level = platform.getInt("level")
            val location = when { level==0 -> "Permukaan"; level>0 -> "Layang · tingkat $level"; else -> "Bawah tanah · tingkat ${-level}" }
            sheet.text(platform.getString("name")+"  ·  "+platform.getInt("meters")+" m  ·  "+location)
        }
        if(lines.isNotEmpty()) sheet.text("Diagram rute",true)
        for(line in lines) for(direction in line.getJSONArray("runs").rows()) {
            sheet.action("${line.getString("name")} → ${direction.getString("to")}",line.getInt("color")) {
                route(activity,handle,line.getInt("id"),direction.getInt("run"),node,time,changed)
            }
        }
        sheet.show()
    }
    private fun facilities(activity: Activity,handle: Long,node: Int,time: Double,platforms: List<JSONObject>,
                           changed: () -> Unit) {
        val names = mapOf(1 to "Terminal", 2 to "Parkir & lanjut naik kereta", 4 to "Ruang tunggu", 8 to "Jalur susul")
        val symbols = mapOf(1 to R.drawable.studio_upgrade_terminal, 2 to R.drawable.studio_upgrade_park_ride,
            4 to R.drawable.studio_upgrade_waiting_room, 8 to R.drawable.studio_upgrade_passing)
        val effects = mapOf(
            1 to "Kereta yang berbalik arah lebih cepat membebaskan peron.",
            2 to "Penumpang lebih mudah menjangkau stasiun dengan kendaraan.",
            4 to "Penumpang menganggap waktu tunggu di stasiun ini lebih singkat.",
            8 to "Kereta cepat bisa mendahului kereta yang berhenti di sini."
        )
        fun reopen() {
            val station = JSONObject(read(handle,node,-1,0,time))
            facilities(activity,handle,node,time,station.getJSONArray("platforms").rows(),changed)
        }
        val sheet = Sheet(activity,"Fasilitas stasiun")
        for (platform in platforms) {
            sheet.text(platform.getString("name")+" · "+platform.getInt("meters")+" m",true)
            val options = platform.getJSONArray("facilities").rows()
            if (options.isEmpty()) sheet.text("Tidak ada fasilitas tambahan untuk peron ini.")
            for (option in options) {
                val bit = option.getInt("bit")
                val name = names[bit] ?: continue
                val installed = option.getBoolean("on")
                val price = StudioCurrency.current(activity).compact(option.getDouble("price"))
                sheet.action(if (installed) "$name · Terpasang" else "$name · $price", icon=symbols[bit]) {
                    StudioDialogBuilder(activity).setTitle(if (installed) "Lepas $name?" else "Pasang $name?")
                        .setView(StudioDecision(activity,platform.getString("name"),effects[bit]).apply {
                            fact("Panjang peron",platform.getInt("meters").toString()+" m")
                            fact("Status setelah konfirmasi",if(installed) "Dilepas" else "Terpasang")
                            if(installed) fact("Pengembalian dana", StudioCurrency.current(activity).compact(0.0))
                            else fact("Biaya pemasangan",price)
                        })
                        .setNegativeButton("Batal") { _,_ -> reopen() }
                        .setOnCancelListener { reopen() }
                        .setPositiveButton(if (installed) "Lepas" else "Pasang") { _,_ ->
                            val result = JSONObject(upgrade(handle,platform.getInt("id"),bit,if (installed) 0 else 1,time))
                            if (result.optBoolean("changed")) changed()
                            if (result.has("error")) android.widget.Toast.makeText(activity,result.getString("error"),android.widget.Toast.LENGTH_LONG).show()
                            reopen()
                        }.show()
                }
            }
        }
        sheet.action("Bangunan & lorong",icon=R.drawable.studio_upgrade_passage) { StationStructurePanel(activity,handle,node,time,changed).show() }
        sheet.show()
    }
    private fun route(activity: Activity,handle: Long,line: Int,run: Int,selected: Int,time: Double, changed: () -> Unit) {
        val payload=read(handle,-1,line,run,time)
        if(payload=="null") return
        val route=JSONObject(payload)
        val shift = route.optDouble("shift",0.0)
        val sheet=Sheet(activity,route.getString("name"))
        if(route.isNull("base")) sheet.text("Belum ada perjalanan yang berangkat dalam 24 jam.")
        else sheet.text("Perjalanan berikutnya · berangkat ${clock(route.getDouble("base")+shift)}",true)
        val stops=route.getJSONArray("stops").rows()
        for((index,stop) in stops.withIndex()) {
            val node=stop.getInt("node")
            val name=stop.getString("name")
            val at=if(stop.isNull("arrival")) "" else clock(stop.getDouble("arrival")+shift)
            val departure=if(stop.isNull("departure")) "" else clock(stop.getDouble("departure")+shift)
            val times=if(departure.isNotEmpty() && departure!=at) "$at–$departure" else at
            val position=if(index==0 || index==stops.lastIndex) "●" else "│"
            sheet.action("$position  $times   $name"+if(node==selected) " · Dipilih" else "",route.getInt("color")) {
                show(activity,handle,node,time,changed)
            }
        }
        sheet.show()
    }
}
