// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.floor

/**
 * The public holidays the clock card's sky marks (web app/src/game/holidays.ts and
 * holidayCalendar.ts, T-862, T-264): each country's holidays and superholiday runs from
 * assets/world-native/holidays.json, a copy of app/src/game/holidays.json (pipeline/holidays_json.py),
 * read once on a background thread the first time the sky asks. Display only: the sim's own day
 * kinds are Rust's. A game year takes the holidays of the real year whose January 1 falls on the same
 * weekday; a holiday is a whole local day of its country.
 */
internal object SkyHolidays {
    /** A superholiday run in game days: going out from [out], away from [away], coming back from
     *  [back], over at [end] (exclusive). */
    class Run(val name: String, val out: Long, val away: Long, val back: Long, val end: Long)

    class Data(
        val names: Array<String>,
        /** country -> January 1's weekday (0..6) -> [day of year, name index]... */
        val days: Map<String, Array<IntArray?>>,
        /** country -> January 1's weekday -> [first going-out day, out, away, back, name]... */
        val supers: Map<String, Array<IntArray?>>,
    )

    @Volatile var data: Data? = null
        private set
    private var asked = false
    private val waiting = mutableListOf<() -> Unit>()

    /** Starts the one read; [done] runs on the main thread once the data is in (now, if it is). */
    fun load(context: Context, done: () -> Unit) {
        if (data != null) { done(); return }
        waiting += done
        if (asked) return
        asked = true
        val app = context.applicationContext
        Thread({
            val read = runCatching { parse(app.assets.open("world-native/holidays.json").bufferedReader().use { it.readText() }) }
                .onFailure { android.util.Log.w("WorldSky", "holidays.json unreadable", it) }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                data = read
                if (read == null) asked = false
                val list = waiting.toList(); waiting.clear()
                if (read != null) list.forEach { it() }
            }
        }, "sky-holidays").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun parse(text: String): Data {
        val o = JSONObject(text)
        val n = o.getJSONArray("names")
        val names = Array(n.length()) { n.getString(it) }
        fun table(key: String, width: Int): Map<String, Array<IntArray?>> {
            val out = HashMap<String, Array<IntArray?>>()
            val all = o.optJSONObject(key) ?: return out
            for (cc in all.keys()) {
                val years = all.getJSONObject(cc)
                out[cc] = Array(7) { wd ->
                    years.optJSONArray(wd.toString())?.let { rows ->
                        IntArray(rows.length() * width) { i -> rows.getJSONArray(i / width).getInt(i % width) }
                    }
                }
            }
            return out
        }
        return Data(names, table("countries", 2), table("supers", 5))
    }

    // days.ts: game day 1 is Monday January 27 of a 365-day year
    fun weekdayOf(day: Long) = Math.floorMod(day - 1, 7L).toInt()
    fun dayOfYear(day: Long) = Math.floorMod(day - 1 + 26, 365L).toInt()
    fun newYearWeekday(day: Long) = weekdayOf(day - dayOfYear(day))

    /** The holiday's name in [country] on its local game day [day] ("A; B" for two), or null. */
    fun holidayName(d: Data, country: String, day: Long): String? {
        val rows = d.days[country]?.get(newYearWeekday(day)) ?: return null
        val doy = dayOfYear(day)
        var i = 0
        while (i < rows.size) { if (rows[i] == doy) return d.names[rows[i + 1]]; i += 2 }
        return null
    }

    /** holidayCalendar.ts superRuns: the runs of [country] that meet game days [d0, d1), in order. */
    fun superRuns(d: Data, country: String, d0: Long, d1: Long): List<Run> {
        val years = d.supers[country] ?: return emptyList()
        val out = ArrayList<Run>()
        var y0 = d0 - dayOfYear(d0) - 365
        while (y0 <= d1 + 365) {
            val rows = years[newYearWeekday(y0)]
            if (rows != null) {
                var i = 0
                while (i < rows.size) {
                    val s = y0 + rows[i]
                    val r = Run(d.names[rows[i + 4]], s, s + rows[i + 1], s + rows[i + 1] + rows[i + 2], s + rows[i + 1] + rows[i + 2] + rows[i + 3])
                    if (r.end > d0 && r.out < d1 && out.none { it.out < r.end && r.out < it.end }) out.add(r)
                    i += 5
                }
            }
            y0 += 365
        }
        return out.sortedBy { it.out }
    }

    // --- the countries the player has built in ------------------------------------------------

    /** a built station's place -> its country and the local time there, seconds ahead of the clock */
    private val placeOf = HashMap<Long, Pair<String, Int>?>()
    private var stopsKey = Long.MIN_VALUE
    private var countries: List<Pair<String, Int>> = emptyList()

    /** The countries with built stations (web railCountries, by stations rather than every built
     *  node), each with its local shift; looked up again only when the stations change. */
    fun countries(map: NetworkView): List<Pair<String, Int>> {
        val stops = map.stops.toList()
        var key = stops.size.toLong()
        for (s in stops) key = key * 31 + s.id * 7919L + s.x.toRawBits() + s.y.toRawBits()
        if (key == stopsKey) return countries
        val h = map.trainEngine()
        val zones = map.zones ?: return countries
        if (h == 0L) return countries
        val out = LinkedHashSet<Pair<String, Int>>()
        for (s in stops) {
            if (s.id < 0) continue
            val key = (s.x.toRawBits().toLong() shl 32) or (s.y.toRawBits().toLong() and 0xffffffffL)
            val p = placeOf.getOrPut(key) {
                runCatching {
                    val ll = CitySurface.lonLat(h, s.x, s.y)
                    zones.place(ll[0], ll[1])?.let { zone ->
                        FaceLayer.country(zone).takeIf { it.isNotEmpty() }?.let { it to StudioNewGame.localShift(zone) }
                    }
                }.getOrNull()
            }
            if (p != null) out.add(p)
        }
        stopsKey = key
        countries = out.sortedWith(compareBy({ it.first }, { it.second }))
        return countries
    }

    /** A mark on the sky in the sky's own local time, seconds: part 0 a public holiday, 1 going out,
     *  2 away, 3 coming back (T-264). */
    class Mark(val country: String, val name: String, val part: Int, val t0: Double, val t1: Double)

    /** web holidaysNear and rushNear for the 24 hours around [clock] (game clock seconds), drawn on
     *  a sky [skyShift] seconds ahead of the game clock. */
    fun marks(d: Data, rail: List<Pair<String, Int>>, clock: Double, skyShift: Int): List<Mark> {
        val out = ArrayList<Mark>()
        val now = clock + skyShift
        val seen = HashSet<String>()
        for ((country, shift) in rail) {
            val d0 = floor((clock + shift) / 86400).toLong()
            for (day in d0 - 1..d0 + 2) {
                val names = holidayName(d, country, day) ?: continue
                val t0 = day * 86400.0 - shift + skyShift
                if (t0 + 86400 <= now - 13 * 3600 || t0 >= now + 13 * 3600) continue
                for (name in names.split("; ")) if (seen.add("$country $t0 $name")) out.add(Mark(country, name, 0, t0, t0 + 86400))
            }
            for (r in superRuns(d, country, d0 - 1, d0 + 3)) {
                for (day in maxOf(r.out, d0 - 1) until minOf(r.end, d0 + 3)) {
                    val t0 = day * 86400.0 - shift + skyShift
                    if (t0 + 86400 <= now - 13 * 3600 || t0 >= now + 13 * 3600) continue
                    val part = if (day < r.away) 1 else if (day < r.back) 2 else 3
                    out.add(Mark(country, r.name, part, t0, t0 + 86400))
                }
            }
        }
        out.sortWith(compareBy({ it.t0 }, { it.country }))
        return out
    }
}
