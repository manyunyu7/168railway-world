// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal class StationStructurePanel(private val activity: Activity, private val handle: Long,
    private val node: Int, private val clock: Double, private val changed: () -> Unit) {
    private fun dp(n: Int) = StudioUi.dp(activity,n)
    private fun call(action: String, fields: JSONObject = JSONObject()): JSONObject =
        JSONObject(StationInspector.structure(handle,node,clock,fields.put("action",action).toString()))
    private fun error(result: JSONObject): Boolean {
        if (!result.has("error")) return false
        Toast.makeText(activity,result.getString("error"),Toast.LENGTH_LONG).show()
        return true
    }
    private fun JSONArray.rows() = List(length()) { getJSONObject(it) }
    private fun commit(action: String, request: JSONObject) {
        val result=call(action,request)
        if (!error(result) && result.optBoolean("changed")) changed()
        show()
    }
    fun show() {
        val data=call("list")
        if (error(data)) return
        val platforms=data.getJSONArray("platforms").rows()
        val buildings=data.getJSONArray("buildings").rows()
        val currency=StudioCurrency.current(activity)
        StudioUi.sheet(activity,"Bangunan & lorong") { column,dialog ->
            column.addView(StudioUi.text(activity,data.getString("name"),19f,true))
            column.addView(StudioUi.text(activity,"Bangunan stasiun",17f,true).apply { setPadding(0,dp(12),0,dp(3)) })
            if (buildings.isEmpty()) column.addView(StudioUi.text(activity,"Peron ini belum memiliki bangunan.",14f))
            for (item in buildings) {
                column.addView(StudioDepot.ticket(activity,item.getString("name"),
                    "${item.getDouble("area").toInt()} m² · ${item.getInt("members")} peron",StudioUi.Icon.TRAIN) {
                    dialog.dismiss()
                    editBuilding(data,item)
                })
            }
            column.addView(StudioUi.button(activity,"Rancang bangunan",StudioUi.Tone.PRIMARY,StudioUi.Icon.PLUS) {
                dialog.dismiss()
                editBuilding(data,null)
            })
            column.addView(StudioUi.text(activity,"Lorong antarperon",17f,true).apply { setPadding(0,dp(14),0,dp(3)) })
            if (platforms.isEmpty()) column.addView(StudioUi.text(activity,
                "Belum ada peron lain dalam jarak 1 km. Bangun peron lebih dekat untuk menyambungkannya.",14f))
            for (dest in platforms) {
                val name=dest.getString("name")
                val linked=dest.getBoolean("linked")
                val built=dest.getBoolean("built")
                val length="${dest.getDouble("meters").toInt()} m"
                val cost=currency.compact(dest.getDouble("cost"))
                val state=when { linked -> "Terhubung"; built -> cost; else -> "Cetak biru" }
                column.addView(StudioDepot.ticket(activity,name,"$length · $state",StudioUi.Icon.ROUTE) {
                    val preview=StudioDecision(activity,name).apply {
                        fact("Panjang lorong",length)
                        if (linked) fact("Pengembalian",currency.compact(0.0))
                        else fact(if (built) "Biaya" else "Saat dibangun",cost)
                    }
                    StudioDialogBuilder(activity).setTitle(if (linked) "Bongkar lorong?" else "Sambungkan peron?")
                        .setView(preview).setNegativeButton("Batal",null)
                        .setPositiveButton(if (linked) "Bongkar" else "Sambungkan") { _,_ ->
                            dialog.dismiss()
                            commit(if (linked) "unlink" else "link",JSONObject().put("id",dest.getInt("id")))
                        }.show()
                })
            }
        }
    }
    private fun editBuilding(data: JSONObject, building: JSONObject?) {
        val points = building?.getJSONArray("points")?.let { a -> MutableList(a.length()) {
            val p=a.getJSONArray(it)
            PointF(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())
        } } ?: mutableListOf(PointF(-40f,-20f),PointF(40f,-20f),PointF(40f,20f),PointF(-40f,20f))
        StudioUi.sheet(activity,if (building==null) "Rancang bangunan" else "Ubah bangunan") { column,dialog ->
            val nameField=EditText(activity).apply {
                setSingleLine()
                hint="Nama bangunan"
                setText(building?.getString("name") ?: data.getString("name"))
            }
            column.addView(nameField)
            column.addView(StudioUi.text(activity,"Geser sudut untuk membentuk denah. Ketuk sisi untuk menambah sudut. Titik gelap menandai peron.",14f))
            val nearby=data.getJSONArray("platforms").rows().map { PointF(it.getDouble("x").toFloat(),it.getDouble("y").toFloat()) }
            val plan=FootprintView(activity,points,nearby)
            column.addView(plan,LinearLayout.LayoutParams(-1,dp(210)))
            val tools=LinearLayout(activity)
            column.addView(tools)
            fun tool(label: String, action: () -> Unit) {
                tools.addView(StudioUi.button(activity,label,click=action),LinearLayout.LayoutParams(0,-2,1f))
            }
            tool("−") { plan.zoom(.65f) }
            tool("+") { plan.zoom(1.5f) }
            tool("Hapus sudut") { plan.removeSelected() }
            column.addView(StudioUi.button(activity,"Tinjau bangunan",StudioUi.Tone.PRIMARY) {
                val corners=JSONArray()
                points.forEach { corners.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
                val request=JSONObject().put("points",corners).put("name",nameField.text.toString())
                if (building!=null) request.put("id",building.getInt("id"))
                val quote=call("quote",request)
                if (!error(quote)) {
                    val preview=StudioDecision(activity,nameField.text.toString().ifBlank { data.getString("name") }).apply {
                        fact("Luas",quote.getDouble("area").toInt().toString()+" m²")
                        fact("Peron di dalam bangunan",quote.getInt("members").toString())
                        fact("Biaya",StudioCurrency.current(activity).compact(quote.getDouble("price")))
                    }
                    StudioDialogBuilder(activity).setTitle("Konfirmasi bangunan").setView(preview)
                        .setNegativeButton("Ubah denah",null).setPositiveButton("Simpan") { _,_ ->
                            dialog.dismiss()
                            commit("building",request)
                        }.show()
                }
            })
            if (building!=null) column.addView(StudioUi.button(activity,"Bongkar bangunan") {
                StudioDialogBuilder(activity).setTitle("Bongkar ${building.getString("name")}?")
                    .setMessage("Peron tetap beroperasi. Biaya pembangunan tidak dikembalikan.")
                    .setNegativeButton("Batal",null).setPositiveButton("Bongkar") { _,_ ->
                        dialog.dismiss()
                        commit("remove",JSONObject().put("id",building.getInt("id")))
                    }.show()
            })
        }
    }
}

/** A local plan centred on the selected platform, in ground metres. */
private class FootprintView(activity: Activity, private val points: MutableList<PointF>,
                            private val nearby: List<PointF>) : View(activity) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private var span=points.maxOf { maxOf(abs(it.x),abs(it.y)) }.coerceAtLeast(64f)*1.35f
    private var selected=0
    private var dragging=false
    private val scale get()=min(width,height)/2f/span
    private fun x(p: PointF)=width/2f+p.x*scale
    private fun y(p: PointF)=height/2f-p.y*scale
    init { isClickable=true; contentDescription="Denah bangunan, peron di tengah"; background=StudioUi.panel(activity,StudioDepot.MINT,12,StudioDepot.MINT) }
    fun zoom(factor: Float) { span=(span/factor).coerceIn(25f,1200f); invalidate() }
    fun removeSelected() {
        if (points.size<=3) return
        points.removeAt(selected)
        selected=selected.coerceAtMost(points.lastIndex)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val outline=Path().apply {
            moveTo(x(points.first()),y(points.first()))
            points.drop(1).forEach { lineTo(x(it),y(it)) }
            close()
        }
        paint.style=Paint.Style.FILL
        paint.color=0x6645a783
        canvas.drawPath(outline,paint)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=2*density
        paint.color=StudioDepot.TEAL
        canvas.drawPath(outline,paint)
        paint.style=Paint.Style.FILL
        for (p in nearby) canvas.drawCircle(x(p),y(p),3*density,paint)
        canvas.drawCircle(width/2f,height/2f,5*density,paint)
        for ((i,p) in points.withIndex()) {
            paint.color=if(i==selected) 0xffc75a22.toInt() else StudioDepot.TEAL
            canvas.drawCircle(x(p),y(p),6*density,paint)
            paint.color=-1
            canvas.drawCircle(x(p),y(p),2*density,paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val closest=points.indices.minByOrNull { hypot(x(points[it])-e.x,y(points[it])-e.y) } ?: return false
                if (hypot(x(points[closest])-e.x,y(points[closest])-e.y)<=20*density) selected=closest
                else {
                    if (points.size>=24) return false
                    val edge=points.indices.minByOrNull { i ->
                        val a=points[i]; val b=points[(i+1)%points.size]
                        val dx=x(b)-x(a); val dy=y(b)-y(a)
                        val t=(((e.x-x(a))*dx+(e.y-y(a))*dy)/(dx*dx+dy*dy).coerceAtLeast(.01f)).coerceIn(0f,1f)
                        hypot(x(a)+t*dx-e.x,y(a)+t*dy-e.y)
                    } ?: return false
                    selected=edge+1
                    points.add(selected,PointF((e.x-width/2f)/scale,(height/2f-e.y)/scale))
                }
                dragging=true
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if(dragging) {
                points[selected].set((e.x-width/2f)/scale,(height/2f-e.y)/scale)
                invalidate()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                dragging=false
                parent.requestDisallowInterceptTouchEvent(false)
                if(e.actionMasked==MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
}
