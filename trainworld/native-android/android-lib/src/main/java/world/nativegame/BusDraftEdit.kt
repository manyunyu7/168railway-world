// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Web busDraw.ts (T-3109, T-3366, T-1517) for a finger: the placed stops are handles. A drag moves a stop
 * and its legs are searched again once it is let go; a drag from the route between two stops puts a new
 * stop there that follows the finger; a tap on the last stop finishes (review), on another one deletes it;
 * a hold on the last stop (or a lone one) deletes it. While a stop is dragged the legs either side are
 * drawn straight; the rest of the route keeps its roads. Each edit is a step Urungkan takes back.
 */
internal class BusDraftEdit(
    private val map: NetworkView,
    private val active: () -> Boolean,
    private val changed: () -> Unit,
    private val finish: () -> Unit,
    private val say: (String) -> Unit,
) {
    private class Snap(val draft: List<PointF>, val bends: List<Pair<Int, PointF>>)
    private class Step(val before: Snap, val after: Snap)
    /** where a press on the route between stops lands: leg [leg], after its vertex [seg], at [at] */
    private class Hit(val leg: Int, val seg: Int, val at: PointF)
    private class Press(val x: Float, val y: Float, var i: Int, val ins: Hit?, val before: Snap) {
        var held = false
        var legs: List<List<PointF>> = emptyList()
    }

    private val steps = ArrayList<Step>()
    private var press: Press? = null
    /** a stop is being dragged: route results landing now are stale */
    var dragging = false
        private set
    private val density get() = map.resources.displayMetrics.density
    private val hold = Runnable { held() }

    private fun snap() = Snap(map.draft.map { PointF(it.x, it.y) }, map.bends.map { it.first to PointF(it.second.x, it.second.y) })

    /** Stops moved onto their road by the route search still count as the same draft. */
    private fun same(a: Snap, b: Snap) = a.draft.size == b.draft.size && a.bends.size == b.bends.size &&
        a.draft.indices.all { hypot(a.draft[it].x - b.draft[it].x, a.draft[it].y - b.draft[it].y) < SNAP_SLACK_M } &&
        a.bends.indices.all { a.bends[it].first == b.bends[it].first && a.bends[it].second == b.bends[it].second }

    private fun restore(s: Snap) {
        map.draft.clear(); map.draft.addAll(s.draft.map { PointF(it.x, it.y) })
        map.bends.clear(); map.bends.addAll(s.bends.map { it.first to PointF(it.second.x, it.second.y) })
    }

    private fun commit(before: Snap) {
        val after = snap()
        if (!same(before, after)) steps.add(Step(before, after))
    }

    /** Urungkan: the last drag or delete goes back when nothing was placed after it; false to take back the last stop. */
    fun undo(): Boolean {
        if (map.draft.isEmpty()) steps.clear()
        val top = steps.lastOrNull() ?: return false
        if (dragging || !same(snap(), top.after)) return false
        steps.removeAt(steps.lastIndex)
        restore(top.before)
        return true
    }

    // ---------------------------------------------------------------- the route as drawn

    /** The route's legs as drawn: along the roads found, else straight through the bends. */
    private fun legs(): List<List<PointF>> {
        val d = map.draft
        val n = d.size
        if (n < 2) return emptyList()
        val straight = (0 until n - 1).map { k -> listOf(d[k]) + map.bends.filter { it.first == k }.map { it.second } + d[k + 1] }
        val shape = map.draftShape ?: return straight
        if (shape.size < 2) return straight
        // each stop sits on the road path: split it at the vertex nearest to each, in order
        val at = IntArray(n)
        var from = 0
        for (s in 1 until n - 1) {
            var best = Float.MAX_VALUE
            for (m in from until shape.size) {
                val dd = hypot(shape[m].x - d[s].x, shape[m].y - d[s].y)
                if (dd < best) { best = dd; at[s] = m }
            }
            from = at[s]
        }
        at[n - 1] = shape.lastIndex
        return (0 until n - 1).map { k -> if (at[k + 1] > at[k]) shape.subList(at[k], at[k + 1] + 1) else straight[k] }
    }

    /** The stop whose handle is under a screen point, -1 for none: the last stop first, then the nearest. */
    private fun handleAt(x: Float, y: Float): Int {
        val reach = HANDLE_DP * density
        val last = map.draft.lastIndex
        var best = reach
        var hit = -1
        map.draft.forEachIndexed { i, p ->
            val s = map.screenOf(p)
            val dd = hypot(s.x - x, s.y - y)
            if (dd <= reach && i == last) return i
            if (dd < best) { best = dd; hit = i }
        }
        return hit
    }

    /** The route's path under a screen point, between its stops (T-3366). */
    private fun pathAt(x: Float, y: Float): Hit? {
        var best = PATH_DP * density
        var hit: Hit? = null
        legs().forEachIndexed { k, leg ->
            for (j in 0 until leg.size - 1) {
                val a = map.screenOf(leg[j]); val b = map.screenOf(leg[j + 1])
                val dx = b.x - a.x; val dy = b.y - a.y
                val len = dx * dx + dy * dy
                val t = if (len > 0f) (((x - a.x) * dx + (y - a.y) * dy) / len).coerceIn(0f, 1f) else 0f
                val dd = hypot(a.x + t * dx - x, a.y + t * dy - y)
                if (dd < best) { best = dd; hit = Hit(k, j, map.worldAt(a.x + t * dx, a.y + t * dy)) }
            }
        }
        return hit
    }

    // ---------------------------------------------------------------- edits

    /** A new stop on leg [h]'s path; its index. The leg's bends before it stay on its first half. */
    private fun insert(h: Hit, leg: List<PointF>): Int {
        val k = h.leg
        map.draft.add(k + 1, h.at)
        val moved = map.bends.map { (l, p) ->
            when {
                l > k -> l + 1 to p
                l == k -> {
                    val near = leg.indices.minByOrNull { hypot(leg[it].x - p.x, leg[it].y - p.y) } ?: 0
                    (if (near <= h.seg) k else k + 1) to p
                }
                else -> l to p
            }
        }
        map.bends.clear(); map.bends.addAll(moved)
        return k + 1
    }

    /** Delete stop [i]: the stops either side are joined by one leg (searched again by [changed]). */
    private fun delete(i: Int) {
        val n = map.draft.size
        if (i !in 0 until n) return
        val before = snap()
        val kept = map.bends.mapNotNull { (l, p) ->
            when {
                n == 1 -> null
                i == 0 -> if (l == 0) null else l - 1 to p
                i == n - 1 -> if (l >= n - 2) null else l to p
                l >= i -> l - 1 to p
                else -> l to p
            }
        }
        map.bends.clear(); map.bends.addAll(kept)
        map.draft.removeAt(i)
        commit(before)
        changed()
    }

    /** A tap on stop [i] (T-3109): the last finishes, another is deleted. */
    private fun tap(i: Int) {
        if (map.draft.size < 2) return
        if (i == map.draft.lastIndex) finish() else delete(i)
    }

    /** Stop [i] held (T-1517, a right click's work): the last stop, or a lone one, is deleted. */
    private fun held() {
        val p = press ?: return
        if (dragging) return
        p.held = true
        if (p.i < 0) {
            // held on the route between stops: a bend, as a hold anywhere else
            p.ins?.let { map.onBend?.invoke(it.at) }
            return
        }
        val n = map.draft.size
        if (p.i == n - 1 || n == 1) delete(p.i)
        else say("Putaran rute bus belum tersedia di sini. Ketuk halte untuk menghapusnya, atau tahan halte terakhir.")
    }

    // ---------------------------------------------------------------- the finger

    /** True when the event is the route's: the map does not pan or tap with it. */
    fun onTouch(e: MotionEvent): Boolean {
        if (!active()) { if (press != null) cancel(); return false }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (map.draft.isEmpty()) steps.clear()
                val i = handleAt(e.x, e.y)
                val ins = if (i < 0) pathAt(e.x, e.y) else null
                if (i < 0 && ins == null) return false
                press = Press(e.x, e.y, i, ins, snap())
                map.postDelayed(hold, HOLD_MS)
                map.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = press ?: return false
                if (p.held) return true
                if (!dragging) {
                    if (hypot(e.x - p.x, e.y - p.y) < ViewConfiguration.get(map.context).scaledTouchSlop) return true
                    map.removeCallbacks(hold)
                    if (!startDrag(p)) { press = null; return true }
                }
                map.draft[p.i] = map.worldAt(e.x, e.y)
                drawDrag(p)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = press ?: return false
                map.removeCallbacks(hold)
                press = null
                when {
                    dragging -> drop(p)
                    p.held -> Unit
                    p.i >= 0 -> tap(p.i)
                    // a tap on the route between stops places the next stop there, as before
                    else -> map.onLocation?.invoke(map.worldAt(e.x, e.y))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (press == null) return false; cancel(); return true }
            // a second finger pinches the map: the drag is dropped, the stop back where it was
            MotionEvent.ACTION_POINTER_DOWN -> { if (press != null) cancel(); return false }
        }
        return press != null
    }

    private fun startDrag(p: Press): Boolean {
        var legs = legs()
        p.ins?.let { h ->
            if (map.draft.size >= MAX_STOPS) { say("Batas $MAX_STOPS titik tercapai. Hapus halte lain dulu."); return false }
            p.i = insert(h, legs[h.leg])
            legs = legs.subList(0, h.leg) + listOf(emptyList(), emptyList()) + legs.subList(h.leg + 1, legs.size)
        }
        p.legs = legs
        dragging = true
        map.junctionMarks = emptyList()
        return true
    }

    /** The route while stop [p.i] is dragged: its legs straight through their bends, the rest as found. */
    private fun drawDrag(p: Press) {
        val d = map.draft
        val i = p.i
        val shape = ArrayList<PointF>()
        for (k in 0 until d.size - 1) {
            val leg = if (k == i - 1 || k == i || k !in p.legs.indices || p.legs[k].size < 2)
                listOf(d[k]) + map.bends.filter { it.first == k }.map { it.second } + d[k + 1]
            else p.legs[k]
            shape.addAll(if (shape.isEmpty()) leg else leg.drop(1))
        }
        map.draftShape = if (shape.size >= 2) shape else null
        map.invalidate()
    }

    private fun drop(p: Press) {
        dragging = false
        val d = map.draft
        val at = d[p.i]
        val close = listOfNotNull(d.getOrNull(p.i - 1), d.getOrNull(p.i + 1)).any { hypot(it.x - at.x, it.y - at.y) < MIN_GAP_M }
        if (close) {
            restore(p.before)
            changed()
            say("Halte harus berjarak setidaknya ${MIN_GAP_M.toInt()} m dari halte di sebelahnya.")
            return
        }
        commit(p.before)
        changed()
    }

    private fun cancel() {
        map.removeCallbacks(hold)
        val p = press
        press = null
        if (p != null && dragging) {
            dragging = false
            restore(p.before)
            changed()
        }
        dragging = false
    }

    private companion object {
        /** a finger's reach to a handle (a 48 dp target) and to the route's path, dp */
        const val HANDLE_DP = 24f
        const val PATH_DP = 14f
        /** a finger held still on a handle this long deletes the stop (web LONG_PRESS_MS) */
        const val HOLD_MS = 500L
        const val MIN_GAP_M = 350f
        const val MAX_STOPS = 24
        /** how far the route search may move a stop onto its road and leave it the same draft, m */
        const val SNAP_SLACK_M = 200f
    }
}
