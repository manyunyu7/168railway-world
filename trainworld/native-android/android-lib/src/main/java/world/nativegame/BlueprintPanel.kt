// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web Build tab's blueprint (ui/Blueprint.tsx): the total, the cost breakdown by part joined by
 * track with each part's and stretch's switch, the quote by kind, and Construct. A switched-off
 * piece is left out of the total and of Construct, as on the web; the edits of the web's selection
 * bar (level, track type, track count, remove) act on the pieces switched on.
 */
internal object BlueprintPanel {
    @JvmStatic private external fun overview(handle: Long): String
    @JvmStatic external fun edit(handle: Long, clock: Double, op: Int, value: Int, edges: IntArray, stations: IntArray, commit: Boolean): String

    const val SWITCH = 0
    const val LEVEL = 1
    const val KIND = 2
    const val TRACKS = 3
    const val REMOVE = 4
    const val CONSTRUCT = 5

    /**
     * [commit] runs one committed edit on the live world, keeps the undo step, redraws the map and
     * saves; it answers the edit's JSON, or null once it has said why not.
     */
    fun show(activity: Activity, handle: () -> Long, clock: () -> Double,
             commit: (String, () -> JSONObject) -> JSONObject?, focus: (Float, Float) -> Unit) {
        Session(activity, handle, clock, commit, focus).open()
    }

    private val LEVELS = listOf(3, 2, 1, 0, -1, -2, -3)
    fun levelLabel(l: Int) = if (l > 0) "+$l" else if (l < 0) "−${-l}" else "0"
    fun levelName(l: Int) = when (l) {
        3 -> "Viaduk tinggi"; 1, 2 -> "Viaduk"; 0 -> "Permukaan tanah"
        -1 -> "Galian terbuka"; -2 -> "Terowongan"; else -> "Terowongan dalam"
    }
    fun distance(m: Double): String =
        if (m < 1000) "${Math.round(m)} m"
        else java.lang.String.format(java.util.Locale.forLanguageTag("id-ID"), "%.1f km", m / 1000)

    private fun ints(a: JSONArray?) = IntArray(a?.length() ?: 0) { a!!.getInt(it) }

    private class Session(val a: Activity, val handle: () -> Long, val clock: () -> Double,
                          val commit: (String, () -> JSONObject) -> JSONObject?, val focus: (Float, Float) -> Unit) {
        val currency = StudioCurrency.current(a)
        lateinit var column: LinearLayout
        lateinit var dialog: Dialog
        var construct: Button? = null
        val unfolded = mutableSetOf<String>()
        var byKind = false
        var last = JSONObject()

        fun money(m: Double) = currency.compact(m)
        fun gap(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(a, top) }

        fun open() {
            dialog = StudioUi.sheet(a, "Rencana", .62f, pinnedAction = {
                StudioUi.button(a, "Bangun rencana", StudioUi.Tone.PRIMARY, StudioUi.Icon.BUILD) { build() }.also { construct = it }
            }) { col, _ -> column = col }
            render()
        }

        fun run(label: String, op: Int, value: Int = 0, edges: IntArray = IntArray(0), stations: IntArray = IntArray(0), done: (JSONObject) -> Unit = {}) {
            // redrawn either way, so a refused switch's box shows the piece's state again
            commit(label) { JSONObject(edit(handle(), clock(), op, value, edges, stations, true)) }?.let(done)
            render()
        }

        fun render() {
            column.removeAllViews()
            val o = runCatching { JSONObject(overview(handle())) }.getOrElse { JSONObject().put("error", it.localizedMessage) }
            last = o
            if (o.has("error")) {
                column.addView(StudioUi.text(a, o.optString("error"), 14f, true, StudioUi.MUTED))
                construct?.isEnabled = false
                return
            }
            val parts = o.getJSONArray("parts")
            val quote = o.getJSONArray("quote")
            val total = o.getDouble("total")
            construct?.apply {
                isEnabled = total > 0
                text = if (total > 0) "Bangun rencana · ${money(total)}" else "Bangun rencana"
                contentDescription = "Bangun semua bagian rencana"
            }
            if (parts.length() == 0 && quote.length() == 0) {
                column.addView(StudioUi.text(a, "Belum ada rencana. Gambar rel dengan alat Rel atau simpan stasiun sebagai rencana; semuanya muncul di sini sebelum dibangun.", 14f, true, StudioUi.MUTED))
                return
            }
            summary(o, total)
            picked(o.getJSONObject("picked"))
            switches(parts)
            column.addView(StudioUi.section(a, "Rincian biaya"))
            for (i in 0 until parts.length()) part(parts.getJSONObject(i))
            kinds(quote, onlyOther = true)
            if (quote.length() > 0) {
                column.addView(StudioUi.button(a, if (byKind) "Sembunyikan per jenis" else "Per jenis") {
                    byKind = !byKind; render()
                }.apply { contentDescription = "Rincian biaya per jenis" }, gap(10))
                if (byKind) kinds(quote, onlyOther = false)
            }
        }

        /** Total (red past the cash), and what is switched off with a way to switch it all on (web OffCost). */
        fun summary(o: JSONObject, total: Double) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val short = !o.optBoolean("sandbox") && total > o.optDouble("cash", Double.MAX_VALUE)
            row.addView(StudioUi.stat(a, "Total biaya", money(total), if (short) StudioUi.BAD else StudioUi.INK).apply {
                contentDescription = "Total biaya pembangunan ${money(total)}" + if (short) ", melebihi kas" else ""
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val offN = o.optInt("offEdges") + o.optInt("offStations")
            if (offN > 0) {
                val pieces = listOf(o.optInt("offEdges").takeIf { it > 0 }?.let { "$it rel" }, o.optInt("offStations").takeIf { it > 0 }?.let { "$it stasiun" }).filterNotNull().joinToString(" dan ")
                val off = o.optDouble("offCost")
                row.addView(StudioUi.stat(a, "Nonaktif", if (off > 0) money(off) else "$offN bagian", StudioUi.MUTED).apply {
                    contentDescription = "Rencana nonaktif: $pieces, tidak masuk total"
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = StudioUi.dp(a, 8) })
            }
            column.addView(row)
            if (offN > 0) column.addView(StudioUi.text(a, "Bagian nonaktif tidak ikut dibangun dan tidak masuk total.", 13f, true, StudioUi.MUTED).apply {
                setPadding(StudioUi.dp(a, 4), StudioUi.dp(a, 6), 0, 0)
            })
        }

        /** Web BlueprintBar: level, track type, track count and remove for the pieces switched on. */
        fun picked(p: JSONObject) {
            val edges = p.optInt("edges"); val stations = p.optInt("stations")
            if (edges + stations == 0) return
            val what = listOfNotNull(edges.takeIf { it > 0 }?.let { "$it rel" }, stations.takeIf { it > 0 }?.let { "$it stasiun" }).joinToString(", ")
            column.addView(StudioUi.section(a, "Ubah yang dipilih"))
            column.addView(StudioUi.text(a, "$what dipilih" + if (edges > 0) " · ${distance(p.optDouble("length"))}" else "", 14f, true))
            column.addView(StudioUi.text(a, "Yang dicentang di rincian biaya ikut diubah, dihapus, dan dibangun.", 12f, false, StudioUi.MUTED))
            if (edges > 0) {
                val level = if (p.isNull("level")) null else p.optInt("level")
                column.addView(StudioUi.text(a, if (level == null) "Tingkat · campuran" else "Tingkat · ${levelName(level)}", 13f, true, StudioUi.MUTED), gap(6))
                // the seven levels on one sideways row of chips
                val levels = LinearLayout(a)
                for (l in LEVELS) levels.addView(StudioUi.choiceChip(a, levelLabel(l), l == level) {
                    if (l != level) run("Ubah tingkat rencana", LEVEL, l) { say(it) }
                }.apply { contentDescription = "Pindahkan rel yang dipilih ke ${levelName(l).lowercase()} (${levelLabel(l)})" },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = StudioUi.dp(a, 6) })
                column.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(levels) }, gap(0))
            }
            val hsr = p.optInt("hsr"); val regular = p.optInt("regular"); val tram = p.optInt("tram")
            if (hsr + regular + tram > 0) {
                column.addView(StudioUi.text(a, "Jenis rel", 13f, true, StudioUi.MUTED), gap(2))
                val kinds = WrapRow(a)
                listOf(Triple(1, "Biasa", tram == 0 && hsr == 0), Triple(2, "Cepat", tram == 0 && regular == 0), Triple(3, "Trem", tram > 0 && hsr + regular == 0)).forEach { (k, label, on) ->
                    kinds.addView(StudioUi.choiceChip(a, label, on) {
                        if (!on) run("Ubah jenis rel rencana", KIND, k) { say(it) }
                    }.apply { contentDescription = if (k == 2) "Rel kecepatan tinggi, biayanya 1,5x rel biasa" else "Jadikan rel $label".lowercase().replaceFirstChar { it.uppercase() } })
                }
                column.addView(kinds, gap(0))
            }
            val ones = p.optInt("ones"); val twos = p.optInt("twos")
            if (ones + twos > 0) {
                val own = if (ones > 0 && twos == 0) 1 else if (twos > 0 && ones == 0) 2 else 0
                column.addView(StudioUi.text(a, "Jalur", 13f, true, StudioUi.MUTED), gap(2))
                val tracks = WrapRow(a)
                listOf(1 to "Tunggal", 2 to "Ganda").forEach { (n, label) ->
                    tracks.addView(StudioUi.choiceChip(a, label, n == own) {
                        if (n != own) run("Ubah jumlah jalur rencana", TRACKS, n) { say(it) }
                    }.apply { contentDescription = "Jadikan rel $label".lowercase().replaceFirstChar { it.uppercase() } })
                }
                column.addView(tracks, gap(0))
            }
            column.addView(StudioUi.button(a, "Hapus yang dipilih") { remove(what) }.also { StudioUi.danger(it) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = StudioUi.dp(a, 6) })
        }

        /** Web enableAll and its reverse: every part on, or every part off. */
        fun switches(parts: JSONArray) {
            val edges = mutableListOf<Int>(); val stations = mutableListOf<Int>()
            var anyOn = false; var anyOff = false
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                edges += ints(p.optJSONArray("edges")).toList(); stations += ints(p.optJSONArray("stations")).toList()
                anyOn = anyOn || p.optBoolean("on"); anyOff = anyOff || p.optBoolean("off")
            }
            if (edges.isEmpty() && stations.isEmpty()) return
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(StudioUi.button(a, "Pilih semua") {
                run("Nyalakan semua rencana", SWITCH, 0, edges.toIntArray(), stations.toIntArray())
            }.apply { isEnabled = anyOff; contentDescription = "Nyalakan semua bagian rencana" }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(StudioUi.button(a, "Kosongkan") {
                run("Matikan semua rencana", SWITCH, 1, edges.toIntArray(), stations.toIntArray())
            }.apply { isEnabled = anyOn; contentDescription = "Matikan semua bagian rencana" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = StudioUi.dp(a, 8) })
            column.addView(row, gap(8))
        }

        fun check(on: Boolean, description: String, flip: () -> Unit) = CheckBox(a).apply {
            isChecked = on
            contentDescription = description
            minWidth = StudioUi.dp(a, 48); minHeight = StudioUi.dp(a, 48)
            buttonTintList = android.content.res.ColorStateList.valueOf(StudioUi.BLUE)
            setOnClickListener { flip() }
        }

        /** One part, folded to a line until its chevron opens its stretches and stations (web T-2339). */
        fun part(p: JSONObject) {
            val rows = p.getJSONArray("rows")
            val junctions = p.optInt("junctions")
            val key = ints(p.optJSONArray("edges")).minOrNull()?.let { "e$it" } ?: "s${ints(p.optJSONArray("stations")).firstOrNull()}"
            val many = rows.length() > 1 || junctions > 0
            val open = many && key in unfolded
            val on = p.optBoolean("on")
            val facts = listOfNotNull(p.optInt("stationCount").takeIf { it > 0 }?.let { "$it stasiun" }, p.optDouble("length").takeIf { it > 0 }?.let { distance(it) }).joinToString(" · ")
            val line = line(p.optString("label"), facts.ifEmpty { null }, money(if (on) p.optDouble("onCost") else p.optDouble("cost")), !on,
                check(on, if (on) "Matikan semua rel yang tersambung" else "Nyalakan semua rel yang tersambung") {
                    flip(p)
                }, p)
            if (many) line.addView(StudioUi.iconButton(a, if (open) "Lipat" else "Buka", if (open) StudioUi.Icon.MINUS else StudioUi.Icon.PLUS) {
                if (!unfolded.remove(key)) unfolded.add(key); render()
            }, 1)
            column.addView(line, gap(6))
            if (!open) return
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val rOn = r.optBoolean("on")
                val station = r.optString("kind") == "station"
                val lo = r.optInt("levelMin"); val hi = r.optInt("levelMax")
                val levels = if (lo == hi) "${levelName(lo)} (${levelLabel(lo)})" else "Tingkat ${levelLabel(lo)} sampai ${levelLabel(hi)}"
                val sub = if (station) levels else listOfNotNull(distance(r.optDouble("length")), levels,
                    r.optInt("crossings").takeIf { it > 0 }?.let { "$it persilangan sebidang" }).joinToString(" · ")
                column.addView(line(r.optString("label"), sub, money(if (rOn) r.optDouble("onCost") else r.optDouble("cost")), !rOn,
                    check(rOn, if (station) (if (rOn) "Matikan stasiun ini" else "Nyalakan stasiun ini") else if (rOn) "Matikan ruas ini" else "Nyalakan ruas ini") { flip(r) }, r
                ), gap(4).apply { marginStart = StudioUi.dp(a, 28) })
            }
            if (junctions > 0) {
                val jOn = p.optDouble("junctionOnCost"); val jCost = p.optDouble("junctionCost")
                column.addView(line("$junctions persimpangan", "Ikut menyala bersama rel di sekitarnya", money(if (jOn > 0) jOn else jCost), !(jOn > 0 || jCost == 0.0), null, null),
                    gap(4).apply { marginStart = StudioUi.dp(a, 28) })
            }
        }

        /** Web switchRow: all its pieces off when any is on, else all on. */
        fun flip(r: JSONObject) {
            val on = r.optBoolean("on")
            run(if (on) "Matikan rencana" else "Nyalakan rencana", SWITCH, if (on) 1 else 0, ints(r.optJSONArray("edges")), ints(r.optJSONArray("stations")))
        }

        fun line(title: String, subtitle: String?, cost: String, off: Boolean, box: CheckBox?, at: JSONObject?): LinearLayout =
            LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = if (at != null) StudioDepot.touch(a, 0xd9ffffff.toInt(), 14) else StudioUi.card(a, 14)
                setPadding(StudioUi.dp(a, 2), StudioUi.dp(a, 2), StudioUi.dp(a, 10), StudioUi.dp(a, 2))
                minimumHeight = StudioUi.dp(a, 48)
                alpha = if (off) .6f else 1f
                if (box != null) addView(box) else addView(View(a), LinearLayout.LayoutParams(StudioUi.dp(a, 12), 1))
                val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(StudioUi.text(a, title, 14f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (subtitle != null) texts.addView(StudioUi.text(a, subtitle, 12f, false, StudioUi.MUTED))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = StudioUi.dp(a, 2) })
                addView(StudioUi.text(a, cost, 13f, true, if (off) StudioUi.MUTED else StudioUi.INK))
                at?.optJSONArray("at")?.let { xy ->
                    isClickable = true
                    contentDescription = "$title, ${subtitle ?: ""}, $cost. Ketuk untuk melihat di peta."
                    setOnClickListener {
                        dialog.dismiss()
                        focus(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat())
                    }
                }
            }

        /** The quote's rows under their kinds' headings (web KindRows); [onlyOther]: trains, buses and ferries only. */
        fun kinds(quote: JSONArray, onlyOther: Boolean) {
            val groups = linkedMapOf<String, MutableList<JSONObject>>()
            for (i in 0 until quote.length()) {
                val r = quote.getJSONObject(i)
                if (onlyOther && r.optBoolean("track")) continue
                groups.getOrPut(r.optString("title")) { mutableListOf() }.add(r)
            }
            for ((title, rows) in groups.entries.sortedBy { (_, rows) -> rows[0].optInt("kind").let { if (it == 15) 5.5 else it.toDouble() } }) {
                column.addView(StudioUi.section(a, title))
                for (r in rows) column.addView(line(r.optString("label"), null, money(r.optDouble("cost")), false, null, null), gap(4))
            }
        }

        /** Web lineActions "Dibangun dengan biaya …" and blueprintPick's notices. */
        fun say(answer: JSONObject) {
            val n = answer.optInt("edges"); val skipped = answer.optInt("skipped"); val to = answer.optString("to")
            val text = if (skipped > 0) "${n - skipped} dari $n potongan diubah jadi $to. $skipped tidak bisa diubah." else "$n potongan diubah jadi $to."
            Toast.makeText(a, text, Toast.LENGTH_SHORT).show()
        }

        fun remove(what: String) {
            val preview = runCatching { JSONObject(edit(handle(), clock(), REMOVE, 0, IntArray(0), IntArray(0), false)) }.getOrElse { JSONObject().put("error", it.localizedMessage) }
            if (preview.has("error")) { WorldSound.refused(a, preview.optString("error")); return }
            val lines = preview.optInt("lines")
            val message = "Rencana yang dicentang ($what" + (if (preview.optDouble("length") > 0) ", ${distance(preview.optDouble("length"))}" else "") + ") akan dihapus. Bisa diurungkan." +
                if (lines > 0) "\n\n$lines line rencana kehilangan pemberhentian atau rutenya terputus." else ""
            val confirm = StudioDialogBuilder(a).setTitle("Hapus rencana terpilih?").setMessage(message)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    run("Hapus rencana", REMOVE) { Toast.makeText(a, "Berhasil menghapus $what.", Toast.LENGTH_SHORT).show() }
                }.show()
            DemolitionPanel.danger(confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
        }

        /** Web Construct: everything switched on, with the bus and ferry routes, in one charged edit. */
        fun build() {
            if (last.optDouble("total") <= 0) return
            run("Bangun rencana", CONSTRUCT) {
                Toast.makeText(a, "Dibangun dengan biaya ${money(it.optDouble("cost"))}.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
