package world.nativegame

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object ServicePanel {
    @JvmStatic private external fun services(handle: Long): String
    @JvmStatic private external fun inspect(handle: Long, id: Int, now: Double): String
    @JvmStatic private external fun update(handle: Long, id: Int, now: Double, params: String): String

    fun open(host: Activity, handle: Long, clock: Double, editable: Boolean, changed: (String) -> Unit) {
        val all = JSONArray(services(handle))
        StudioUi.sheet(host, "Jalur & layanan") { column, dialog ->
            if (all.length() == 0) {
                column.addView(StudioUi.text(host, "Bangun jalur pertama untuk mengatur layanannya.", 16f))
                column.addView(StudioUi.button(host, "Kembali ke peta") { dialog.dismiss() })
            }
            val items = List(all.length()) { all.getJSONObject(it) }
            val rows = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            var search: EditText? = null
            fun render(query: String) {
                rows.removeAllViews()
                val found = items.filter { it.getString("name").contains(query.trim(), ignoreCase = true) }
                if (found.isEmpty() && items.isNotEmpty()) {
                    rows.addView(StudioUi.text(host,"Jalur tidak ditemukan.",15f))
                    rows.addView(StudioUi.button(host,"Hapus pencarian") { search?.setText("") })
                }
                found.forEach { route ->
                    val running = route.optBoolean("running")
                    val card = LinearLayout(host).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        isFocusable = true
                        background = StudioUi.panel(host, StudioUi.BACKGROUND, 14)
                        setPadding(StudioUi.dp(host,12),StudioUi.dp(host,8),StudioUi.dp(host,10),StudioUi.dp(host,8))
                        minimumHeight = StudioUi.dp(host,56)
                        setOnClickListener {
                            dialog.dismiss()
                            details(host,handle,route.getInt("id"),clock,editable,changed)
                        }
                    }
                    val copy = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                    copy.addView(StudioUi.text(host,route.getString("name"),17f,true))
                    copy.addView(StudioUi.text(host,if (running) "Beroperasi" else "Belum beroperasi",13f,
                        color = if (running) 0xff156444.toInt() else StudioUi.MUTED))
                    card.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
                    card.addView(StudioUi.text(host,"›",23f,true,StudioUi.MUTED).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    rows.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host,5) })
                }

            }
            if (items.size > 6) {
                search = EditText(host).apply {
                    hint = "Cari nama jalur"
                    setSingleLine()
                    typeface = StudioUi.face(host)
                    minHeight = StudioUi.dp(host,48)
                }
                column.addView(search)
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            column.addView(rows)
            render("")
        }
    }

    fun details(host: Activity, handle: Long, id: Int, clock: Double, editable: Boolean, changed: (String) -> Unit) {
        val service = JSONObject(inspect(handle,id,clock))
        val route = ServiceStops(service)
        val upcoming = service.optJSONArray("upcoming")
        val times = service.optJSONArray("depart") ?: JSONArray()
        val shift = service.optDouble("shift", 0.0)
        StudioUi.sheet(host, service.getString("name")) { column, dialog ->
            fun line(text: String, bold: Boolean = false) {
                column.addView(StudioUi.text(host, text, if (bold) 16f else 15f, bold),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(host, 5) })
            }
            val overview = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.panel(host, 0xff152c3f.toInt(), 16, 0xff152c3f.toInt())
                setPadding(StudioUi.dp(host,13), StudioUi.dp(host,10), StudioUi.dp(host,13), StudioUi.dp(host,10))
            }
            column.addView(overview,LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host,8) })
            overview.addView(StudioUi.text(host, service.getString("vehicle") + "  ·  " +
                String.format(Locale.forLanguageTag("id-ID"),"%.1f km", service.getDouble("length") / 1000),
                13f, true, 0xffbfe5ff.toInt()))
            if (route.names.isEmpty()) {
                overview.addView(StudioUi.text(host,"Belum ada pemberhentian pada rute ini.",15f,color = StudioUi.WHITE))
            } else {
                overview.addView(StudioUi.text(host,route.endpoints,18f,true,StudioUi.WHITE))
                val cycle = service.optDouble("cycle", 0.0)
                val duration = if (cycle > 0) "  ·  ±${Math.round(cycle / 60)} menit per putaran" else ""
                overview.addView(StudioUi.text(host,"${route.names.size} ${route.stopType}$duration",14f,color = 0xffd5e7ef.toInt()))
                val itinerary = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                if (route.unnamedCount > 0) itinerary.addView(StudioUi.text(host,
                    "Pemberhentian yang belum bernama ditampilkan menurut urutannya di rute.", 14f, color = StudioUi.MUTED))
                route.names.indices.forEach { index ->
                    itinerary.addView(StudioUi.text(host, route.numbered(index), 15f).apply {
                        setPadding(0, StudioUi.dp(host, 3), 0, StudioUi.dp(host, 3))
                    })
                }
                val show = "Lihat urutan ${route.names.size} ${route.stopType}"
                lateinit var toggle: android.widget.Button
                toggle = StudioUi.button(host, show) {
                    val expand = itinerary.visibility != View.VISIBLE
                    itinerary.visibility = if (expand) View.VISIBLE else View.GONE
                    toggle.text = if (expand) "Tutup urutan ${route.stopType}" else show
                }
                column.addView(toggle)
                column.addView(itinerary)
                line("Keberangkatan dari ${route.names.first()}", true)
                val entries = if (upcoming != null) {
                    (0 until minOf(3, upcoming.length())).map { i ->
                        val row = upcoming.getJSONArray(i)
                        StudioTimetable.time(row.getDouble(1) + shift) to route.destination(row)
                    }
                } else {
                    (0 until minOf(3, times.length())).map { StudioTimetable.time(times.getDouble(it) + shift) to "" }
                }
                if (entries.isEmpty()) {
                    line(if (service.optBoolean("running")) "Belum ada jadwal dari pemberhentian ini dalam satu jam ke depan."
                        else "Layanan belum beroperasi.")
                } else entries.forEach { (time, destination) -> column.addView(StudioTimetable.departure(host,time,destination)) }
                column.addView(StudioUi.button(host,"Grafik perjalanan & keberangkatan",StudioUi.Tone.PRIMARY) {
                    dialog.dismiss()
                    val editSchedule: (() -> Unit)? = if (editable) {
                        { edit(host,handle,id,clock,service,changed) }
                    } else null
                    StudioTimetable.show(host,handle,id,clock,service,editSchedule)
                }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host, 10) })
            }
            if (editable) column.addView(StudioUi.button(host,"Atur jadwal",StudioUi.Tone.BLUE) {
                dialog.dismiss()
                edit(host,handle,id,clock,service,changed)
            }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(host, 5) })
        }
    }

    private fun edit(host: Activity, handle: Long, id: Int, clock: Double, service: JSONObject, changed: (String) -> Unit) {
        ScheduleEditor(host, service,
            showTimetable = { StudioTimetable.show(host, handle, id, clock, service, null) },
            applySchedule = { values -> JSONObject(update(handle, id, clock, values.toString())) },
            onApplied = { updated ->
                changed(updated.getString("name"))
                details(host, handle, id, clock, true, changed)
            }
        ).show()
    }
}

/** The archive can carry unnamed stops. Keep the original order so station indices match the timetable. */
internal class ServiceStops(service: JSONObject) {
    val vehicleUnit = when (service.optString("vehicle")) { "Bus" -> "bus"; "Feri" -> "kapal"; else -> "kereta" }
    val stopType = when (vehicleUnit) { "bus" -> "halte"; "kapal" -> "dermaga"; else -> "stasiun" }
    private val stops = service.optJSONArray("stops") ?: JSONArray()
    val names = List(stops.length()) { i ->
        stops.optString(i).trim().ifEmpty { "${stopType.replaceFirstChar { it.uppercaseChar() }} ${i + 1}" }
    }
    val unnamedCount = service.optInt("unnamedStops", (0 until stops.length()).count { stops.optString(it).isBlank() })
    fun numbered(index: Int): String {
        val stop = names[index]
        return if (stop.equals("$stopType ${index + 1}", ignoreCase = true)) stop else "${index + 1}.  $stop"
    }
    val endpoints get() = when {
        names.isEmpty() -> ""
        names.size == 1 -> names.first()
        names.first() == names.last() -> "Rute melingkar dari ${names.first()}"
        else -> "${names.first()} – ${names.last()}"
    }
    private val directions = service.optJSONArray("directionNames")
    fun terminus(direction: Int): String = directions?.optString(direction)?.trim().orEmpty().ifEmpty {
        if (direction == 0) names.last() else names.first()
    }
    fun destination(row: JSONArray): String = row.optString(2).trim().ifEmpty { terminus(row.getInt(0)) }
}
