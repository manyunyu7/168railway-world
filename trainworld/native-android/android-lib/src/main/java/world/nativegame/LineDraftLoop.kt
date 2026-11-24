// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.PointF

/**
 * A new line's stations closed into a loop while they are picked, as the web line tool does on a touch
 * screen (map/stopMarkers.ts `loopAt`, map/tools.ts `toggle`, T-798, T-3109, T-3118): holding the first
 * or last station closes the line, its last stop on to its first; holding any station of a closed line
 * opens it there. Whether track leads round is the engine's to say: the preview (NativeRailLine.create)
 * refuses a loop the track does not close.
 */
internal object LineDraftLoop {
    /** The line's last station is its first. */
    fun closed(order: List<Int>) = order.size >= 3 && order.first() == order.last()

    /** The answer of a hold: the new order of stations, or null and why not. */
    class Hold(val order: List<Int>?, val say: String)

    fun hold(order: List<Int>, station: Int, name: String): Hold {
        if (closed(order)) {
            val ring = order.dropLast(1)
            val k = ring.indexOf(station)
            if (k < 0) return Hold(null, "$name · Tidak bisa membuat putaran di sini")
            // a ring opened at one of its stations ends there (web T-3118)
            val open = if (ring.distinct().size == ring.size) ring.drop(k + 1) + ring.take(k + 1) else ring
            return Hold(open, "Putaran dibuka di $name.")
        }
        if (order.distinct().size >= 2 && (station == order.first() || station == order.last()))
            return Hold(order + order.first(), "Putaran tertutup.")
        return Hold(null, "$name · Tidak bisa membuat putaran di sini")
    }

    /** The station marker under a point, within the map's pick radius. */
    fun station(map: NetworkView, point: PointF): NetworkView.Station? =
        map.stops.asSequence().filter { it.id >= 0 }.minByOrNull { kotlin.math.hypot(it.x - point.x, it.y - point.y) }
            ?.takeIf { kotlin.math.hypot(it.x - point.x, it.y - point.y) <= map.pickRadiusMeters() }

    /** The line tool's status line for `n` picked stations. */
    fun status(order: List<Int>): String {
        val n = order.size
        return when {
            n == 0 -> "Ketuk stasiun pertama yang akan dilayani."
            closed(order) -> "Putaran tertutup · ${n - 1} stasiun. Tahan stasiun mana saja untuk membukanya."
            n >= 2 -> "$n stasiun · Ketuk stasiun berikutnya sesuai urutan perjalanan. Tahan stasiun ujung untuk membuat putaran."
            else -> "$n stasiun · Ketuk stasiun berikutnya sesuai urutan perjalanan."
        }
    }

    /**
     * A hold on the map while picking a line's stations: the order and the draft's points changed, or
     * `say` told why not. True when it changed.
     */
    fun apply(map: NetworkView, order: MutableList<Int>, point: PointF, say: (String) -> Unit): Boolean {
        val at = station(map, point) ?: return false.also { say("Tahan penanda stasiun untuk membuat putaran.") }
        val r = hold(order, at.id, at.name)
        val next = r.order ?: return false.also { say(r.say) }
        val points = next.map { id -> map.stops.firstOrNull { it.id == id } ?: return false.also { say("Stasiun tidak ditemukan.") } }
        order.clear(); order.addAll(next)
        map.draft.clear(); points.forEach { map.draft.add(PointF(it.x, it.y)) }
        map.bends.clear()
        map.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return true
    }
}
