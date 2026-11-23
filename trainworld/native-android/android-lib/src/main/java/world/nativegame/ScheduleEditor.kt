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
    private val applySchedule: (JSONObject) -> JSONObject,
    private val onApplied: (JSONObject) -> Unit
) {
    private val route = ServiceStops(service)
    private val periods = listOf("Sibuk", "Sedang", "Sepi")
    private val countDraft = Array(3) { service.getJSONArray("count").optInt(it, 0).toString() }
    private val headwayDraft = Array(3) { service.getJSONArray("headway").optInt(it, listOf(180, 360, 720)[it]).toString() }
    private var byInterval = service.getJSONArray("headway").length() == 3
    private val fields = mutableListOf<EditText>()
    private val hints = mutableListOf<TextView>()
    private val modeButtons = mutableListOf<Button>()
    private val preview = FrequencyChart(host)
    private val cycle = service.optDouble("cycle", 0.0)
    private val colour = service.optInt("color", 0x1cb0f6) or Color.BLACK
    private fun dp(v: Int) = StudioUi.dp(host, v)
    private fun gap(v: Int) = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(v) }
    private fun group() = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private fun section(label: String) = StudioUi.section(host,label)
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

    fun show() {
        lateinit var footer: LinearLayout
        StudioUi.sheet(host, "Atur jadwal", .94f, pinnedAction = { footer }) { body, dialog ->
            val titleRow = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(StudioUi.badge(host, ServicePanel.code(service.getString("name")), colour, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
            val heading = group()
            val routeTitle = StudioUi.text(host, service.getString("name"), 17f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(host) }
            heading.addView(routeTitle)
            val summary = listOf(service.getString("vehicle"), route.endpoints).filter { it.isNotBlank() }.joinToString("  ·  ")
            heading.addView(StudioUi.text(host, summary, 13f, true, StudioUi.MUTED))
            titleRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            body.addView(titleRow, gap(4))
            val card = group().apply {
                background = StudioUi.card(host, 22)
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            card.addView(StudioUi.text(host, "Pratinjau frekuensi · satu jam", 13f, true, StudioUi.MUTED))
            card.addView(preview, LinearLayout.LayoutParams(-1, dp(92)))
            body.addView(card, gap(12))

            body.addView(section("Jadwal hari kerja"), gap(16))
            val selector = LinearLayout(host).apply {
                background = StudioUi.pill(host, 0x1422204a, 18)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            listOf("Jumlah ${route.vehicleUnit}", "Selang waktu").forEachIndexed { index, label ->
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
                btn.setPadding(dp(8), 0, dp(8), 0)
                selector.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index == 1) marginStart = dp(4) })
                modeButtons.add(btn)
            }
            body.addView(selector, gap(6))
            periods.forEachIndexed { i, period ->
                val row = LinearLayout(host).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(8), dp(8))
                    background = StudioUi.card(host, 22)
                }
                val text = group()
                text.addView(StudioUi.text(host, period, 16f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(host) })
                val hint = StudioUi.text(host, "", 12f, true, StudioUi.MUTED)
                text.addView(hint)
                hints.add(hint)
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                val initial = (if (byInterval) headwayDraft else countDraft)[i]
                val edit = input(period, initial, true).apply {
                    background = null
                    typeface = StudioUi.heavy(host)
                    textSize = 17f
                }
                fields.add(edit)
                fun move(direction: Int) {
                    val current = edit.text.toString().toIntOrNull() ?: 0
                    val step = if (byInterval) 60 else 1
                    val upper = if (byInterval) 7200 else 128
                    var next = (current.toLong() + direction * step).coerceIn(0L, upper.toLong()).toInt()
                    if (byInterval && next in 1..59) next = if (direction > 0) 60 else 0
                    edit.setText(next.toString())
                }
                fun adjust(direction: Int) = StudioUi.iconButton(host,
                    "${if (direction < 0) "Kurangi" else "Tambah"} periode ${period.lowercase()}",
                    if (direction < 0) StudioUi.Icon.MINUS else StudioUi.Icon.PLUS) { move(direction) }.apply {
                    // Round stepper: a soft minus and a solid plus.
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (direction < 0) 0x1422204a else StudioUi.INK)
                    }
                    setTextColor(if (direction < 0) StudioUi.INK else StudioUi.WHITE)
                    setPadding(0, 0, 0, 0)
                    textSize = 20f
                }
                row.addView(adjust(-1), LinearLayout.LayoutParams(dp(44), dp(44)))
                row.addView(edit, LinearLayout.LayoutParams(dp(60), -2).apply { setMargins(dp(2),0,dp(2),0) })
                row.addView(adjust(1), LinearLayout.LayoutParams(dp(44), dp(44)))
                body.addView(row, gap(8))
                watch(edit) {
                    (if (byInterval) headwayDraft else countDraft)[i] = edit.text.toString()
                    refresh()
                }
            }

            val advanced = group().apply { visibility = View.GONE }
            lateinit var fold: Button
            fun expand(open: Boolean) {
                advanced.visibility = if (open) View.VISIBLE else View.GONE
                fold.text = if (open) "Sembunyikan pengaturan lanjutan" else "Pengaturan lanjutan"
            }
            fold = StudioUi.button(host, "Pengaturan lanjutan") { expand(advanced.visibility != View.VISIBLE) }
            body.addView(fold, gap(12))
            body.addView(advanced)
            fun option(title: String, value: String, numeric: Boolean = true): EditText {
                advanced.addView(StudioUi.text(host,title,13f,true,StudioUi.MUTED).apply { setPadding(dp(4),0,0,0) },gap(10))
                return input(title,value,numeric).also { advanced.addView(it,gap(3)) }
            }
            val name = option("Nama layanan", service.getString("name"), false)
            watch(name) { routeTitle.text = name.text.toString().trim().ifEmpty { service.getString("name") } }
            val weekend = option("Armada akhir pekan (% hari kerja)", service.getInt("weekend").toString())
            val cars = if (route.vehicleUnit == "kereta")
                option("Gerbong per rangkaian (0 = otomatis)", service.getInt("cars").toString()) else null
            val dwell = option("Berhenti di ${route.stopType} (detik)", service.getInt("dwell").toString())
            val turn = option("Balik di terminal (detik)", service.getInt("turn").toString())
            if (route.names.isNotEmpty()) body.addView(StudioUi.button(host, "Grafik & keberangkatan saat ini", icon = StudioUi.Icon.ROUTE) {
                showTimetable()
            },gap(5))
            val error = StudioUi.text(host, "", 14f, true, 0xffa12d29.toInt()).apply {
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            fun invalid(field: EditText, reason: String): Nothing? {
                if (field !in fields) expand(true)
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
                    invalid(name,"Nama layanan harus 1–48 karakter.")
                    return
                }
                val values = JSONObject().put("name",title).put("mode",if (byInterval) "headway" else "count")
                listOf("peak","day","night").forEachIndexed { i, key ->
                    val n = read(fields[i], 0, if (byInterval) 7200 else 128) ?: return
                    if (byInterval && n in 1..59) {
                        invalid(fields[i], "Pilih 0 untuk berhenti beroperasi, atau minimal 60 detik.")
                        return
                    }
                    values.put(key,n)
                }
                values.put("weekend",read(weekend,10,200) ?: return)
                values.put("cars",if (cars == null) service.getInt("cars") else read(cars,0,12) ?: return)
                values.put("dwell",read(dwell,5,600) ?: return)
                values.put("turn",read(turn,15,1800) ?: return)
                val result = applySchedule(values)
                if (result.has("error")) {
                    error.text = result.getString("error")
                    error.visibility = View.VISIBLE
                } else {
                    dialog.dismiss()
                    onApplied(result)
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
            val n = field.text.toString().toIntOrNull()
            val valid = n != null && if (byInterval) n == 0 || n in 60..7200 else n in 0..128
            val seconds = when {
                !valid || n == null || n == 0 -> 0.0
                byInterval -> n.toDouble()
                cycle.isFinite() && cycle > 0 -> cycle / n
                else -> 0.0
            }
            val unit = if (byInterval) "detik" else route.vehicleUnit
            val note = when {
                !valid -> "Periksa angka"
                n == 0 -> "Nonaktif"
                seconds <= 0 -> "Durasi belum tersedia"
                seconds < 60 -> "≈ ${seconds.roundToInt()} detik"
                else -> "≈ ${String.format(Locale.forLanguageTag("id-ID"),"%.1f",seconds/60).removeSuffix(",0")} mnt"
            }
            field.contentDescription = "${periods[i]}, $unit"
            hints[i].text = if (byInterval) "detik · $note" else note
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
        private val labels = listOf("Sibuk","Sedang","Sepi")
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
