// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.widget.RadioButton
import android.widget.RadioGroup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor

internal object StudioTimetable {
    @JvmStatic private external fun query(handle: Long, id: Int, start: Double, station: Int): String
    fun time(at: Double): String {
        val minute = Math.floorMod(floor(at/60).toLong(),1440L)
        return "%02d:%02d".format(Locale.ROOT,minute/60,minute%60)
    }

    fun departure(host: Activity, time: String, destination: String): View = LinearLayout(host).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = StudioUi.card(host, 18)
        setPadding(StudioUi.dp(host,8),StudioUi.dp(host,7),StudioUi.dp(host,12),StudioUi.dp(host,7))
        addView(StudioUi.text(host, time, 15f, color = StudioUi.WHITE).apply {
            typeface = StudioUi.heavy(host)
            background = StudioUi.pill(host,StudioUi.INK,12)
            gravity = Gravity.CENTER
            setPadding(StudioUi.dp(host,10),StudioUi.dp(host,5),StudioUi.dp(host,10),StudioUi.dp(host,6))
        })
        addView(StudioUi.text(host, destination.ifEmpty { "Keberangkatan" }, 15f, true),LinearLayout.LayoutParams(0,-2,1f).apply {
            marginStart = StudioUi.dp(host,12)
        })
    }

    fun show(host: Activity, handle: Long, id: Int, clock: Double, service: JSONObject, editSchedule: (() -> Unit)?) {
        val route = ServiceStops(service)
        if (route.names.isEmpty()) return
        val shift = service.optDouble("shift", 0.0)
        var start = floor((clock + shift) / 3600) * 3600 - shift
        val fleet = service.optJSONArray("count")
        val noVehicles = fleet != null && fleet.length() == 3 && (0..2).all { fleet.optInt(it, -1) == 0 }
        var selectedStop = 0
        StudioUi.sheet(host,"Grafik perjalanan") { column, dialog ->
            val head = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(StudioUi.badge(host, ServicePanel.code(service.getString("name")), ServicePanel.color(service), 44),
                LinearLayout.LayoutParams(StudioUi.dp(host,44), StudioUi.dp(host,44)))
            val copy = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(StudioUi.text(host,service.getString("name"),16f,color = StudioUi.INK).apply { typeface = StudioUi.heavy(host) })
            copy.addView(StudioUi.text(host,route.endpoints,13f,true,StudioUi.MUTED))
            head.addView(copy, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = StudioUi.dp(host,12) })
            column.addView(head)
            val frame = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            column.addView(frame)
            column.addView(StudioUi.section(host,"Papan keberangkatan"))
            val stopButton = StudioUi.button(host, "", icon = StudioUi.Icon.PIN) {}
            column.addView(stopButton)
            val board = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            column.addView(board)
            fun display() {
                stopButton.text = "Pilih ${route.stopType} · ${route.names[selectedStop]}"
                stopButton.contentDescription = "Pilih ${route.stopType} keberangkatan. Saat ini ${route.numbered(selectedStop)}"
                val raw = query(handle,id,start,selectedStop)
                val state = if (raw == "null") null else JSONObject(raw)
                frame.removeAllViews()
                board.removeAllViews()
                val trips = state?.optJSONArray("trips") ?: JSONArray()
                val span = time(start + shift) + "–" + time(start + 7200 + shift)
                frame.addView(StudioUi.text(host,"$span · ${trips.length()} perjalanan",15f,color = StudioUi.INK).apply { typeface = StudioUi.heavy(host) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(host, 8) })
                if (trips.length() > 0 && state != null) {
                    frame.addView(object : View(host) {
                        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
                        init {
                            contentDescription = "${trips.length()} perjalanan dalam rentang $span"
                            background = StudioUi.pill(host, StudioUi.INK, 22)
                            clipToOutline = true
                        }
                        override fun onDraw(canvas: Canvas) {
                            val pad = StudioUi.dp(host,12).toFloat()
                            val w = width-2*pad
                            val h = height-3*pad
                            canvas.drawColor(StudioUi.INK)
                            pen.color = 0xff3c5366.toInt()
                            pen.strokeWidth = resources.displayMetrics.density
                            for (tick in 0..4) {
                                val x = pad + w*tick/4
                                pen.color = 0xff3c5366.toInt()
                                canvas.drawLine(x,pad,x,pad+h,pen)
                                pen.color = 0xffd6e8ef.toInt()
                                pen.textSize = resources.displayMetrics.scaledDensity*11
                                pen.style = Paint.Style.FILL
                                pen.textAlign = when(tick) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                                canvas.drawText(time(start+shift+tick*1800),x,height-pad*.35f,pen)
                            }
                            pen.color = 0xff3c5366.toInt()
                            val profiles = state.getJSONArray("profiles")
                            val outbound = profiles.optJSONArray(0)
                            if (outbound != null) for (i in 0 until outbound.length()) {
                                val y = pad + h*outbound.getJSONArray(i).getDouble(0).toFloat()
                                canvas.drawLine(pad,y,pad+w,y,pen)
                            }
                            canvas.save()
                            canvas.clipRect(pad,pad,pad+w,pad+h)
                            pen.style = Paint.Style.STROKE
                            pen.strokeWidth = resources.displayMetrics.density*1.7f
                            for (i in 0 until trips.length()) {
                                val trip = trips.getJSONArray(i)
                                val idx = trip.getInt(0)
                                val profile = profiles.optJSONArray(idx) ?: continue
                                val base = trip.getDouble(1)-start
                                pen.color = if (idx<3) 0xff5be49b.toInt() else 0xffffb020.toInt()
                                val path = Path()
                                for (j in 0 until profile.length()) {
                                    val point = profile.getJSONArray(j)
                                    val y = pad+h*point.getDouble(0).toFloat()
                                    val x = pad+w*((base+point.getDouble(1))/7200).toFloat()
                                    if (j==0) path.moveTo(x,y) else path.lineTo(x,y)
                                    path.lineTo(pad+w*((base+point.getDouble(2))/7200).toFloat(),y)
                                }
                                canvas.drawPath(path,pen)
                            }
                            canvas.restore()
                        }
                    }, LinearLayout.LayoutParams(-1,StudioUi.dp(host,150)).apply { topMargin = StudioUi.dp(host,6) })
                    val directions = buildString {
                        append("Hijau · ke ${route.terminus(0)}")
                        if (state.getJSONArray("profiles").length() > 3) append("\nJingga · ke ${route.terminus(1)}")
                    }
                    frame.addView(StudioUi.text(host,directions,13f,color = StudioUi.MUTED))
                }
                val nav = LinearLayout(host)
                for ((label, delta) in listOf("2 jam sebelumnya" to -7200, "2 jam berikutnya" to 7200)) {
                    nav.addView(StudioUi.button(host,label) { start += delta; display() },
                        LinearLayout.LayoutParams(0,-2,1f).apply { if (delta > 0) marginStart = StudioUi.dp(host,8) })
                }
                frame.addView(nav, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host,6) })
                val departures = state?.optJSONArray("board") ?: JSONArray()
                if (departures.length() == 0) {
                    val reason = if (state == null) "Jadwal belum tersedia untuk ${route.names[selectedStop]}."
                        else "Tidak ada keberangkatan dari ${route.names[selectedStop]} pukul $span."
                    val guidance = when {
                        trips.length() == 0 && noVehicles -> "Armada hari kerja untuk ketiga periode berjumlah nol."
                        trips.length() == 0 && !service.optBoolean("running") -> "Line ini belum beroperasi."
                        else -> "Pilih ${route.stopType} lain atau lihat dua jam berikutnya."
                    }
                    board.addView(StudioUi.text(host,"$reason $guidance",14f,true,StudioUi.MUTED).apply {
                        background = StudioUi.card(host, 18)
                        setPadding(StudioUi.dp(host,14),StudioUi.dp(host,12),StudioUi.dp(host,14),StudioUi.dp(host,12))
                    })
                    if (trips.length() == 0 && editSchedule != null) {
                        board.addView(StudioUi.button(host, "Atur jadwal", StudioUi.Tone.PRIMARY) {
                            dialog.dismiss()
                            editSchedule()
                        })
                    }
                    return
                }
                var rendered = 0
                fun showMore() {
                    val end = minOf(rendered + 6, departures.length())
                    for (index in rendered until end) {
                        val entry = departures.getJSONArray(index)
                        board.addView(departure(host, time(entry.getDouble(1) + shift), route.destination(entry)),
                            LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host,6) })
                    }
                    rendered = end
                    if (rendered < departures.length()) {
                        val more = StudioUi.button(host, "Tampilkan ${minOf(6, departures.length() - rendered)} keberangkatan lagi") {}
                        more.setOnClickListener { board.removeView(more); showMore() }
                        board.addView(more)
                    }
                }
                showMore()
            }
            stopButton.setOnClickListener {
                var pending = selectedStop
                lateinit var confirm: View
                StudioUi.sheet(host, "Pilih ${route.stopType} keberangkatan", pinnedAction = { confirm }) { choices, picker ->
                    choices.addView(StudioUi.text(host, service.getString("name"), 14f, true, StudioUi.MUTED))
                    val group = RadioGroup(host)
                    choices.addView(group)
                    route.names.indices.forEach { i ->
                        val choice = RadioButton(host).apply {
                            this.id = View.generateViewId()
                            text = route.numbered(i)
                            textSize = 16f
                            typeface = StudioUi.face(host, true)
                            setTextColor(StudioUi.INK)
                            minHeight = StudioUi.dp(host,48)
                            setPadding(StudioUi.dp(host,8),0,0,0)
                            setOnClickListener { pending = i }
                        }
                        group.addView(choice)
                        if (i == selectedStop) group.check(choice.id)
                    }
                    confirm = LinearLayout(host).apply {
                        addView(StudioUi.button(host,"Batal") { picker.dismiss() },LinearLayout.LayoutParams(0,-2,1f))
                        addView(StudioUi.button(host,"Tampilkan",StudioUi.Tone.PRIMARY) {
                            selectedStop = pending
                            picker.dismiss()
                            display()
                        },LinearLayout.LayoutParams(0,-2,1.5f).apply { marginStart = StudioUi.dp(host,6) })
                    }
                }
            }
            display()
        }
    }
}
