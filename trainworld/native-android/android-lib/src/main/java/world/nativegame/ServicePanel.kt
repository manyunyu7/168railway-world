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
    @JvmStatic private external fun rename(handle: Long, id: Int, now: Double, name: String): String
    @JvmStatic private external fun nameIdeas(handle: Long, id: Int): String

    /** Line colour from the archive (0xRRGGBB); falls back to the Studio purple. */
    /** What the last edit from a line's panel changed, for its undo entry. */
    var editLabel = "Jadwal line"
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
                column.addView(StudioUi.text(host, "Bangun line pertama untuk mengatur jadwalnya.", 14f))
                column.addView(StudioUi.button(host, "Kembali ke peta") { dialog.dismiss() })
            }
            val items = List(all.length()) { all.getJSONObject(it) }
            // Colour and vehicle come from each service's inspection; read once per sheet.
            val looks = items.associate { it.getInt("id") to runCatching { JSONObject(inspect(handle, it.getInt("id"), clock)) }.getOrNull() }
            fun vehicle(item: JSONObject) = looks[item.getInt("id")]?.optString("vehicle").orEmpty().ifEmpty { "Lainnya" }
            val running = items.count { it.optBoolean("running") }
            val headline = StudioUi.text(host, "${items.size} line", 13f, true, StudioUi.MUTED).apply {
                setPadding(dp(2), 0, 0, dp(8)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
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
                // one short line: count, riders a day, profit a day
                headline.text = listOfNotNull(
                    "${items.size} line" + if (running < items.size) " ($running jalan)" else "",
                    riders?.let { "${StudioFormat.count(it)} penumpang" },
                    profit?.let { "laba ${StudioCurrency.current(host).compact(it)}" },
                ).joinToString(" · ") + if (riders != null) " / hari" else if (running > 0) " · angka muncul saat simulasi berjalan" else ""
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
                val top = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                top.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
                top.addView(list.modeToggle { render() }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
                column.addView(top)
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
            }
            // filters then the sort chips on one scrolling row (the sort hides in table mode: its heads sort)
            if (items.isNotEmpty()) {
                val row = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                if (modes.size > 2) row.addView(tabs, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
                row.addView(list.sorts { render() })
                column.addView(android.widget.HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(8, host))
                if (items.size <= 6) column.addView(list.modeToggle { render() }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(8); gravity = android.view.Gravity.END })
            }
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

    /** "Ganti nama" for a line, with ↻ cycling name ideas (the station rename's generator, asked for lines too). */
    private fun askRename(host: Activity, handle: Long, id: Int, current: String, stops: JSONArray, transaction: PanelEdit, done: (String) -> Unit) {
        fun dp(n: Int) = StudioUi.dp(host, n)
        // Pieces for a name built from the line's own stops: a prefix, the ends, a suffix.
        val names = (0 until stops.length()).mapNotNull { stops.optString(it).takeIf { n -> n.isNotBlank() } }
        fun short(n: String) = n.split(' ').take(2).joinToString(" ")
        val first = names.firstOrNull()?.let(::short).orEmpty()
        val last = names.lastOrNull()?.let(::short).orEmpty()
        val mid = names.getOrNull(names.size / 2)?.let(::short).orEmpty()
        val prefixes = listOf("", "Commuter Line", "KRL", "MRT", "LRT", "Lin", "Ekspres", "Bus", "Trem")
        val bodies = listOfNotNull(
            "$first – $last".takeIf { first.isNotEmpty() && first != last },
            last.takeIf { it.isNotEmpty() }, first.takeIf { it.isNotEmpty() },
            "Lingkar $first".takeIf { first.isNotEmpty() },
        ).distinct()
        val suffixes = listOf("", "Ekspres", "Lokal", "via $mid", "Raya", "Feeder", "Malam")
        var pre = 0; var body = 0; var suf = 0
        val ideas = runCatching { JSONArray(nameIdeas(handle, id)) }.getOrDefault(JSONArray())
        var next = 0
        val field = EditText(host).apply {
            setText(current); setSingleLine(); setSelectAllOnFocus(true)
            StudioUi.field(this)
            contentDescription = "Nama"
            filters = arrayOf(android.text.InputFilter.LengthFilter(48))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        // the field stays put above the suggestions (StudioDialogBuilder.setPinned), same as the station's
        val row = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        fun compose() {
            val text = listOf(prefixes[pre], bodies.getOrNull(body).orEmpty(), suffixes[suf]).filter { it.isNotBlank() }.joinToString(" ")
            if (text.isNotBlank()) { field.setText(text.take(48)); field.setSelection(field.text.length) }
        }
        fun chips(title: String, options: List<String>, selected: () -> Int, pick: (Int) -> Unit) {
            box.addView(StudioUi.text(host, title, 12f, true, StudioUi.MUTED).apply { setPadding(0, dp(8), 0, dp(2)) })
            val line = LinearLayout(host)
            val views = mutableListOf<android.widget.TextView>()
            fun paint() = views.forEachIndexed { i, v -> val on = i == selected(); v.background = StudioUi.pill(host, if (on) StudioUi.INK else 0x1422204a, 14); v.setTextColor(if (on) StudioUi.WHITE else StudioUi.INK) }
            options.forEachIndexed { i, o ->
                val v = StudioUi.text(host, o.ifEmpty { "Tanpa" }, 13f, true).apply {
                    gravity = android.view.Gravity.CENTER; minHeight = dp(36); setPadding(dp(12), 0, dp(12), 0); isClickable = true
                    setOnClickListener { pick(i); paint(); compose() }
                }
                views += v
                line.addView(v, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6); topMargin = dp(2); bottomMargin = dp(2) })
            }
            paint()
            box.addView(android.widget.HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(line) })
        }
        // At least five ready names to tap, not only the ↻ cycle (owner: "kok terbatas banget? minimal 5")
        val ready = buildList {
            for (i in 0 until ideas.length()) add(ideas.getString(i))
            for (p in listOf("Commuter Line", "KRL", "Lin")) for (b in bodies) add("$p $b")
            for (b in bodies) for (x in listOf("Ekspres", "Lokal")) add("$b $x")
        }.map { it.trim().take(48) }.filter { it.isNotBlank() && it != current }.distinct().take(6)
        if (ready.isNotEmpty()) {
            box.addView(StudioUi.text(host, "Usulan nama", 12f, true, StudioUi.MUTED).apply { setPadding(0, dp(6), 0, dp(2)) })
            // one neat column: every idea one line high, cut with "…" when long
            val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            ready.forEach { n ->
                list.addView(StudioUi.text(host, n, 14f, true, StudioUi.INK).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, dp(12), 0); background = StudioUi.pill(host, 0x1422204a, 12); isClickable = true
                    setOnClickListener { field.setText(n); field.setSelection(n.length) }
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
            }
            box.addView(list)
        }
        if (bodies.isNotEmpty()) {
            chips("Awalan", prefixes, { pre }) { pre = it }
            chips("Rute", bodies, { body }) { body = it }
            chips("Akhiran", suffixes, { suf }) { suf = it }
        }
        row.addView(StudioUi.iconButton(host, "Nama usulan berikutnya", StudioUi.Icon.UNDO) {
            if (ideas.length() == 0) { android.widget.Toast.makeText(host, "Tidak ada usulan nama lain.", android.widget.Toast.LENGTH_SHORT).show(); return@iconButton }
            field.setText(ideas.getString(next % ideas.length())); field.selectAll(); next++
        }.apply { rotationY = 180f }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        lateinit var dlg: StudioPrompt
        fun save() {
            val name = field.text.toString().trim()
            if (name.isEmpty() || name == current) { dlg.dismiss(); return }
            dlg.dismiss()
            transaction { now ->
                editLabel = "Nama line"
                val r = JSONObject(rename(handle, id, now, name))
                if (r.has("error")) WorldSound.refused(host, r.getString("error"))
                else done(name)
            }
        }
        dlg = StudioDialogBuilder(host).setTitle("Ganti nama line").setPinned(row).setView(box)
            .setPositiveButton("Selesai") { _, _ -> save() }.setNegativeButton("Batal", null).create()
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        field.requestFocus()
    }

    /** The line panel's tab last shown (Ringkasan, Perhentian, Jadwal), kept for the session. */
    private var lastTab = 0

    fun details(host: Activity, handle: Long, id: Int, clock: Double, editable: Boolean, transaction: PanelEdit, changed: (String) -> Unit) {
        val service = JSONObject(inspect(handle,id,clock))
        val route = ServiceStops(service)
        val upcoming = service.optJSONArray("upcoming")
        val times = service.optJSONArray("depart") ?: JSONArray()
        val shift = service.optDouble("shift", 0.0)
        val color = color(service)
        lateinit var footer: LinearLayout
        StudioUi.sheet(host, service.getString("name"), .75f, pinnedAction = { footer }) { column, dialog ->
            fun dp(n: Int) = StudioUi.dp(host, n)
            fun look() = LineLook.edit(host, handle, id, service, transaction) {
                dialog.dismiss(); changed(service.getString("name")); details(host, handle, id, clock, editable, transaction, changed)
            }
            val head = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            LineLook.remember(service)
            // web LineTitle: the badge opens the look editor
            head.addView(LineLook.badge(host, id, service.getString("name"), color, 36).apply {
                if (editable) { isClickable = true; contentDescription = "Ubah tampilan line"; setOnClickListener { look() } }
            }, LineLook.params(host, 36))
            val copy = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(StudioUi.text(host, if (route.names.isEmpty()) "Belum ada pemberhentian" else route.endpoints, 14f, color = StudioUi.INK).apply {
                typeface = StudioUi.heavy(host); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(StudioUi.text(host, service.getString("vehicle") + " · " +
                if (service.optBoolean("running")) "Beroperasi" else "Belum beroperasi", 12f, true, StudioUi.MUTED))
            // the fleet, length and round trip in one short line (owner: the three stat cards took the panel)
            val fleet = service.optJSONArray("count")
            val cycle = service.optDouble("cycle", 0.0)
            val figures = listOfNotNull(
                fleet?.optInt(0)?.let { "$it armada" },
                String.format(Locale.forLanguageTag("id-ID"), "%.1f km", service.getDouble("length") / 1000),
                if (cycle > 0) "±${Math.round(cycle / 60)} mnt putaran" else null,
            )
            copy.addView(StudioUi.text(host, figures.joinToString(" · "), 12f, true, StudioUi.INK).apply {
                contentDescription = listOfNotNull(fleet?.optInt(0)?.let { "Armada sibuk $it" }, figures.getOrNull(1)?.let { "panjang $it" },
                    if (cycle > 0) "putaran sekitar ${Math.round(cycle / 60)} menit" else null).joinToString(", ")
            })
            head.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            column.addView(head)
            // the line's actions in one row of small buttons, scrolling sideways; Hapus last and only its text red
            if (editable) {
                val actions = LinearLayout(host).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                fun action(label: String, icon: StudioUi.Icon, ink: Int = StudioUi.INK, description: String? = null, click: () -> Unit) {
                    actions.addView(StudioUi.text(host, label, 13f, true, ink).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        background = StudioUi.panel(host, StudioUi.WHITE, 12)
                        minHeight = dp(40)
                        setPadding(dp(10), 0, dp(12), 0)
                        compoundDrawablePadding = dp(4)
                        setCompoundDrawablesRelative(StudioUi.glyph(host, icon, ink), null, null, null)
                        isClickable = true
                        isFocusable = true
                        contentDescription = description ?: label
                        setOnClickListener { click() }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
                // web LineInspector "Ubah perhentian" (toggleEditStops): the stops edited on the map (StopEditMode.kt)
                if (StopEditMode.available && route.vehicleUnit == "kereta") action("Ubah perhentian", StudioUi.Icon.PIN,
                    description = "Ubah perhentian: ketuk stasiun di peta untuk menambah perhentian ke line") {
                    dialog.dismiss()
                    StopEditMode.start(host, id, service.getString("name"), color, transaction, changed) {
                        details(host, StopEditMode.currentHandle(), id, clock, editable, transaction, changed)
                    }
                }
                action("Ganti nama", StudioUi.Icon.EDIT) {
                    askRename(host, handle, id, service.getString("name"), service.optJSONArray("stops") ?: JSONArray(), transaction) {
                        dialog.dismiss(); changed(it); details(host, handle, id, clock, editable, transaction, changed)
                    }
                }
                action("Tampilan", StudioUi.Icon.EYE, description = "Tampilan: simbol, bentuk, dan warna line") { look() }
                action("Hapus line", StudioUi.Icon.DELETE, StudioUi.BAD) {
                    dialog.dismiss(); (host as? NativeWorldActivity)?.removeLine(id, service.getString("name"))
                }
                column.addView(android.widget.HorizontalScrollView(host).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(actions)
                }, lp(8, host))
            }
            // Owner's tabs under the head: Ringkasan (riders graph, fare, money, speed profile), Perhentian
            // (the stop rows with the passed stations between them), Jadwal (the next departures).
            val tabNames = listOf("Ringkasan", "Perhentian", "Jadwal")
            val pages = tabNames.map { LinearLayout(host).apply { orientation = LinearLayout.VERTICAL } }
            val (summaryTab, stopsTab, scheduleTab) = pages
            val track = LinearLayout(host).apply {
                background = StudioUi.pill(host, 0x1422204a, 18)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val tabs = tabNames.mapIndexed { i, name ->
                StudioUi.text(host, name, 13.5f, true, StudioUi.MUTED).apply {
                    gravity = android.view.Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    track.addView(this, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
                }
            }
            fun show(k: Int) {
                lastTab = k
                tabs.forEachIndexed { i, t ->
                    val on = i == k
                    t.background = if (on) StudioUi.pill(host, StudioUi.WHITE, 14) else null
                    t.setTextColor(if (on) StudioUi.INK else StudioUi.MUTED)
                    t.isSelected = on
                    t.contentDescription = "Tab ${tabNames[i]}, ${i + 1} dari ${tabNames.size}" + if (on) ", terpilih" else ""
                }
                pages.forEachIndexed { i, p -> p.visibility = if (i == k) View.VISIBLE else View.GONE }
            }
            tabs.forEachIndexed { i, t -> t.setOnClickListener { show(i) } }
            column.addView(track, lp(8, host))
            pages.forEach { column.addView(it, lp(4, host)) }

            if (route.names.isNotEmpty()) {
                // the riders on each stretch, both ways (LineRidersChart.kt), from the same answer as the stop rows
                val chart = if (LineStops.riders != null && route.names.size >= 2) LineRidersChart.create(host, route, color) else null
                chart?.let { summaryTab.addView(it.view, lp(8, host)) }
                // Vertical strip in the line colour with each stop's riders; stops passed and skipped (LineStops.kt).
                val strip = LineStops.strip(host, handle, id, route, color, editable, chart?.fill) { success, edit ->
                    dialog.dismiss()
                    transaction { now ->
                        if (LineStops.toast(host, edit.run(now), success)) {
                            editLabel = LineStops.LABEL
                            changed(service.getString("name"))
                        }
                        details(host, handle, id, clock, editable, transaction, changed)
                    }
                }
                stopsTab.addView(strip, lp(8, host))
                scheduleTab.addView(StudioUi.section(host, "Keberangkatan dari ${route.names.first()}"))
                val entries = if (upcoming != null) {
                    (0 until minOf(6, upcoming.length())).map { i ->
                        val row = upcoming.getJSONArray(i)
                        StudioTimetable.time(row.getDouble(1) + shift) to route.destination(row)
                    }
                } else {
                    (0 until minOf(6, times.length())).map { StudioTimetable.time(times.getDouble(it) + shift) to "" }
                }
                if (entries.isEmpty()) {
                    scheduleTab.addView(StudioUi.text(host, if (service.optBoolean("running")) "Belum ada jadwal dari pemberhentian ini dalam satu jam ke depan."
                        else "Line belum beroperasi.", 13f, true, StudioUi.MUTED).apply { setPadding(dp(4), 0, 0, 0) })
                } else entries.forEach { (time, destination) -> scheduleTab.addView(StudioTimetable.departure(host,time,destination), lp(6, host)) }
            } else {
                val none = "Belum ada pemberhentian pada rute ini."
                listOf(stopsTab, scheduleTab).forEach { it.addView(StudioUi.text(host, none, 13f, true, StudioUi.MUTED), lp(8, host)) }
            }
            // web PastDay's crowding rows: average load, full trains, riders left behind (Crowding.kt)
            Crowding.pastDay(host, id)?.let { summaryTab.addView(it, lp(12, host)) }
            // web LineFare: the line's own fare multiple, above its money
            summaryTab.addView(LineFare.row(host, handle, id, editable, transaction) {
                editLabel = "Kelipatan tarif"
                changed(service.getString("name"))
            }, lp(12, host))
            // 168Railway's own train adverts (WorldExtras.kt; not in the web)
            summaryTab.addView(WorldExtras.lineSection(host, handle, id, clock, editable, transaction) {
                editLabel = "Iklan kereta"
                changed(service.getString("name"))
            }, lp(12, host))
            // web LineMoney: the line's last 30 whole days of fares and running cost
            FinanceHistory.lineMoney(host, service.optJSONArray("lineDays"), service.optInt("today"), StudioCurrency.current(host))?.let {
                summaryTab.addView(it, lp(12, host))
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
            } else null)?.let { summaryTab.addView(it, lp(12, host)) }
            show(lastTab.coerceIn(0, tabNames.size - 1))
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
            applySchedule = { values, reply -> transaction { now -> editLabel = "Jadwal line"; reply(JSONObject(update(handle, id, now, values.toString()))) } },
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
