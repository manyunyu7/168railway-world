// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object StationInspector {
    @JvmStatic external fun read(handle: Long, node: Int, line: Int, run: Int, clock: Double): String
    private fun JSONArray.rows() = List(length()) { getJSONObject(it) }
    private fun clock(seconds: Double): String {
        val minute = Math.floorMod(kotlin.math.floor(seconds/60).toLong(),1440L)
        return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60)
    }
    private class Sheet(val activity: Activity,val title: String) {
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(16*resources.displayMetrics.density).toInt()
            setPadding(pad,0,pad,pad)
        }
        private var dialog: AlertDialog?=null
        fun text(value: String, heading: Boolean=false) {
            root.addView(TextView(activity).apply {
                text=value
                textSize=if(heading) 16f else 14f
                setPadding(0,if(heading) 18 else 5,0,4)
                if(heading) setTypeface(typeface,android.graphics.Typeface.BOLD)
            })
        }
        fun action(value: String,color: Int?=null,onClick: ()->Unit) {
            root.addView(Button(activity).apply {
                text=value
                isAllCaps=false
                gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                if(color!=null) setCompoundDrawablesWithIntrinsicBounds(android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.BLACK or color)
                    val dp=(11*resources.displayMetrics.density).toInt(); setSize(dp,dp)
                },null,null,null)
                compoundDrawablePadding=(9*resources.displayMetrics.density).toInt()
                setOnClickListener { dialog?.dismiss(); onClick() }
            })
        }
        fun show() {
            dialog=AlertDialog.Builder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(root) })
                .setNegativeButton("Tutup",null).show()
        }
    }
    fun show(activity: Activity,handle: Long,node: Int,time: Double) {
        val payload=read(handle,node,-1,0,time)
        if(payload=="null") return
        val station=JSONObject(payload)
        val sheet=Sheet(activity,station.getString("name"))
        val platforms=station.getJSONArray("platforms").rows()
        sheet.text(if(platforms.size>1) "Kompleks stasiun · ${platforms.size} peron" else "Peron",true)
        for(p in platforms) sheet.text("${p.getString("name")} · ${p.getInt("meters")} m · level ${p.getInt("level")}")
        val lines=station.getJSONArray("lines").rows()
        val byId=lines.associateBy { it.getInt("id") }
        sheet.text("Keberangkatan dua jam ke depan",true)
        val departures=station.getJSONArray("departures").rows()
        if(departures.isEmpty()) sheet.text("Belum ada keberangkatan terjadwal.")
        for(d in departures) {
            val line=byId[d.getInt("line")] ?: continue
            val terminal=line.getJSONArray("runs").rows().firstOrNull { it.getInt("run")==d.getInt("run") }?.getString("to") ?: ""
            sheet.text("${clock(d.getDouble("at"))}   ${line.getString("name")} → $terminal")
        }
        sheet.text("Diagram rute",true)
        if(lines.isEmpty()) sheet.text("Belum ada layanan rel di kompleks ini.")
        for(line in lines) for(direction in line.getJSONArray("runs").rows()) {
            sheet.action("${line.getString("name")} → ${direction.getString("to")}",line.getInt("color")) {
                route(activity,handle,line.getInt("id"),direction.getInt("run"),node,time)
            }
        }
        sheet.show()
    }
    private fun route(activity: Activity,handle: Long,line: Int,run: Int,selected: Int,time: Double) {
        val payload=read(handle,-1,line,run,time)
        if(payload=="null") return
        val route=JSONObject(payload)
        val sheet=Sheet(activity,route.getString("name"))
        if(route.isNull("base")) sheet.text("Belum ada perjalanan yang berangkat dalam 24 jam.")
        else sheet.text("Perjalanan berikutnya · berangkat ${clock(route.getDouble("base"))}",true)
        val stops=route.getJSONArray("stops").rows()
        for((index,stop) in stops.withIndex()) {
            val node=stop.getInt("node")
            val name=stop.getString("name")
            val at=if(stop.isNull("arrival")) "" else clock(stop.getDouble("arrival"))
            val departure=if(stop.isNull("departure")) "" else clock(stop.getDouble("departure"))
            val times=if(departure.isNotEmpty() && departure!=at) "$at–$departure" else at
            val position=if(index==0 || index==stops.lastIndex) "●" else "│"
            sheet.action("$position  $times   $name"+if(node==selected) " · Dipilih" else "",route.getInt("color")) {
                show(activity,handle,node,time)
            }
        }
        sheet.show()
    }
}
