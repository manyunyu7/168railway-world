// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Achievements as the web has them (game/achievements.ts, ui/inspector/AchievementsView.tsx,
 * ui/Achievements.tsx): four tiered ones, each a ladder of medals, and firsts earned once, some
 * hidden until earned. Rust (`achievements.rs`) works out the steps met now and keeps each earned
 * step in the save with the game second it landed, the web's own record.
 */
internal object StudioAchievements {
    @JvmStatic external fun figures(save: ByteArray, manifest: String): String
    @JvmStatic external fun record(handle: Long, met: String, clock: Double): String

    class Tiered(val id: String, val name: String, val tiers: DoubleArray, val color: Int)
    class First(val id: String, val name: String, val rule: String?, val hidden: Boolean = false, val text: (JSONObject) -> String)

    private val ID = Locale.forLanguageTag("id-ID")
    private fun num(v: Double, dp: Int = 0) = NumberFormat.getNumberInstance(ID).apply { minimumFractionDigits = dp; maximumFractionDigits = dp }.format(v)
    /** web `short`: three significant figures, 17.2M, 669k, 1B */
    fun short(n: Double): String {
        val (u, s) = when { n >= 999.5e6 -> 1e9 to "B"; n >= 999.5e3 -> 1e6 to "M"; n >= 999.5 -> 1e3 to "k"; else -> 1.0 to "" }
        return java.math.BigDecimal(n / u).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString() + s
    }
    private fun bigDistance(m: Double) = num(m / 1000, if (m / 1000 >= 100) 0 else 1).let { if (it.endsWith(",0")) it.dropLast(2) else it } + " km"
    private fun country(cc: String) = Locale("", cc).getDisplayCountry(ID).ifBlank { cc }
    private fun places(a: JSONObject) = a.optJSONArray("in")?.let { arr -> List(arr.length()) { country(arr.getString(it)) } }.orEmpty()
        .let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " dan " + it.last() }
    private fun of(a: JSONObject) = a.optString("of")
    private fun v(a: JSONObject) = a.optDouble("v", 0.0).takeIf { !it.isNaN() } ?: 0.0

    /** web `TIERED`, colours the medals' ribbons (achievements.css) */
    val TIERED = listOf(
        Tiered("cities", "Kota terlayani", doubleArrayOf(2.0, 10.0, 25.0, 100.0), 0xff3a78c9.toInt()),
        Tiered("trips", "Perjalanan per hari", doubleArrayOf(1e6, 10e6, 100e6, 300e6, 1e9), 0xffc8423b.toInt()),
        Tiered("station", "Stasiun tersibuk", doubleArrayOf(500e3, 1e6, 2e6, 5e6), 0xffc97a12.toInt()),
        Tiered("line", "Line tersibuk", doubleArrayOf(500e3, 1e6, 3e6, 10e6), 0xff2f8f5b.toInt()),
    )
    private const val FIRST_COLOR = 0xff8558c2.toInt()
    /** a tier's metal by its place in the ladder (web `METALS`): face and rim */
    private val METALS = listOf(0xffcd8a4e.toInt() to 0xff8a5225.toInt(), 0xffc9ccd1.toInt() to 0xff80858d.toInt(),
        0xffefc548.toInt() to 0xffa57d14.toInt(), 0xffeef7fb.toInt() to 0xff5b8fb3.toInt(), 0xff3fc68c.toInt() to 0xff177a54.toInt())

    fun tierKey(id: String, tier: Double) = "$id:${tier.toLong()}"
    /** web `tierText`: with what earned it, else the rule */
    fun tierText(id: String, tier: Double, a: JSONObject?, toast: Boolean = false): String = when (id) {
        "cities" -> if (toast) "${tier.toLong()} kota" else "${tier.toLong()} kota terlayani"
        "trips" -> "${short(tier)} perjalanan/hari"
        "station" -> "${short(tier)} naik/hari di ${a?.optString("of")?.ifBlank { null } ?: "satu stasiun"}"
        else -> "${short(tier)} penumpang/hari di ${a?.optString("of")?.ifBlank { null } ?: "satu line"}"
    }

    private fun currency(c: Context) = StudioCurrency.current(c)
    /** web `FIRSTS`, in the panel's order */
    fun firsts(c: Context): List<First> {
        val money = currency(c)
        val corridors = listOf("allJapan" to "Seluruh Jepang", "orient" to "Orient Express", "transcon" to "Lintas benua", "siberian" to "Trans-Siberia",
            "andean" to "Lintas Andes", "cape" to "Cape ke Cairo", "ghan" to "The Ghan", "silk" to "Jalur Sutra")
        val nations = listOf("JP", "US", "ES", "GB", "IT", "DE", "KR", "TW", "CN", "ID", "MX", "BR", "AU", "TR")
        fun share(a: JSONObject) = num(v(a), 1) + "%"
        return listOf(
            First("border", "Lintas batas", "Line dengan perhentian di dua negara") { "${of(it)} berhenti di ${places(it)}" },
            First("continents", "Dua benua", "Line dengan perhentian di dua benua") { "${of(it)} berhenti di ${it.optJSONArray("in")?.let { a -> List(a.length()) { i -> a.getString(i) }.joinToString(" dan ") }.orEmpty()}" },
            First("long", "Jalan terpanjang", "Line sepanjang ${bigDistance(5e6)}") { "${of(it)} panjangnya ${bigDistance(v(it))}" },
            First("pearls", "Untaian mutiara", "Line yang untung, panjangnya minimal ${bigDistance(3e6)}") { "${of(it)} panjangnya ${bigDistance(v(it))} dan untung" },
            First("money", "Kereta uang", "Line dengan laba ${money.compact(200.0)} dalam sehari") { "${of(it)} meraih laba ${money.compact(v(it) / 1e6)} hari ini" },
            First("bullet", "Kereta peluru", "Kereta yang melaju 300 km/h") { "${of(it)} mencapai ${Math.round(v(it))} km/h" },
            First("circle", "Lingkar kota", "Line lingkar dengan 10 perhentian") { "${of(it)} melingkar dengan ${v(it).toLong()} perhentian" },
            First("change", "Serba pindah", "Rata-rata satu kali pindah per perjalanan, dengan ${short(100e3)} perjalanan/hari") { "${num(v(it), 2)} pindah per perjalanan" },
            First("city", "Kota angkutan umum", "Kota yang 20% perjalanannya naik angkutan umum") { "${v(it).toInt()}% perjalanan di dalam ${of(it)} naik angkutan umum" },
            First("intercity", "Tetap di darat", "Kota yang 20% perjalanannya ke tempat lain naik angkutan umum") { "${v(it).toInt()}% perjalanan antara ${of(it)} dan tempat lain naik angkutan umum" },
            First("sleeper", "Kereta tidur", "Penumpang di satu kereta selama 12 jam") { "Penumpang menghabiskan ${num(v(it), 1)} jam di ${of(it)}" },
            First("trainsfan", "keretamania", "Bermain selama 100 jam") { "${num(v(it))} jam bermain" },
        ) + corridors.map { (id, name) ->
            First(id, name, null, hidden = true) { "Kereta berjalan dari ${of(it)} ke ${it.optJSONArray("in")?.optString(0).orEmpty()}" }
        } + First("europe", "Grand Tour", null, hidden = true) { "Angkutan umum membawa ${share(it)} perjalanan di Eropa" } +
            nations.map { cc ->
                val name = when (cc) { "JP" -> "Japan Railways"; "GB" -> "British Rail"; "DE" -> "Bundesbahn"; "IT" -> "Ferrovie"; else -> "Kereta Api ${country(cc)}" }
                First("nation:$cc", name, null, hidden = true) { "Angkutan umum membawa ${share(it)} perjalanan di ${country(cc)}" }
            }
    }

    /** web `when`: "Senin, hari ke-406, pukul 14:08" on the game clock */
    fun whenAt(at: Double) = StudioAllCities.whenEarned(at, 0.0)

    // ---- state of the open world
    private val executor = Executors.newSingleThreadExecutor()
    private var figuresNow: JSONObject? = null
    private var got = JSONObject()
    private var sandbox = false
    private var busy = false
    private var world = 0L

    /** Forget the last world's figures (a world opened or closed). */
    fun reset(handle: Long) {
        if (handle == world) return
        world = handle; figuresNow = null; got = JSONObject(); busy = false
        if (handle != 0L) read(handle)
    }
    private fun read(handle: Long, met: String = "{}", clock: Double = 0.0): List<String> {
        val r = runCatching { JSONObject(record(handle, met, clock)) }.getOrNull() ?: return emptyList()
        sandbox = r.optString("mode") == "sandbox"
        got = r.optJSONObject("got") ?: JSONObject()
        val fresh = r.optJSONArray("fresh") ?: return emptyList()
        return List(fresh.length()) { fresh.getString(it) }
    }

    /**
     * Works out the steps met now off the UI thread, then keeps the new ones in the world and toasts
     * them (web `toastsFor`: each tiered achievement's highest new tier, then each first).
     */
    fun refresh(activity: Activity, handle: () -> Long, save: ByteArray, manifest: (ByteArray) -> String?,
                clock: () -> Double, cityName: (String) -> String?, done: (Boolean) -> Unit = {}) {
        val start = handle()
        reset(start)
        if (busy || start == 0L || sandbox) { done(false); return }
        busy = true
        executor.submit {
            val result = runCatching { JSONObject(figures(save, manifest(save).orEmpty())) }.getOrNull()
            activity.runOnUiThread {
                busy = false
                if (activity.isDestroyed || handle() != start) return@runOnUiThread
                val before = got.length()
                if (result != null && !result.has("error")) {
                    figuresNow = result
                    val met = result.optJSONObject("met") ?: JSONObject()
                    // the city first names its city as the web keeps it
                    met.optJSONObject("city")?.let { c -> c.put("of", cityName(c.optString("zone")) ?: c.optString("zone")); c.remove("zone") }
                    toast(activity, read(start, met.toString(), clock()))
                }
                done(got.length() != before)
            }
        }
    }

    private fun toast(activity: Activity, fresh: List<String>) {
        if (fresh.isEmpty()) return
        val lines = mutableListOf<String>()
        for (t in TIERED) {
            val top = t.tiers.lastOrNull { tierKey(t.id, it) in fresh } ?: continue
            lines += "${t.name}: ${tierText(t.id, top, got.optJSONObject(tierKey(t.id, top)), true)}"
        }
        for (f in firsts(activity)) if (f.id in fresh) got.optJSONObject(f.id)?.let { lines += "${f.name}: ${f.text(it)}" }
        val text = if (lines.size > 3) "${lines.size} pencapaian lagi" else lines.joinToString("\n")
        Toast.makeText(activity, "Pencapaian baru\n$text", Toast.LENGTH_LONG).show()
    }

    /** A tiered achievement's figure now (web `figureOf`); null while unknown. */
    private fun figure(id: String): Double? {
        val f = figuresNow ?: return null
        return when (id) {
            "cities", "trips" -> f.optDouble(id).takeIf { !it.isNaN() }
            else -> f.optJSONObject(id)?.optDouble("v")?.takeIf { !it.isNaN() } ?: if (f.isNull("trips")) null else 0.0
        }
    }

    /** The mission card: the tiered achievement nearest its next tier, as "name: figure/next". */
    fun mission(): Pair<String, Float>? {
        if (sandbox) return null
        return TIERED.mapNotNull { t ->
            val v = figure(t.id) ?: return@mapNotNull null
            val next = t.tiers.firstOrNull { v < it } ?: return@mapNotNull null
            val label = { x: Double -> if (t.id == "cities") x.toLong().toString() else short(x) }
            Triple("${t.name}: ${label(v)}/${label(next)}", (v / next).toFloat(), t)
        }.maxByOrNull { it.second }?.let { it.first to it.second }
    }

    /** Keeps this world's earned steps in `candidate` (a simulation snapshot about to replace it);
     *  true when that changed it. */
    fun carry(handle: Long, candidate: Long): Boolean {
        if (handle != world || got.length() == 0 || candidate == 0L) return false
        val before = runCatching { JSONObject(record(candidate, "{}", 0.0)).optJSONObject("got")?.length() ?: 0 }.getOrDefault(0)
        val after = runCatching { JSONObject(record(candidate, got.toString(), 0.0)).optJSONObject("got")?.length() ?: 0 }.getOrDefault(0)
        return after != before
    }

    /** how many steps there are (hidden ones only once earned) and how many this world has */
    private fun counts(c: Context): Pair<Int, Int> {
        val firsts = firsts(c)
        val keys = TIERED.flatMap { t -> t.tiers.map { tierKey(t.id, it) } } + firsts.map { it.id }
        val hidden = firsts.filter { it.hidden }.map { it.id }.toSet()
        val earned = keys.count { got.has(it) }
        return earned to keys.size - hidden.size + hidden.count { got.has(it) }
    }

    /** web `Medal`: the disc in the tier's metal and the ribbon in the achievement's colour */
    private class Medal(c: Context, private val metal: Pair<Int, Int>?, private val ribbon: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val k = minOf(width / 16f, height / 22f)
            canvas.translate((width - 16 * k) / 2, (height - 22 * k) / 2)
            canvas.scale(k, k)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
            if (metal == null) {
                paint.color = StudioUi.BORDER; canvas.drawCircle(8f, 15f, 6.2f, paint); return
            }
            paint.style = Paint.Style.FILL
            paint.color = StudioUi.darker(ribbon)
            path.reset(); path.moveTo(14f, 0f); path.lineTo(10f, 0f); path.lineTo(5.6f, 9.6f); path.lineTo(8.6f, 11f); path.close(); canvas.drawPath(path, paint)
            paint.color = ribbon
            path.reset(); path.moveTo(2f, 0f); path.lineTo(6f, 0f); path.lineTo(10.4f, 9.6f); path.lineTo(7.4f, 11f); path.close(); canvas.drawPath(path, paint)
            paint.color = metal.first; canvas.drawCircle(8f, 15f, 6.2f, paint)
            paint.style = Paint.Style.STROKE; paint.color = metal.second; paint.strokeWidth = 1.1f; canvas.drawCircle(8f, 15f, 6.2f, paint)
            paint.strokeWidth = .8f; paint.alpha = 153; canvas.drawCircle(8f, 15f, 3.9f, paint)
        }
    }

    /** web `Ring`: `p` of the way round from the top, with the figure in the middle */
    private class Dial(c: Context, private val p: Float, private val color: Int, private val big: String, private val small: String?) : View(c) {
        private val d = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = StudioUi.face(c, true) }
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val pad = 3 * d; box.set(pad, pad, width - pad, height - pad)
            stroke.color = (color and 0x00ffffff) or 0x2e000000; canvas.drawArc(box, 0f, 360f, false, stroke)
            stroke.color = color; if (p > 0) canvas.drawArc(box, -90f, 360f * p.coerceAtMost(1f), false, stroke)
            text.color = StudioUi.INK; text.textSize = 13 * d
            val y = height / 2f + (if (small != null) -1 * d else 4 * d)
            canvas.drawText(big, width / 2f, y, text)
            if (small != null) { text.color = StudioUi.MUTED; text.textSize = 10 * d; canvas.drawText("/$small", width / 2f, y + 12 * d, text) }
        }
    }

    /** web `AchievementsView`: each tiered achievement's medals and dial, then the firsts. */
    fun show(activity: Activity) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val (earned, steps) = counts(activity)
        StudioUi.sheet(activity, "Pencapaian") { column, _ ->
            if (sandbox) {
                column.addView(StudioUi.text(activity, "Mode bebas tidak mendapat pencapaian.", 14f, false, StudioUi.MUTED))
                return@sheet
            }
            column.addView(StudioUi.text(activity, "$earned dari $steps langkah tercapai", 13f, true, StudioUi.MUTED))
            if (figuresNow == null) column.addView(StudioUi.text(activity, "Angka terbaru sedang dihitung…", 12f, false, StudioUi.MUTED))
            for (t in TIERED) {
                val f = figure(t.id)
                val next = f?.let { v -> t.tiers.firstOrNull { v < it } }
                val p = if (f == null) 0f else if (next == null) 1f else (f / next).toFloat()
                val label = { x: Double -> if (t.id == "cities") x.toLong().toString() else short(x) }
                val row = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = StudioUi.card(activity, 18)
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                    minimumHeight = dp(64)
                }
                val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                left.addView(StudioUi.text(activity, t.name, 15f, true))
                val medals = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                // what each tier is: the latest reached with when, then the next one's rule
                var note: String? = null
                t.tiers.forEachIndexed { k, x ->
                    val a = got.optJSONObject(tierKey(t.id, x))
                    medals.addView(Medal(activity, if (a != null) METALS[k] else null, t.color), LinearLayout.LayoutParams(dp(18), dp(25)).apply { marginEnd = dp(4) })
                    if (a != null) note = "${tierText(t.id, x, a)}, tercapai pada ${whenAt(a.optDouble("at"))}"
                }
                left.addView(medals, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                val ahead = t.tiers.firstOrNull { !got.has(tierKey(t.id, it)) }?.let { "Berikutnya: ${tierText(t.id, it, null)}" }
                listOfNotNull(note, ahead).forEach { left.addView(StudioUi.text(activity, it, 12f, false, StudioUi.MUTED)) }
                val of = figuresNow?.optJSONObject(t.id)?.optString("of")?.ifBlank { null }
                if (of != null) left.addView(StudioUi.text(activity, "Sekarang: $of", 12f, false, StudioUi.MUTED))
                row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Dial(activity, p, t.color, f?.let(label) ?: "–", next?.let(label)), LinearLayout.LayoutParams(dp(56), dp(56)))
                column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            column.addView(StudioUi.section(activity, "Pertama kali"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val all = firsts(activity)
            for (f in all.filter { got.has(it.id) } + all.filter { !got.has(it.id) && !it.hidden }) {
                val a = got.optJSONObject(f.id)
                val row = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = StudioUi.card(activity, 16)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    minimumHeight = dp(52)
                    alpha = if (a != null) 1f else .72f
                }
                row.addView(Medal(activity, if (a != null) METALS[2] else null, FIRST_COLOR), LinearLayout.LayoutParams(dp(20), dp(28)))
                val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                body.addView(StudioUi.text(activity, f.name, 14f, true, if (a != null) StudioUi.INK else StudioUi.MUTED))
                val sub = if (a != null) "${f.text(a)}\nTercapai pada ${whenAt(a.optDouble("at"))}" else listOfNotNull(f.rule, now(f.id)).joinToString("\n")
                if (sub.isNotBlank()) body.addView(StudioUi.text(activity, sub, 12f, false, StudioUi.MUTED))
                row.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                if (a != null) row.addView(StudioUi.text(activity, "hari ${num(kotlin.math.floor(a.optDouble("at") / 86400))}", 12f, false, StudioUi.MUTED))
                column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
        }
    }

    /** web `First.now`: the figure now for a step not yet earned */
    private fun now(id: String): String? = when (id) {
        "change" -> figuresNow?.optDouble("transfersPerTrip")?.takeIf { !it.isNaN() }?.let { "Sekarang ${num(it, 2)} pindah per perjalanan" }
        else -> null
    }
}
