// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * The track inspector's Signalling section (web ui/inspector/PieceSignal.tsx) and the Signals lens: four
 * levels per piece of track, from open block to CBTC (sim `track/signal.rs`). A pick applies at once to the
 * piece or its whole block (station to station or junction); dearer signalling on built track is paid then.
 * The figures and the edit come from `src/signalling.rs` over the engine's `TrackApi`, as on the web.
 */
internal object TrackSignal {
    @JvmStatic external fun panel(handle: Long, edge: Int): String
    @JvmStatic external fun set(handle: Long, edges: IntArray, level: Int, clock: Double): String
    @JvmStatic external fun levels(handle: Long): IntArray

    const val OPEN = 0
    const val CLOSED = 1
    const val ATP = 2
    const val CBTC = 3

    /** Chip label, long name and what it does (web `signal.*` in id.json). */
    private val NAMES = listOf("Mekanik", "Elektrik", "ATP", "CBTC")
    private val LONG = listOf("Mekanik, blok terbuka", "Elektrik, blok tertutup", "Blok otomatis rapat dengan ATP", "CBTC, blok bergerak")
    private val TIPS = listOf(
        "Sinyal mekanik, blok terbuka: hanya satu kereta di antara dua stasiun atau persimpangan, paling cepat 90 km/jam. Gratis, tanpa perawatan sinyal.",
        "Sinyal elektrik, blok tertutup: bawaan setiap rel.",
        "Blok otomatis rapat dengan proteksi kereta (ATP): jarak antarkereta sekitar seperempat lebih rapat, persimpangan lebih cepat lega.",
        "CBTC, blok bergerak: jarak antarkereta sekitar separuhnya, persimpangan dan peron lebih cepat lega. Paling mahal.",
    )

    /** The lens colours, as the web's Signals view (the Profit ramp's ends): open block red, closed block
     *  the plain track grey, ATP light blue, CBTC deep blue. */
    private val LENS = intArrayOf(0xffb2182b.toInt(), 0xff879194.toInt(), 0xff8ecae6.toInt(), 0xff2166ac.toInt())
    val LENS_SWATCH = LENS.copyOf()
    const val LENS_LEGEND = "Merah = blok terbuka · abu = blok tertutup · biru muda = ATP · biru tua = CBTC"
    fun lensColor(level: Int) = LENS.getOrElse(level) { LENS[CLOSED] }

    fun longName(level: Int) = LONG.getOrElse(level) { LONG[CLOSED] }

    /** Whether "seluruh petak" is on, kept across the inspector's redraws while it is open. */
    private var wholeBlock = true

    /**
     * The section for built piece [edge]; [transaction] runs the edit with the world settled, [changed]
     * records the undo step and redraws, [refresh] draws the inspector again with the piece as it now is.
     */
    fun section(activity: Activity, handle: Long, edge: Int, transaction: PanelEdit, changed: (String) -> Unit, refresh: () -> Unit): View? {
        val raw = panel(handle, edge)
        if (raw == "null") return null
        val p = JSONObject(raw)
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val currency = StudioCurrency.current(activity)
        fun money(millionUsd: Double) = StudioFormat.money(millionUsd * 1e6, currency)
        val count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
        val have = p.getDouble("level").toInt()
        val tph = p.getJSONArray("tph")
        val price = p.getJSONArray("price")
        val upkeep = p.getJSONArray("upkeep")
        val block = p.getJSONArray("block").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val whole = wholeBlock && block.size > 1
        fun most(level: Int) = "maks. ${count.format(Math.floor(tph.getDouble(level)))} kereta/jam per arah"

        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(StudioUi.section(activity, "Persinyalan"))
        val row = WrapRow(activity)
        for (level in OPEN..CBTC) {
            row.addView(StudioUi.choiceChip(activity, NAMES[level], level == have) {
                if (level == have && !whole) return@choiceChip
                val edges = if (whole) block else intArrayOf(edge)
                transaction { now ->
                    val r = JSONObject(set(handle, edges, level, now))
                    if (r.has("error")) {
                        Toast.makeText(activity, r.getString("error"), Toast.LENGTH_LONG).show()
                        return@transaction
                    }
                    if (r.optBoolean("changed")) {
                        changed("Persinyalan ${NAMES[level]}")
                        val paid = r.optDouble("charge", 0.0)
                        Toast.makeText(activity, if (paid > 0) "${LONG[level]} · ${money(paid)}" else LONG[level], Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            }.apply {
                isSelected = level == have
                contentDescription = "${LONG[level]}. ${TIPS[level]} ${most(level)}"
            })
        }
        box.addView(row)
        if (block.size > 1) {
            box.addView(StudioUi.choiceChip(activity, "Seluruh petak (${block.size} ruas)", whole) {
                wholeBlock = !wholeBlock
                refresh()
            }.apply { isSelected = whole; contentDescription = "Terapkan ke seluruh petak, stasiun ke stasiun" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        // what the current level does, and what each pick would cost (a phone has no hover)
        box.addView(StudioUi.text(activity, TIPS[have], 12f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        val facts = StudioDecision(activity, LONG[have])
        facts.fact("Kapasitas rel", most(have))
        if (have == OPEN) facts.fact("Kecepatan maks.", "90 km/jam (blok terbuka)")
        if (price.getDouble(have) > 0) facts.fact("Biaya persinyalan", money(price.getDouble(have)))
        if (upkeep.getDouble(have) > 0) facts.fact("Perawatan persinyalan", "${money(upkeep.getDouble(have))}/hari")
        val quotes = p.getJSONArray("quote")
        for (level in OPEN..CBTC) {
            if (level == have) continue
            val q = quotes.getJSONObject(level).getJSONArray(if (whole) "block" else "piece")
            val cost = when {
                q.getDouble(0) > 0 -> money(q.getDouble(0))
                q.getDouble(1) > 0 -> "+${money(q.getDouble(1))} rencana"
                else -> "gratis"
            }
            facts.fact("→ ${NAMES[level]}", "${count.format(Math.floor(tph.getDouble(level)))}/jam · $cost")
        }
        box.addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return box
    }
}
