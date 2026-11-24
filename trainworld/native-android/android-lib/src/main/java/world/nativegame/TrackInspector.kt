// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/**
 * The web track inspector (ui/inspector/TrackInspector.tsx) for one built piece of track: its kind,
 * length and level, rebuilding it in place (PieceRebuild.tsx: level, track count, track type, with a
 * quoted price and a question), its top speed and the player's limit (PieceLimit.tsx), the slowest
 * curve, cost and upkeep, and the lines on it. The figures and edits come from `src/track_inspection.rs`.
 */
internal object TrackInspector {
    @JvmStatic external fun read(handle: Long, edge: Int): String
    @JvmStatic external fun quote(handle: Long, edge: Int, level: Int, kind: Int, tracks: Int): String
    @JvmStatic external fun rebuild(handle: Long, edge: Int, level: Int, kind: Int, tracks: Int, clock: Double): String
    @JvmStatic external fun limit(handle: Long, edge: Int, kmh: Int, clock: Double): String

    /** `rebuild_quote`'s "keep the level". */
    private const val KEEP_LEVEL = 99
    /** Web `KIND` (game/rebuild.ts). */
    private const val REGULAR = 1
    private const val HSR = 2
    private const val TRAM = 3
    /** Web PieceLimit `STEP` and `MIN`, km/h. */
    private const val STEP = 10
    private const val MIN = 40
    private val LEVELS = listOf(3, 2, 1, 0, -1, -2, -3)
    private val locale: Locale = Locale.forLanguageTag("id-ID")

    /** Web `levelLabel` (format.ts): "+1", "0", "−2". */
    private fun levelLabel(l: Int) = if (l > 0) "+$l" else if (l < 0) "−${-l}" else "0"
    /** Web `levelName` (format.ts LEVEL_NAMES). */
    private fun levelName(l: Int) = when {
        l >= 3 -> "Viaduk tinggi"; l > 0 -> "Viaduk"; l == 0 -> "Permukaan tanah"
        l == -1 -> "Galian terbuka"; l == -2 -> "Terowongan"; else -> "Terowongan dalam"
    }
    /** Web `distance` (format.ts). */
    private fun distance(m: Double) = if (m < 1000) "${Math.round(m)} m" else String.format(locale, "%.1f km", m / 1000)
    private fun speed(kmh: Double) = "${Math.round(kmh)} km/jam"

    /** What the player picked; null keeps what the piece has (web `RebuildChoice`). */
    private data class Choice(val level: Int? = null, val kind: Int? = null, val tracks: Int? = null) {
        val chosen get() = level != null || kind != null || tracks != null
    }

    /** Web `rebuildTo`: "tingkat +1 dan rel ganda". */
    private fun rebuildTo(c: Choice): String {
        val parts = listOfNotNull(
            c.level?.let { "tingkat ${levelLabel(it)}" },
            c.kind?.let { when (it) { HSR -> "rel kecepatan tinggi"; TRAM -> "rel trem"; else -> "rel biasa" } },
            c.tracks?.let { if (it == 1) "rel tunggal" else "rel ganda" },
        )
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " dan " + parts.last()
        }
    }

    /**
     * Opens the panel for built piece [edge]. [transaction] runs an edit with the world settled
     * (the host's `editPanel`); [changed] records the undo step under its label and redraws.
     */
    fun show(activity: Activity, handle: Long, edge: Int, transaction: PanelEdit, changed: (String) -> Unit,
             openLine: (Int) -> Unit, remove: (Int) -> Unit, extra: ((Dialog) -> View)? = null) {
        val first = read(handle, edge)
        if (first == "null") {
            Toast.makeText(activity, "Rel ini sudah tidak ada.", Toast.LENGTH_SHORT).show()
            return
        }
        var piece = JSONObject(first)
        var choice = Choice()
        lateinit var dialog: Dialog
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val currency = StudioCurrency.current(activity)
        fun money(millionUsd: Double) = StudioFormat.money(millionUsd * 1e6, currency)

        fun render() {
            content.removeAllViews()
            val crossover = piece.getBoolean("crossover")
            val tram = piece.getBoolean("tram")
            val hsr = piece.getBoolean("hsr")
            val tracks = piece.getInt("tracks")
            val lo = piece.getInt("levelMin"); val hi = piece.getInt("levelMax")
            val kindWord = when {
                crossover -> "crossover"; piece.getInt("kind") == 2 -> "rel trem di jalan"; tram -> "rel trem"
                hsr -> "rel kecepatan tinggi"; tracks == 2 -> "rel ganda"; else -> "rel tunggal"
            }
            val lv = if (lo == hi) "Level ${levelLabel(lo)}" else "Level ${levelLabel(lo)} sampai ${levelLabel(hi)}"
            content.addView(StudioUi.text(activity, listOf(distance(piece.getDouble("length")), kindWord, lv).joinToString(" · "), 14f, true, StudioUi.MUTED).apply {
                setPadding(dp(4), 0, dp(4), dp(6))
            })

            // T-3408: Rebuild beside Remove, its choices above (PieceRebuild)
            if (!crossover) {
                val ownLevel = if (lo == hi) lo else null
                val ownKind = if (tram) TRAM else if (hsr) HSR else REGULAR
                val ownTracks = if (tracks == 1) 1 else 2
                fun pick(level: Int? = choice.level, kind: Int? = choice.kind, tracks: Int? = choice.tracks) {
                    choice = Choice(level?.takeIf { it != ownLevel }, kind?.takeIf { it != ownKind }, tracks?.takeIf { it != ownTracks })
                    render()
                }
                fun option(row: WrapRow, label: String, on: Boolean, description: String, click: () -> Unit) {
                    row.addView(StudioUi.button(activity, label, if (on) StudioUi.Tone.BLUE else StudioUi.Tone.SURFACE, click = click).apply {
                        contentDescription = description
                        isSelected = on
                    })
                }
                val open = piece.getJSONArray("levels").let { a -> List(a.length()) { a.getInt(it) } }
                if (open.isNotEmpty()) {
                    content.addView(StudioUi.section(activity, "Tingkat"))
                    val row = WrapRow(activity)
                    val shown = choice.level ?: ownLevel
                    for (l in LEVELS.filter { it == ownLevel || it in open })
                        option(row, levelLabel(l), l == shown, "${levelName(l)} (${levelLabel(l)})") { pick(level = l) }
                    content.addView(row)
                }
                content.addView(StudioUi.section(activity, "Jumlah rel"))
                WrapRow(activity).also { row ->
                    val shown = choice.tracks ?: ownTracks
                    option(row, "Tunggal", shown == 1, "Rel tunggal") { pick(tracks = 1) }
                    option(row, "Ganda", shown == 2, "Rel ganda") { pick(tracks = 2) }
                    content.addView(row)
                }
                // the piece's own family's kinds, and the other family's where open; only when it offers a change
                val openKinds = piece.getJSONArray("kinds").let { a -> List(a.length()) { a.getInt(it) } }
                val kinds = listOf(REGULAR, HSR, TRAM).filter { (it == TRAM) == tram || it in openKinds }
                if (kinds.size > 1) {
                    content.addView(StudioUi.section(activity, "Jenis rel"))
                    val row = WrapRow(activity)
                    val shown = choice.kind ?: ownKind
                    for (k in kinds) {
                        val label = when (k) { HSR -> "Cepat"; TRAM -> "Trem"; else -> "Biasa" }
                        option(row, label, k == shown, "Jenis rel $label") { pick(kind = k) }
                    }
                    content.addView(row)
                }
                val q = if (choice.chosen) JSONObject(quote(handle, edge, choice.level ?: KEEP_LEVEL, choice.kind ?: 0, choice.tracks ?: 0)) else null
                val changes = q != null && (q.getInt("changed") > 0 || q.getDouble("stations") > 0)
                val refused = q?.getInt("refused") ?: 0
                // web `rebuildTipFor`, shown under the choices since a phone has no hover
                val tip = when {
                    q == null -> "Pilih tingkat, jenis rel, atau jumlah rel"
                    !changes -> listOf(if (refused > 0) "Potongan ini tak bisa diubah" else "Pilih tingkat, jenis rel, atau jumlah rel", q.optString("why")).filter { it.isNotEmpty() }.joinToString("\n")
                    else -> listOf("Bangun ulang ${distance(q.getDouble("length"))} jadi ${rebuildTo(choice)}",
                        if (q.getDouble("price") > 0) money(q.getDouble("price")) else "Gratis").joinToString(" · ")
                }
                content.addView(StudioUi.text(activity, tip, 13f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(8), dp(4), 0) })
                val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                actions.addView(StudioUi.button(activity, "Bangun ulang", StudioUi.Tone.PRIMARY, StudioUi.Icon.BUILD) {
                    if (q == null) return@button
                    if (!changes) {
                        Toast.makeText(activity, listOf("Tidak ada yang bisa dibangun ulang.", q.optString("why")).filter { it.isNotEmpty() }.joinToString(" "), Toast.LENGTH_LONG).show()
                        return@button
                    }
                    val to = rebuildTo(choice)
                    val what = "${distance(q.getDouble("length"))} rel yang sudah dibangun"
                    val price = q.getDouble("price")
                    // web `rebuild.question` / `rebuild.questionFree`
                    val question = if (price > 0) "Bangun ulang $what jadi $to dengan biaya ${money(price)}? Tidak ada uang kembali untuk yang ada sekarang."
                        else "Bangun ulang $what jadi $to? Gratis. Tidak ada uang kembali untuk yang ada sekarang."
                    val asked = choice
                    StudioDialogBuilder(activity).setTitle("Bangun ulang rel?").setMessage(question)
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Bangun ulang") { _, _ ->
                            transaction { now ->
                                val r = JSONObject(rebuild(handle, edge, asked.level ?: KEEP_LEVEL, asked.kind ?: 0, asked.tracks ?: 0, now))
                                if (r.has("error")) {
                                    Toast.makeText(activity, r.getString("error"), Toast.LENGTH_LONG).show()
                                    return@transaction
                                }
                                changed("Bangun ulang rel")
                                Toast.makeText(activity, "1 potongan dibangun ulang jadi $to.", Toast.LENGTH_SHORT).show()
                                // the title and choices follow the piece as it now is
                                dialog.dismiss()
                                show(activity, handle, edge, transaction, changed, openLine, remove, extra)
                            }
                        }.show()
                }.apply { isEnabled = q != null && (changes || refused > 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(StudioUi.button(activity, "Hapus rel", icon = StudioUi.Icon.DELETE) { dialog.dismiss(); remove(edge) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            } else {
                content.addView(StudioUi.button(activity, "Hapus crossover", icon = StudioUi.Icon.DELETE) { dialog.dismiss(); remove(edge) })
            }

            // the facts (web `ul.rows.top-speed`)
            val facts = StudioDecision(activity, "Ruas rel")
            val top = piece.getDouble("top")
            val have = piece.getInt("limit")
            if (!crossover) {
                facts.fact("Kecepatan puncak", speed(top))
                if (!tram && have == 0) facts.addView(StudioUi.text(activity,
                    if (hsr) "Kereta cepat melaju hingga 320 km/jam di sini dan kereta biasa hingga 160 km/jam"
                    else "Kereta melaju hingga 160 km/jam di sini. Di atas 160 butuh rel kecepatan tinggi.", 12f, true, StudioUi.MUTED).apply {
                    setPadding(dp(4), dp(4), dp(4), 0)
                })
            }
            facts.fact("Tikungan paling lambat", speed(piece.getDouble("slowest")))
            facts.fact("Biaya bangun", money(piece.getDouble("cost")))
            if (!crossover) facts.fact("Perawatan", "${money(piece.getDouble("upkeep"))}/hari")
            content.addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

            // web PieceLimit: Batasi toggles a limit at the kind's top speed; the stepper holds trains to less
            if (!crossover) {
                fun send(kmh: Int) = transaction { now ->
                    val r = JSONObject(limit(handle, edge, kmh, now))
                    if (r.has("error")) {
                        Toast.makeText(activity, r.getString("error"), Toast.LENGTH_LONG).show()
                        return@transaction
                    }
                    if (r.optBoolean("changed")) changed(if (kmh == 0) "Hapus batas laju" else "Batas laju rel")
                    read(handle, edge).takeIf { it != "null" }?.let { piece = JSONObject(it) }
                    render()
                }
                val limitRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                limitRow.addView(StudioUi.text(activity, "Batas laju", 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
                if (have > 0) {
                    fun step(d: Int) {
                        val to = (have + d * STEP).coerceIn(MIN, top.toInt())
                        if (to != have) send(to)
                    }
                    limitRow.addView(StudioUi.iconButton(activity, "Turunkan batas laju", StudioUi.Icon.MINUS) { step(-1) }.apply { isEnabled = have > MIN })
                    limitRow.addView(StudioUi.text(activity, speed(have.toDouble()), 16f).apply {
                        typeface = StudioUi.heavy(activity); gravity = Gravity.CENTER
                        minWidth = dp(96)
                    })
                    limitRow.addView(StudioUi.iconButton(activity, "Naikkan batas laju", StudioUi.Icon.PLUS) { step(1) }.apply { isEnabled = have < top })
                }
                limitRow.addView(StudioUi.button(activity, "Batasi", if (have > 0) StudioUi.Tone.BLUE else StudioUi.Tone.SURFACE) {
                    send(if (have > 0) 0 else top.toInt())
                }.apply { isSelected = have > 0; contentDescription = if (have > 0) "Hapus batas laju" else "Batasi laju di rel ini" },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                content.addView(limitRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }

            // Lini di sini
            content.addView(StudioUi.section(activity, "Lini di sini"))
            val lines = piece.getJSONArray("lines")
            if (lines.length() == 0) content.addView(StudioUi.text(activity, "Belum ada lini yang lewat rel ini.", 13f, true, StudioUi.MUTED).apply {
                setPadding(dp(4), 0, dp(4), 0)
            })
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                val id = line.getInt("id")
                val name = line.getString("name")
                val row = StudioUi.row(activity, null, 0, name) { dialog.dismiss(); openLine(id) }
                row.addView(StudioUi.badge(activity, name, Color.BLACK or line.getInt("color"), 32), 0,
                    LinearLayout.LayoutParams(dp(32), dp(32)))
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            extra?.let { content.addView(it(dialog), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        }

        val title = when {
            piece.getBoolean("crossover") -> "Crossover"
            piece.getInt("kind") == 2 -> "Rel trem di jalan"
            piece.getBoolean("tram") -> "Rel trem"
            piece.getBoolean("hsr") -> "Rel kecepatan tinggi"
            piece.getInt("tracks") == 2 -> "Rel ganda"
            else -> "Rel tunggal"
        }
        dialog = StudioUi.sheet(activity, title, .78f) { column, _ ->
            column.addView(content)
        }
        render()
    }
}
