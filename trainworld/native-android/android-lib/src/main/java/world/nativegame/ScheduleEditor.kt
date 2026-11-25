// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Edits a local draft. Only the primary action passes it to the simulation. */
internal class ScheduleEditor(
    private val host: Activity,
    private val service: JSONObject,
    private val showTimetable: () -> Unit,
    private val applySchedule: (JSONObject, (JSONObject) -> Unit) -> Unit,
    private val onApplied: (JSONObject) -> Unit,
    /** The new-line default headways under the weekends (ScheduleDefaults.kt), given the sheet. */
    private val defaults: ((android.app.Dialog) -> View?)? = null
) {
    private val route = ServiceStops(service)
    /** Web `schedule.demandName` and `levelTimes` (game/periods.ts): each demand level and its hours. */
    private val periods = listOf("Tinggi", "Sedang", "Rendah")
    private val periodHours = listOf("06:00-20:00", "20:00-24:00", "00:00-06:00")
    private val periodTip = listOf("tinggi", "sedang", "rendah")
    private val countDraft = Array(3) { service.getJSONArray("count").optInt(it, 0).toString() }
    /** Headways are typed in minutes as the web shows them (`headwayText`), kept in seconds for the sim. */
    private val headwayDraft = Array(3) { minutes(service.getJSONArray("headway").optInt(it, listOf(180, 360, 720)[it]).toDouble()) }
    private var byInterval = service.getJSONArray("headway").length() == 3
    private val fields = mutableListOf<EditText>()
    private val hints = mutableListOf<TextView>()
    private val modeButtons = mutableListOf<Button>()
    private val preview = FrequencyChart(host)
    /** Each demand level's round trip (web `roundTripByLevel`), for the headway a count gives. */
    private val cycles = DoubleArray(3) { service.optJSONArray("cycles")?.optDouble(it) ?: service.optDouble("cycle", 0.0) }
    /** The demand level running now in the line's local time (web: the row marked `now`). */
    private val nowLevel = service.optInt("level", -1)
    private val colour = service.optInt("color", 0x1cb0f6) or Color.BLACK
    private val lineId = service.optInt("id", -1)
    private fun dp(v: Int) = StudioUi.dp(host, v)
    private fun gap(v: Int) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(v) }
    private fun group() = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private fun section(label: String) = StudioUi.section(host,label)
    private fun TextView.single() { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    /** A 40 dp round stepper inside its 48 dp touch target: soft, or solid ink for the main "+". */
    private fun stepper(description: String, direction: Int, solid: Boolean, click: () -> Unit) =
        StudioUi.iconButton(host, description, if (direction < 0) StudioUi.Icon.MINUS else StudioUi.Icon.PLUS) { click() }.apply {
            val face = { fill: Int -> android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill)
            }, dp(4)) }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), face(if (solid) StudioUi.NAVY_EDGE else 0x2622204a))
                addState(intArrayOf(), face(if (solid) StudioUi.INK else 0x1422204a))
            }
            setTextColor(if (solid) StudioUi.WHITE else StudioUi.INK)
            setPadding(0, 0, 0, 0)
            textSize = 18f
        }
    private fun watch(input: EditText, callback: () -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = callback()
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun input(label: String, value: String, numeric: Boolean): EditText = EditText(host).apply {
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine()
        typeface = StudioUi.face(host, numeric)
        textSize = 16f
        setTextColor(StudioUi.INK)
        setSelectAllOnFocus(numeric)
        background = StudioUi.pill(host, StudioUi.WHITE, 16)
        minHeight = dp(48)
        gravity = if (numeric) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        contentDescription = label
        setText(value)
    }

    companion object {
        /** Web `HEADWAY_MIN_S`, `HEADWAY_MAX_S` (game/world.ts). */
        const val HEADWAY_MIN_S = 60
        const val HEADWAY_MAX_S = 14400
        private val number = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply { maximumFractionDigits = 2 }
        /** Seconds as the minutes typed and shown: "5", "5,37". */
        fun minutes(s: Double): String = number.format(s / 60)
        /** Minutes typed back to seconds; null when not a number. */
        fun seconds(text: String): Int? {
            val minutes = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            if (minutes <= 0) return 0
            return (Math.round(minutes.coerceAtMost(240.0) * 12) * 5).toInt().coerceIn(HEADWAY_MIN_S, HEADWAY_MAX_S)
        }
        /**
         * Web `nextHeadway`: to the whole minute above or below (15 minutes at a time from an hour
         * up), from no service plus 15 minutes and minus 5, minus at the 1-minute limit no service.
         */
        fun nextHeadway(s: Int, dir: Int): Int {
            if (s <= 0) return if (dir > 0) 900 else 300
            val long = if (dir > 0) s >= 3600 else s > 3600
            val k = if (long) 15 else 1
            val m = s / 60.0 / k
            val near = Math.round(m).toDouble()
            val whole = kotlin.math.abs(m - near) < 1e-6
            if (dir > 0) return minOf(HEADWAY_MAX_S, ((if (whole) near + 1 else kotlin.math.ceil(m)) * k * 60).toInt())
            if (s <= HEADWAY_MIN_S) return 0
            return maxOf(HEADWAY_MIN_S, ((if (whole) near - 1 else kotlin.math.floor(m)) * k * 60).toInt())
        }
    }

    /** Web `limitsFrom` per level: what the track lets the line run, null where nothing limits it. */
    private val limits = List(3) { service.optJSONArray("limits")?.optJSONObject(it) }
    private val tram = service.optString("vehicle") == "Trem"
    /** Web capacityKinds.ts titles by kind and whether the place reads "di" (at) or "dekat" (near). */
    private fun kindWords(kind: Int) = TroubleList.kindWords(kind, tram)
    /** Web `formatDuration` for Indonesian (no own units): "4m00s", "1h05m", "45s". */
    private fun duration(s: Double): String {
        val t = Math.round(s); val h = t / 3600; val m = t / 60 % 60; val sec = t % 60
        return when { t >= 3600 -> String.format(Locale.ROOT, "%dh%02dm", h, m); t >= 60 -> String.format(Locale.ROOT, "%dm%02ds", m, sec); else -> "${t}s" }
    }
    /** Web `limitText` + `limitWait`: "Putar balik di Hudson A maksimal satu kereta tiap 4m00s, jadi kereta selebihnya menunggu di ujung". */
    private fun limitWait(level: Int): String? {
        val l = limits[level] ?: return null
        val (title, at) = kindWords(l.optInt("kind"))
        val place = l.optString("place")
        val what = if (place.isEmpty()) title else "$title ${if (at) "di" else "dekat"} $place"
        val unit = route.vehicleUnit.let { if (tram) "trem" else it }
        val text = if (l.isNull("s")) "$what penuh" else "$what maksimal satu $unit tiap ${duration(l.getDouble("s"))}"
        return "$text, jadi $unit selebihnya menunggu di ujung"
    }
    /** Web `trainCap`: the whole trains the track lets run at a level, plus one so one is held. */
    private fun trainCap(level: Int): Int {
        val capS = limits[level]?.takeIf { !it.isNull("capS") }?.getDouble("capS") ?: return 128
        val trip = cycles[level]
        if (!(trip > 0)) return 128
        val room = kotlin.math.floor(trip / capS + 0.01).toInt()
        return minOf(128, maxOf(1, room) + 1)
    }

    fun show() {
        lateinit var footer: LinearLayout
        StudioUi.sheet(host, "Jadwal", .94f, pinnedAction = { footer }) { body, dialog ->
            // compact header: small badge, the name and the route on one line each
            val titleRow = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(LineLook.badge(host, service.optInt("id", -1), service.getString("name"), colour, 32), LineLook.params(host, 32))
            val heading = group()
            val routeTitle = StudioUi.text(host, service.getString("name"), 15f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(host); single() }
            heading.addView(routeTitle)
            val summary = listOf(service.getString("vehicle"), route.endpoints).filter { it.isNotBlank() }.joinToString("  ·  ")
            heading.addView(StudioUi.text(host, summary, 12f, true, StudioUi.MUTED).apply { single(); contentDescription = summary })
            titleRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            body.addView(titleRow, gap(0))
            val card = group().apply {
                background = StudioUi.card(host, 14)
                setPadding(dp(10), dp(5), dp(10), dp(6))
            }
            card.addView(StudioUi.text(host, "Pratinjau frekuensi · satu jam", 12f, true, StudioUi.MUTED))
            card.addView(preview, LinearLayout.LayoutParams(-1, dp(66)))
            // web PastDay: average load, vehicles that left full and riders left on the platform, as one line
            Crowding.pastDayLine(host, lineId)?.let { card.addView(it, gap(4)) }
            body.addView(card, gap(8))

            // the weekday heading and "Atur dengan" segmented control share one row
            val modeRow = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            modeRow.addView(section("Hari kerja"), LinearLayout.LayoutParams(0, -2, 1f))
            val selector = LinearLayout(host).apply {
                background = StudioUi.pill(host, 0x1422204a, 18)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "Atur dengan"
            }
            listOf(route.vehicleUnit.replaceFirstChar { it.uppercase() }, "Selang").forEachIndexed { index, label ->
                val btn = StudioUi.button(host, label) {
                    val nextMode = index == 1
                    if (byInterval != nextMode) {
                        byInterval = nextMode
                        val next = if (byInterval) headwayDraft else countDraft
                        fields.forEachIndexed { i, edit -> edit.error = null; edit.setText(next[i]) }
                        refresh()
                    }
                }
                btn.minHeight = dp(40); btn.minimumHeight = dp(40)
                btn.textSize = 13f
                btn.setPadding(dp(14), 0, dp(14), 0)
                btn.contentDescription = "Atur dengan ${label.lowercase()}"
                selector.addView(btn, LinearLayout.LayoutParams(-2, dp(40)).apply { if (index == 1) marginStart = dp(4) })
                modeButtons.add(btn)
            }
            modeRow.addView(selector)
            body.addView(modeRow, gap(4))
            periods.forEachIndexed { i, period ->
                val row = LinearLayout(host).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(4), dp(2), dp(4))
                    background = StudioUi.card(host, 14)
                    minimumHeight = dp(52)
                }
                val text = group()
                // web LevelName: the level's crowding dot before its name, its peak and bar below (Crowding.kt)
                val crowd = Crowding.level(lineId, i)
                val name = StudioUi.text(host, if (i == nowLevel) "$period · sekarang" else period, 14f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(host); single() }
                if (crowd == null) text.addView(name) else text.addView(LinearLayout(host).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Crowding.dot(host, crowd), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(5) })
                    addView(name)
                })
                if (i == nowLevel) row.background = StudioUi.card(host, 14, StudioUi.TINT_YELLOW)
                val hint = StudioUi.text(host, "", 11.5f, true, StudioUi.MUTED)
                text.addView(hint)
                hints.add(hint)
                if (crowd != null) {
                    // the peak and the stretches' bar side by side; the other periods' loads stay in the dot's description
                    val grade = crowd.optInt("grade")
                    val load = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
                    val tip = Crowding.tip(crowd)
                    load.addView(StudioUi.text(host, tip.replace("\n", " · "), 11.5f, true,
                        if (grade >= 3) StudioUi.BAD else if (grade == 2) 0xffb3541e.toInt() else StudioUi.INK).apply { single(); contentDescription = tip })
                    Crowding.strip(host, crowd)?.let { load.addView(it, LinearLayout.LayoutParams(dp(44), dp(4)).apply { marginStart = dp(6) }) }
                    text.addView(load)
                }
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                val initial = (if (byInterval) headwayDraft else countDraft)[i]
                val edit = input(period, initial, true).apply {
                    // minutes may carry a decimal comma (5,37)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    background = null
                    typeface = StudioUi.heavy(host)
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                }
                fields.add(edit)
                fun move(direction: Int) {
                    if (byInterval) {
                        edit.setText(minutes(nextHeadway(seconds(edit.text.toString()) ?: 0, direction).toDouble()))
                        return
                    }
                    val current = edit.text.toString().toIntOrNull() ?: 0
                    // web: "+" stops one past what the track lets run, and says why
                    if (direction > 0 && current >= trainCap(i)) {
                        limitWait(i)?.let { android.widget.Toast.makeText(host, it, android.widget.Toast.LENGTH_LONG).show() }
                        return
                    }
                    edit.setText((current + direction).coerceIn(0, 128).toString())
                }
                // round stepper: a soft minus and a solid plus
                row.addView(stepper("Kurangi permintaan ${period.lowercase()}", -1, solid = false) { move(-1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(edit, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(stepper("Tambah permintaan ${period.lowercase()}", 1, solid = true) { move(1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                body.addView(row, gap(6))
                watch(edit) {
                    (if (byInterval) headwayDraft else countDraft)[i] = edit.text.toString()
                    refresh()
                }
            }

            // web DaySections: the weekends' own section, the share of the weekdays' trains or, set by
            // hand, the trains at each level ("Kereta akhir pekan diatur manual"); folded to a summary row
            val shareStart = service.optInt("weekend", 100).toString()
            val manual = service.optBoolean("weekendManual")
            val weekendStart = Array(3) { service.optJSONArray("weekendCounts")?.optInt(it, 0) ?: 0 }
            val weekendBox = group().apply { visibility = View.GONE }
            val weekendHead = LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                background = StudioDepot.touch(host, 0xd9ffffff.toInt(), 14)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isClickable = true
            }
            val weekendText = group()
            weekendText.addView(StudioUi.text(host, "Akhir pekan & libur nasional", 13.5f, true, StudioUi.INK).apply { single() })
            val weekendSummary = StudioUi.text(host, "", 11.5f, true, StudioUi.MUTED).apply { single() }
            weekendText.addView(weekendSummary)
            weekendHead.addView(weekendText, LinearLayout.LayoutParams(0, -2, 1f))
            val weekendArrow = StudioUi.text(host, "▾", 16f, true, StudioUi.MUTED)
            weekendHead.addView(weekendArrow)
            body.addView(weekendHead, gap(10))
            body.addView(weekendBox)
            fun openWeekend(open: Boolean) {
                weekendBox.visibility = if (open) View.VISIBLE else View.GONE
                weekendArrow.text = if (open) "▴" else "▾"
                weekendHead.contentDescription = "Akhir pekan dan hari libur nasional, ${weekendSummary.text}. " + if (open) "Ketuk untuk melipat" else "Ketuk untuk membuka"
            }
            weekendHead.setOnClickListener { openWeekend(weekendBox.visibility != View.VISIBLE) }
            val weekend = input("Porsi kereta akhir pekan terhadap kereta hari kerja", shareStart, true).apply {
                background = null; typeface = StudioUi.heavy(host); setPadding(0, 0, 0, 0)
            }
            fun compactRow() = LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(2), 0)
                minimumHeight = dp(48)
                background = StudioUi.card(host, 14)
            }
            val shareRow = compactRow()
            shareRow.addView(StudioUi.text(host, "Porsi dari hari kerja (%)", 13.5f, true), LinearLayout.LayoutParams(0, -2, 1f))
            shareRow.addView(weekend, LinearLayout.LayoutParams(dp(72), dp(48)))
            weekendBox.addView(shareRow, gap(4))
            val manualNote = StudioUi.text(host, "Kereta akhir pekan diatur manual. Mengubah porsi mengembalikannya ke porsi.", 12f, true, 0xff7a5600.toInt()).apply {
                setPadding(dp(4), 0, 0, 0)
                visibility = if (manual) View.VISIBLE else View.GONE
            }
            weekendBox.addView(manualNote, gap(2))
            val weekendFields = periods.mapIndexed { i, period ->
                val row = compactRow()
                row.addView(StudioUi.text(host, "$period · ${route.vehicleUnit}", 13.5f, true), LinearLayout.LayoutParams(0, -2, 1f))
                val field = input("Kereta akhir pekan, permintaan ${period.lowercase()}", weekendStart[i].toString(), true).apply {
                    background = null; typeface = StudioUi.heavy(host); setPadding(0, 0, 0, 0)
                }
                fun step(d: Int) = stepper("${if (d < 0) "Kurangi" else "Tambah"} ${route.vehicleUnit} akhir pekan, permintaan ${period.lowercase()}", d, solid = false) {
                    field.setText(((field.text.toString().toIntOrNull() ?: 0) + d).coerceIn(0, 128).toString())
                }
                row.addView(step(-1), LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(field, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(step(1), LinearLayout.LayoutParams(dp(48), dp(48)))
                weekendBox.addView(row, gap(4))
                field
            }
            fun weekendRecap() {
                val counts = weekendFields.joinToString("/") { it.text.toString().ifBlank { "–" } }
                weekendSummary.text = "Porsi ${weekend.text.toString().ifBlank { "–" }}% · ${periods.joinToString("/")}: $counts ${route.vehicleUnit}"
                openWeekend(weekendBox.visibility == View.VISIBLE)
            }
            weekendFields.forEach { f -> watch(f) {
                manualNote.visibility = if (manual || weekendFields.indices.any { weekendFields[it].text.toString() != weekendStart[it].toString() }) View.VISIBLE else View.GONE
                weekendRecap()
            } }
            watch(weekend) { weekendRecap() }
            weekendRecap()

            defaults?.invoke(dialog)?.let { body.addView(it, gap(8)) }

            val advanced = group().apply { visibility = View.GONE }
            lateinit var fold: Button
            fun expand(open: Boolean) {
                advanced.visibility = if (open) View.VISIBLE else View.GONE
                StudioUi.select(fold, open)
                fold.contentDescription = if (open) "Sembunyikan pengaturan lanjutan" else "Pengaturan lanjutan"
            }
            // the advanced fold and the timetable as two small chips on one row
            val tools = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            fold = StudioUi.choiceChip(host, "Pengaturan lanjutan", false) { expand(advanced.visibility != View.VISIBLE) }.apply {
                single(); setPadding(dp(10), 0, dp(10), 0)
            }
            tools.addView(fold, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (route.names.isNotEmpty()) tools.addView(StudioUi.choiceChip(host, "Grafik & keberangkatan", false) { showTimetable() }.apply {
                single(); setPadding(dp(10), 0, dp(10), 0)
                contentDescription = "Grafik & keberangkatan saat ini"
                setCompoundDrawablesRelative(StudioUi.glyph(host, StudioUi.Icon.ROUTE, StudioUi.INK), null, null, null)
                compoundDrawablePadding = dp(4)
            }, LinearLayout.LayoutParams(0, dp(48), 1.2f).apply { marginStart = dp(6) })
            body.addView(tools, gap(4))
            body.addView(advanced)
            fun option(title: String, value: String, numeric: Boolean = true): EditText {
                advanced.addView(StudioUi.text(host,title,13f,true,StudioUi.MUTED).apply { setPadding(dp(4),0,0,0) },gap(10))
                return input(title,value,numeric).also { advanced.addView(it,gap(3)) }
            }
            val name = option("Nama line", service.getString("name"), false)
            watch(name) { routeTitle.text = name.text.toString().trim().ifEmpty { service.getString("name") } }
            val cars = if (route.vehicleUnit == "kereta")
                option("Gerbong per rangkaian (0 = otomatis)", service.getInt("cars").toString()) else null
            val dwell = option("Berhenti di ${route.stopType} (detik)", service.getInt("dwell").toString())
            val turn = option("Balik di terminal (detik)", service.getInt("turn").toString())
            val error = StudioUi.text(host, "", 14f, true, 0xffa12d29.toInt()).apply {
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            fun invalid(field: EditText, reason: String): Nothing? {
                if (field == weekend || field in weekendFields) openWeekend(true)
                else if (field !in fields) expand(true)
                field.requestFocus()
                field.error = reason
                error.text = reason
                error.visibility = View.VISIBLE
                return null
            }
            fun read(field: EditText, min: Int, max: Int): Int? {
                val value = field.text.toString().toIntOrNull()
                if (value == null || value !in min..max) return invalid(field, "Masukkan angka ${min}–${max}.")
                return value
            }
            fun save() {
                val title = name.text.toString().trim()
                if (title.isEmpty() || title.codePointCount(0,title.length) > 48) {
                    invalid(name,"Nama line harus 1–48 karakter.")
                    return
                }
                val values = JSONObject().put("name",title).put("mode",if (byInterval) "headway" else "count")
                listOf("peak","day","night").forEachIndexed { i, key ->
                    val n = if (byInterval) {
                        val typed = fields[i].text.toString()
                        val old = service.getJSONArray("headway").optInt(i,-1)
                        val s = if (old >= 0 && typed == minutes(old.toDouble())) old else seconds(typed)
                        if (s == null || s < 0 || s > HEADWAY_MAX_S || s in 1 until HEADWAY_MIN_S) {
                            invalid(fields[i], "Isi 0 untuk tidak ada layanan, atau selang 1–240 mnt.")
                            return
                        }
                        s
                    } else read(fields[i], 0, 128) ?: return
                    values.put(key,n)
                }
                values.put("weekend",read(weekend,10,200) ?: return)
                // counts typed: set by hand; untouched on a line set by hand and the share kept: kept
                val counts = weekendFields.map { read(it,0,128) ?: return }
                val countsChanged = counts.indices.any { counts[it] != weekendStart[it] }
                val shareChanged = weekend.text.toString().trim() != shareStart
                if (countsChanged || (manual && !shareChanged)) values.put("weekendCounts", org.json.JSONArray(counts))
                values.put("cars",if (cars == null) service.getInt("cars") else read(cars,0,12) ?: return)
                values.put("dwell",read(dwell,5,600) ?: return)
                values.put("turn",read(turn,15,1800) ?: return)
                applySchedule(values) { result ->
                    if (result.has("error")) {
                        error.text = result.getString("error"); WorldSound.knock()
                        error.visibility = View.VISIBLE
                    } else {
                        dialog.dismiss()
                        onApplied(result)
                    }
                }
            }
            footer = group()
            footer.addView(error)
            val actions = LinearLayout(host)
            actions.addView(StudioUi.button(host,"Batal") { dialog.dismiss() },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(StudioUi.button(host,"Terapkan",StudioUi.Tone.PRIMARY) { save() },
                LinearLayout.LayoutParams(0,-2,1.6f).apply { marginStart = dp(6) })
            footer.addView(actions)
            refresh()
        }
    }

    private fun refresh() {
        modeButtons.forEachIndexed { i, btn ->
            btn.isSelected = (i == 1) == byInterval
            // Segmented control: the chosen mode is a white pill on the tinted track.
            btn.background = if (btn.isSelected) StudioUi.pill(host, StudioUi.WHITE, 14) else null
            btn.tag = null
            btn.setTextColor(if (btn.isSelected) StudioUi.INK else StudioUi.MUTED)
        }
        val summaries = mutableListOf<String>()
        fields.forEachIndexed { i, field ->
            val n = if (byInterval) seconds(field.text.toString()) else field.text.toString().toIntOrNull()
            val valid = n != null && if (byInterval) n == 0 || n in HEADWAY_MIN_S..HEADWAY_MAX_S else n in 0..128
            val cycle = cycles[i]
            val seconds = when {
                !valid || n == null || n == 0 -> 0.0
                byInterval -> n.toDouble()
                cycle.isFinite() && cycle > 0 -> cycle / n
                else -> 0.0
            }
            val unit = if (byInterval) "mnt" else route.vehicleUnit
            val note = when {
                !valid -> "Periksa angka"
                n == 0 -> "tidak ada"
                seconds <= 0 -> "Durasi belum tersedia"
                seconds < 60 -> "≈ ${seconds.roundToInt()} detik"
                else -> "≈ ${String.format(Locale.forLanguageTag("id-ID"),"%.1f",seconds/60).removeSuffix(",0")} mnt"
            }
            field.contentDescription = "Permintaan ${periods[i].lowercase()}, $unit"
            // web: a headway shorter than the track allows is red, with why (AUTO_SLACK 1.005)
            val lim = limits[i]?.let { if (it.isNull("s")) Double.POSITIVE_INFINITY else it.getDouble("s") }
            val short = valid && lim != null && seconds > 0 && seconds * 1.005 < lim
            val warn = if (short) limitWait(i) else null
            hints[i].text = periodHours[i] + " · " + (if (byInterval) "mnt · $note" else note) + (warn?.let { "\n$it" } ?: "")
            hints[i].setTextColor(if (short) StudioUi.BAD else StudioUi.MUTED)
            hints[i].contentDescription = "Periode permintaan ${periodTip[i]}: ${periodHours[i]}. $note" + (warn?.let { ". $it" } ?: "")
            preview.intervals[i] = seconds
            summaries.add("${periods[i]} $note")
        }
        preview.contentDescription = summaries.joinToString(", ")
        preview.invalidate()
    }

    /** Time ticks share one scale, so a tighter interval is visible across all periods. */
    private class FrequencyChart(private val host: Activity) : View(host) {
        val intervals = DoubleArray(3)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = StudioUi.face(host, true) }
        private val colors = intArrayOf(0xffff7a2f.toInt(),0xff6c4df6.toInt(),0xff3fb06a.toInt())
        private val labels = listOf("Tinggi","Sedang","Rendah")
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val textSize = 12 * resources.displayMetrics.scaledDensity
            paint.textSize = textSize
            val left = labels.maxOf { paint.measureText(it) } + 10*d
            val right = width-4*d
            if (right <= left) return
            val h = (height-textSize-6*d)/3
            for (i in intervals.indices) {
                val y = (i+.5f)*h
                paint.color = StudioUi.INK
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(labels[i],0f,y-(paint.ascent()+paint.descent())/2,paint)
                paint.color = 0x2622204a
                paint.strokeWidth = d
                canvas.drawLine(left,y,right,y,paint)
                val seconds = intervals[i]
                if (seconds <= 0 || !seconds.isFinite()) continue
                val spacing = ((right-left)*seconds/3600).toFloat().coerceAtLeast(2*d)
                var x = left
                paint.color = colors[i]
                while (x < right) {
                    canvas.drawRoundRect(x,y-5*d,(x+2.5f*d).coerceAtMost(right),y+5*d,d,d,paint)
                    x += spacing
                }
            }
            paint.color = StudioUi.MUTED
            val baseline = height-paint.descent()
            for ((fraction,label) in listOf(0f to "0", .5f to "30", 1f to "60 mnt")) {
                paint.textAlign = when (fraction) { 0f -> Paint.Align.LEFT; 1f -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                canvas.drawText(label,left+(right-left)*fraction,baseline,paint)
            }
        }
    }
}
