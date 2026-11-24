package world.nativegame

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
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

    fun open(host: Activity, handle: Long, clock: Double, editable: Boolean, transaction: PanelEdit, changed: (String) -> Unit) {
        val all = JSONArray(services(handle))
        StudioUi.sheet(host, "Lini") { column, dialog ->
            fun dp(n: Int) = StudioUi.dp(host, n)
            if (all.length() == 0) {
                column.addView(StudioUi.text(host, "Bangun lini pertama untuk mengatur layanannya.", 16f))
                column.addView(StudioUi.button(host, "Kembali ke peta") { dialog.dismiss() })
            }
            val items = List(all.length()) { all.getJSONObject(it) }
            // Colour and vehicle come from each service's inspection; read once per sheet.
            val looks = items.associate { it.getInt("id") to runCatching { JSONObject(inspect(handle, it.getInt("id"), clock)) }.getOrNull() }
            fun vehicle(item: JSONObject) = looks[item.getInt("id")]?.optString("vehicle").orEmpty().ifEmpty { "Lainnya" }
            val running = items.count { it.optBoolean("running") }
            column.addView(StudioUi.text(host, "${items.size} layanan · $running beroperasi", 13f, true, StudioUi.MUTED).apply {
                setPadding(dp(2), 0, 0, dp(8))
            })
            val rows = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            var search: EditText? = null
            var mode = "Semua"
            val modes = listOf("Semua") + items.map { vehicle(it) }.distinct().sorted()
            val tabs = LinearLayout(host).apply {
                background = StudioUi.pill(host, 0x1422204a, 16)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            fun card(group: List<JSONObject>): View {
                val first = group.first()
                val look = looks[first.getInt("id")]
                val color = color(look)
                val name = first.getString("name")
                val anyRunning = group.any { it.optBoolean("running") }
                val card = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = true
                    isClickable = true
                    background = StudioUi.card(host, 18, 0xf2ffffff.toInt())
                    contentDescription = "$name, ${group.size} layanan, ${if (anyRunning) "beroperasi" else "belum beroperasi"}"
                    setOnClickListener {
                        if (group.size == 1) {
                            dialog.dismiss()
                            details(host, handle, first.getInt("id"), clock, editable, transaction, changed)
                        } else StudioUi.sheet(host, name) { list, picker ->
                            group.forEachIndexed { i, route ->
                                val stops = looks[route.getInt("id")]?.let { ServiceStops(it).endpoints }.orEmpty()
                                list.addView(StudioUi.row(host, null, 0, "Layanan ${i + 1}" + if (stops.isNotEmpty()) " · $stops" else "",
                                    if (route.optBoolean("running")) "Beroperasi" else "Belum beroperasi") {
                                    picker.dismiss(); dialog.dismiss()
                                    details(host, handle, route.getInt("id"), clock, editable, transaction, changed)
                                }.apply { addView(StudioUi.badge(host, code(name), color, 34), 0, LinearLayout.LayoutParams(dp(34), dp(34))) }, lp(6, host))
                            }
                        }
                    }
                }
                // A compact row (web LinesTab): code badge, name and state, so a 28-line city fits on two screens.
                card.orientation = LinearLayout.HORIZONTAL
                card.gravity = android.view.Gravity.CENTER_VERTICAL
                card.minimumHeight = dp(64)
                card.setPadding(dp(10), dp(8), dp(12), dp(8))
                card.addView(StudioUi.badge(host, code(name), color, 40), LinearLayout.LayoutParams(dp(40), dp(40)))
                val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                body.addView(StudioUi.text(host, name, 15f, color = StudioUi.INK).apply {
                    typeface = StudioUi.heavy(host); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                body.addView(StudioUi.text(host, (if (group.size > 1) "${group.size} layanan · " else "") +
                    if (anyRunning) "Beroperasi" else "Belum beroperasi", 12.5f, true, if (anyRunning) StudioUi.GOOD else StudioUi.MUTED))
                card.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                return card
            }
            fun render(query: String) {
                rows.removeAllViews()
                val found = items.filter { it.getString("name").contains(query.trim(), ignoreCase = true) && (mode == "Semua" || vehicle(it) == mode) }
                if (found.isEmpty() && items.isNotEmpty()) {
                    rows.addView(StudioUi.text(host,"Lini tidak ditemukan.",15f,true,StudioUi.MUTED).apply { setPadding(dp(4),dp(12),0,dp(6)) })
                    rows.addView(StudioUi.button(host,"Hapus pencarian") { search?.setText(""); mode = "Semua"; render("") })
                }
                // Services sharing a name (both directions, variants) form one collectible card.
                val groups = found.groupBy { it.getString("name").trim().lowercase() }.values.toList()
                groups.forEach { group -> rows.addView(card(group), lp(6, host)) }
            }
            if (items.size > 6) {
                search = EditText(host).apply {
                    hint = "Cari nama lini"
                    setSingleLine()
                    typeface = StudioUi.face(host, true)
                    textSize = 16f
                    setTextColor(StudioUi.INK)
                    setHintTextColor(StudioUi.MUTED)
                    background = StudioUi.pill(host, StudioUi.WHITE, 26)
                    setPadding(dp(18), 0, dp(18), 0)
                    minHeight = dp(50)
                }
                column.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            if (modes.size > 2) {
                val buttons = mutableListOf<android.widget.TextView>()
                modes.forEach { m ->
                    val tab = StudioUi.text(host, m, 13f, true, StudioUi.INK).apply {
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        minHeight = dp(36)
                        setPadding(dp(10), 0, dp(10), 0)
                        isClickable = true
                        setOnClickListener {
                            mode = m
                            buttons.forEach { b -> b.background = if (b === this) StudioUi.pill(host, StudioUi.WHITE, 12) else null }
                            render(search?.text?.toString().orEmpty())
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
            column.addView(rows)
            render("")
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
                // Vertical strip in the line colour; termini and interchanges stand out.
                val strip = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = StudioUi.card(host, 22)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                fun stop(index: Int): View = LinearLayout(host).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(34)
                    val terminal = index == 0 || index == route.names.lastIndex
                    addView(object : View(host) {
                        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
                        override fun onDraw(canvas: Canvas) {
                            val cx = width / 2f
                            pen.style = Paint.Style.FILL
                            pen.color = color
                            val top = if (index == 0) height / 2f else 0f
                            val bottom = if (index == route.names.lastIndex) height / 2f else height.toFloat()
                            canvas.drawRect(cx - dp(3), top, cx + dp(3), bottom, pen)
                            val r = dp(if (terminal) 9 else 6).toFloat()
                            pen.color = StudioUi.WHITE
                            canvas.drawCircle(cx, height / 2f, r, pen)
                            pen.style = Paint.Style.STROKE
                            pen.strokeWidth = dp(if (terminal) 4 else 3).toFloat()
                            pen.color = StudioUi.INK
                            canvas.drawCircle(cx, height / 2f, r - pen.strokeWidth / 2, pen)
                        }
                    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24), dp(34)))
                    addView(StudioUi.text(host, route.numbered(index), if (terminal) 15f else 14f, terminal, StudioUi.INK).apply {
                        if (terminal) typeface = StudioUi.heavy(host)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                }
                val visible = 8
                route.names.indices.take(visible).forEach { strip.addView(stop(it)) }
                val rest = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                route.names.indices.drop(visible).forEach { rest.addView(stop(it)) }
                strip.addView(rest)
                if (route.unnamedCount > 0) strip.addView(StudioUi.text(host,
                    "Pemberhentian yang belum bernama ditampilkan menurut urutannya di rute.", 12f, true, StudioUi.MUTED))
                if (route.names.size > visible) {
                    val show = "+${route.names.size - visible} ${route.stopType} lagi · lihat urutan"
                    lateinit var toggle: android.widget.TextView
                    toggle = StudioUi.text(host, show, 13f, true, StudioUi.BLUE).apply {
                        setPadding(dp(34), dp(6), 0, dp(2))
                        isClickable = true
                        setOnClickListener {
                            val expand = rest.visibility != View.VISIBLE
                            rest.visibility = if (expand) View.VISIBLE else View.GONE
                            toggle.text = if (expand) "Tutup urutan ${route.stopType}" else show
                        }
                    }
                    strip.addView(toggle)
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
                    else changed(service.getString("name"))
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
