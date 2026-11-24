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

    /** Line colour from the archive (0xRRGGBB); falls back to the Studio purple. */
    /** What the last edit from a line's panel changed, for its undo entry. */
    var editLabel = "Jadwal layanan"
    fun color(service: JSONObject?): Int {
        val raw = service?.optDouble("color", Double.NaN) ?: Double.NaN
        return if (raw.isFinite() && raw > 0) (raw.toLong().toInt() and 0xffffff) or 0xff000000.toInt() else StudioUi.BLUE
    }

    /** Short badge code: a trailing number ("Koridor 9" → 9), otherwise the first letter of the distinctive word. */
    fun code(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val last = words.lastOrNull() ?: return "?"
        if (last.all { it.isDigit() }) return last.takeLast(2)
        val generic = setOf("commuter","line","lin","koridor","kereta","bus","feri","lrt","mrt","rute","jalur","layanan")
        val word = words.lastOrNull { it.lowercase() !in generic } ?: last
        return word.first().uppercaseChar().toString()
    }

    private fun lp(top: Int = 0, host: Activity) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(host, top) }

    /** The running simulation's figures for every line (SimulationClock.lineSummary); null without one. */
    var summary: (((String?) -> Unit) -> Unit)? = null

    fun open(host: Activity, handle: Long, clock: Double, editable: Boolean, transaction: PanelEdit, changed: (String) -> Unit) {
        val all = JSONArray(services(handle))
        StudioUi.sheet(host, "Line") { column, dialog ->
            fun dp(n: Int) = StudioUi.dp(host, n)
            if (all.length() == 0) {
                column.addView(StudioUi.text(host, "Bangun line pertama untuk mengatur layanannya.", 16f))
                column.addView(StudioUi.button(host, "Kembali ke peta") { dialog.dismiss() })
            }
            val items = List(all.length()) { all.getJSONObject(it) }
            // Colour and vehicle come from each service's inspection; read once per sheet.
            val looks = items.associate { it.getInt("id") to runCatching { JSONObject(inspect(handle, it.getInt("id"), clock)) }.getOrNull() }
            fun vehicle(item: JSONObject) = looks[item.getInt("id")]?.optString("vehicle").orEmpty().ifEmpty { "Lainnya" }
            val running = items.count { it.optBoolean("running") }
            val headline = StudioUi.text(host, "${items.size} line · $running beroperasi", 13f, true, StudioUi.MUTED).apply {
                setPadding(dp(2), 0, 0, dp(8))
            }
            column.addView(headline)
            val list = LineList(host, StudioCurrency.current(host), looks) { id ->
                dialog.dismiss()
                details(host, handle, id, clock, editable, transaction, changed)
            }
            var search: EditText? = null
            var mode = "Semua"
            val modes = listOf("Semua") + items.map { vehicle(it) }.distinct().sorted()
            val tabs = LinearLayout(host).apply {
                background = StudioUi.pill(host, 0x1422204a, 16)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            fun render() {
                val query = search?.text?.toString().orEmpty().trim()
                val found = items.filter { it.getString("name").contains(query, ignoreCase = true) && (mode == "Semua" || vehicle(it) == mode) }
                list.show(found, items.isNotEmpty()) { search?.setText(""); mode = "Semua"; render() }
                val riders = list.totalRiders
                val profit = list.totalProfit
                headline.text = "${items.size} line · $running beroperasi" + when {
                    riders != null -> " · ${StudioFormat.count(riders)} penumpang/24 jam" +
                        (profit?.let { " · laba ${StudioFormat.money(it * 1e6, StudioCurrency.current(host))}/hari" } ?: "")
                    running > 0 -> "\nPenumpang dan uang tiap line muncul saat simulasi berjalan."
                    else -> ""
                }
            }
            if (items.size > 6) {
                search = EditText(host).apply {
                    hint = "Cari nama line"
                    setSingleLine()
                    typeface = StudioUi.face(host, true)
                    textSize = 15f
                    setTextColor(StudioUi.INK)
                    setHintTextColor(StudioUi.MUTED)
                    background = StudioUi.pill(host, StudioUi.WHITE, 24)
                    setPadding(dp(16), 0, dp(16), 0)
                    minHeight = dp(48)
                    setCompoundDrawables(StudioUi.glyph(host, StudioUi.Icon.SEARCH, StudioUi.MUTED), null, null, null)
                    compoundDrawablePadding = dp(8)
                }
                column.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            if (modes.size > 2) {
                val buttons = mutableListOf<android.widget.TextView>()
                modes.forEach { m ->
                    val tab = StudioUi.text(host, m, 13f, true, StudioUi.INK).apply {
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        minHeight = dp(40)
                        setPadding(dp(10), 0, dp(10), 0)
                        isClickable = true
                        setOnClickListener {
                            mode = m
                            buttons.forEach { b -> b.background = if (b === this) StudioUi.pill(host, StudioUi.WHITE, 12) else null }
                            render()
                        }
                        if (m == mode) background = StudioUi.pill(host, StudioUi.WHITE, 12)
                    }
                    buttons.add(tab)
                    tabs.addView(tab, LinearLayout.LayoutParams(-2, -2))
                }
                column.addView(android.widget.HorizontalScrollView(host).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(tabs)
                }, lp(10, host))
            }
            if (items.isNotEmpty()) column.addView(list.sorts { render() }, lp(10, host))
            column.addView(list.rows, lp(4, host))
            render()
            // web useToday: the figures asked again while the list shows (here every two seconds)
            val ask = summary
            if (ask != null) {
                val again = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return
                        ask { text ->
                            if (!dialog.isShowing) return@ask
                            list.figures = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                            render()
                            list.rows.postDelayed(this, 2000)
                        }
                    }
                }
                list.rows.post(again)
            }
        }
    }

    fun details(host: Activity, handle: Long, id: Int, clock: Double, editable: Boolean, transaction: PanelEdit, changed: (String) -> Unit) {
        val service = JSONObject(inspect(handle,id,clock))
        val route = ServiceStops(service)
        val upcoming = service.optJSONArray("upcoming")
        val times = service.optJSONArray("depart") ?: JSONArray()
        val shift = service.optDouble("shift", 0.0)
        val color = color(service)
        lateinit var footer: LinearLayout
        StudioUi.sheet(host, service.getString("name"), .6f, pinnedAction = { footer }) { column, dialog ->
            fun dp(n: Int) = StudioUi.dp(host, n)
            val head = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            head.addView(StudioUi.badge(host, code(service.getString("name")), color, 52), LinearLayout.LayoutParams(dp(52), dp(52)))
            val copy = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(StudioUi.text(host, if (route.names.isEmpty()) "Belum ada pemberhentian" else route.endpoints, 15f, color = StudioUi.INK).apply {
                typeface = StudioUi.heavy(host)
            })
            copy.addView(StudioUi.text(host, service.getString("vehicle") + " · " +
                if (service.optBoolean("running")) "Beroperasi" else "Belum beroperasi", 13f, true, StudioUi.MUTED))
            head.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            column.addView(head)
            val stats = LinearLayout(host)
            val fleet = service.optJSONArray("count")
            val cycle = service.optDouble("cycle", 0.0)
            listOf(
                "Armada sibuk" to (fleet?.optInt(0)?.toString() ?: "—"),
                "Panjang" to String.format(Locale.forLanguageTag("id-ID"),"%.1f km", service.getDouble("length") / 1000),
                "Putaran" to if (cycle > 0) "±${Math.round(cycle / 60)} mnt" else "—"
            ).forEachIndexed { i, (caption, value) ->
                stats.addView(StudioUi.stat(host, caption, value), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            column.addView(stats, lp(12, host))
            if (route.names.isNotEmpty()) {
                // Vertical strip in the line colour with each stop's riders; stops passed and skipped (LineStops.kt).
                val strip = LineStops.strip(host, handle, id, route, color, editable) { success, edit ->
                    dialog.dismiss()
                    transaction { now ->
                        if (LineStops.toast(host, edit.run(now), success)) {
                            editLabel = LineStops.LABEL
                            changed(service.getString("name"))
                        }
                        details(host, handle, id, clock, editable, transaction, changed)
                    }
                }
                column.addView(strip, lp(12, host))
                column.addView(StudioUi.section(host, "Keberangkatan dari ${route.names.first()}"))
                val entries = if (upcoming != null) {
                    (0 until minOf(3, upcoming.length())).map { i ->
                        val row = upcoming.getJSONArray(i)
                        StudioTimetable.time(row.getDouble(1) + shift) to route.destination(row)
                    }
                } else {
                    (0 until minOf(3, times.length())).map { StudioTimetable.time(times.getDouble(it) + shift) to "" }
                }
                if (entries.isEmpty()) {
                    column.addView(StudioUi.text(host, if (service.optBoolean("running")) "Belum ada jadwal dari pemberhentian ini dalam satu jam ke depan."
                        else "Layanan belum beroperasi.", 14f, true, StudioUi.MUTED).apply { setPadding(dp(4), 0, 0, 0) })
                } else entries.forEach { (time, destination) -> column.addView(StudioTimetable.departure(host,time,destination), lp(6, host)) }
            } else {
                column.addView(StudioUi.text(host,"Belum ada pemberhentian pada rute ini.",15f,true,StudioUi.MUTED), lp(12, host))
            }
            // web LineTopSpeed + LineProfile: the top speed, the speed limit and the line along its length
            LineProfileView.section(host, handle, id, color, if (editable) { kmh ->
                dialog.dismiss()
                transaction { now ->
                    val error = LineProfileView.setLimit(handle, id, kmh, now)
                    if (error != null) android.widget.Toast.makeText(host, error, android.widget.Toast.LENGTH_LONG).show()
                    else { editLabel = "Batas laju line"; changed(service.getString("name")) }
                    details(host, handle, id, clock, editable, transaction, changed)
                }
            } else null)?.let { column.addView(it, lp(14, host)) }
            // web LineFare: the line's own fare multiple, above its money
            column.addView(LineFare.row(host, handle, id, editable, transaction) {
                editLabel = "Kelipatan tarif"
                changed(service.getString("name"))
            }, lp(14, host))
            // web LineMoney: the line's last 30 whole days of fares and running cost
            FinanceHistory.lineMoney(host, service.optJSONArray("lineDays"), service.optInt("today"), StudioCurrency.current(host))?.let {
                column.addView(it, lp(14, host))
            }
            footer = LinearLayout(host)
            if (route.names.isNotEmpty()) footer.addView(StudioUi.button(host, if (editable) "Grafik" else "Grafik perjalanan & keberangkatan", icon = StudioUi.Icon.ROUTE) {
                dialog.dismiss()
                val editSchedule: (() -> Unit)? = if (editable) {
                    { edit(host,handle,id,clock,service,transaction,changed) }
                } else null
                StudioTimetable.show(host,handle,id,clock,service,editSchedule)
            }, LinearLayout.LayoutParams(0,-2,1f))
            if (editable) footer.addView(StudioUi.button(host,"Atur jadwal",StudioUi.Tone.PRIMARY) {
                dialog.dismiss()
                edit(host,handle,id,clock,service,transaction,changed)
            }, LinearLayout.LayoutParams(0,-2,1.4f).apply { if (route.names.isNotEmpty()) marginStart = StudioUi.dp(host, 8) })
        }
    }

    private fun edit(host: Activity, handle: Long, id: Int, clock: Double, service: JSONObject, transaction: PanelEdit, changed: (String) -> Unit) {
        ScheduleEditor(host, service,
            showTimetable = { StudioTimetable.show(host, handle, id, clock, service, null) },
            applySchedule = { values, reply -> transaction { now -> editLabel = "Jadwal layanan"; reply(JSONObject(update(handle, id, now, values.toString()))) } },
            onApplied = { updated ->
                changed(updated.getString("name"))
                details(host, handle, id, clock, true, transaction, changed)
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
