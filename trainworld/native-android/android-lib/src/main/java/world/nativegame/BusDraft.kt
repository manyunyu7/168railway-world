// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.graphics.PointF
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/**
 * Web busDraw.ts (T-1464) while drawing a bus: after each placed point the route to the next is
 * laid along the roads by the same planner `Engine.build` uses, on a copy of the world, and drawn
 * as the draft; each stop moves onto its road and shows its automatic name. Without road data
 * (offline) the draft stays straight lines with a note.
 */
internal object BusDraft {
    /** via: x, y, kind (1 a stop, 0 a bend) per point. {path, km, stops: [[x, y, name]], snapped} or {error}. */
    @JvmStatic external fun preview(handle: Long, via: DoubleArray): String
    /** The loaded cities' places (x, y per place; names one per line), for naming bus stops. */
    @JvmStatic private external fun places(xy: DoubleArray, names: String)

    fun places(layers: List<CitySurface.Layer>) {
        val all = layers.flatMap { it.places }
        runCatching {
            places(DoubleArray(all.size * 2) { i -> all[i / 2].let { if (i % 2 == 0) it.x else it.y }.toDouble() },
                all.joinToString("\n") { it.name.replace('\n', ' ') })
        }
    }

    /**
     * Route the draft in the background. [live] says whether this version is still the one drawn;
     * [fork] opens a copy of the world for the worker (closed here). Results land on the map.
     */
    fun route(
        activity: Activity, worker: Executor, map: NetworkView, status: TextView, title: TextView,
        specification: String, directory: File, fork: () -> Long, live: () -> Boolean,
    ) {
        val points = map.draft.map { PointF(it.x, it.y) }
        val bends = map.bends.toList()
        if (points.size < 2) return
        BusRoads.apply(activity)
        val via = points.flatMapIndexed { i, p -> listOf(p to 1.0) + bends.filter { it.first == i }.map { it.second to 0.0 } }
        val triples = via.flatMap { (p, kind) -> listOf(p.x.toDouble(), p.y.toDouble(), kind) }.toDoubleArray()
        val coords = via.flatMap { (p, _) -> listOf(p.x.toDouble(), p.y.toDouble()) }.toDoubleArray()
        map.postDelayed({
            if (!live()) return@postDelayed
            val candidate = fork()
            if (candidate == 0L) return@postDelayed
            worker.execute {
                if (!live()) { Engine.close(candidate); return@execute }
                val result = try {
                    val need = JSONObject(Engine.requiredRoads(candidate, coords))
                    check(!need.has("error")) { need.getString("error") }
                    val tiles = need.optJSONArray("tiles")
                    if (tiles != null && (0 until tiles.length()).any { i ->
                            val t = tiles.getJSONArray(i); !File(directory, "14-${t.getInt(0)}-${t.getInt(1)}.pbf").isFile }) {
                        activity.runOnUiThread { if (live()) status.text = "Memuat jalan…" }
                    }
                    try {
                        RoadTileStore.prepare(directory, need.toString())
                    } catch (e: Exception) {
                        throw IllegalStateException("Data jalan belum bisa dimuat (${e.localizedMessage ?: "offline"}). Pratinjau memakai garis lurus.")
                    }
                    JSONObject(preview(candidate, triples))
                } catch (e: Exception) {
                    JSONObject().put("error", e.localizedMessage ?: "Rute jalan belum dapat dihitung.")
                } finally { Engine.close(candidate) }
                activity.runOnUiThread {
                    if (!live() || map.draftTouch?.dragging == true) return@runOnUiThread
                    if (result.has("error")) {
                        map.draftShape = null; map.junctionMarks = emptyList()
                        status.text = result.getString("error")
                    } else show(result, map, points, status, title, specification)
                    map.invalidate()
                }
            }
        }, 120)
    }

    private fun show(result: JSONObject, map: NetworkView, points: List<PointF>, status: TextView, title: TextView, specification: String) {
        val xs = result.getJSONArray("path")
        map.draftShape = (0 until xs.length() - 1 step 2).map { PointF(xs.getDouble(it).toFloat(), xs.getDouble(it + 1).toFloat()) }
        val stops = result.getJSONArray("stops")
        val labels = mutableListOf<Pair<PointF, String>>()
        if (stops.length() == points.size && map.draft.size == points.size) {
            for (i in 0 until stops.length()) {
                val row = stops.getJSONArray(i)
                val at = PointF(row.getDouble(0).toFloat(), row.getDouble(1).toFloat())
                // The stop sits on its road unless that brings it under 350 m of a neighbour.
                val apart = listOfNotNull(map.draft.getOrNull(i - 1), map.draft.getOrNull(i + 1))
                    .all { kotlin.math.hypot(it.x - at.x, it.y - at.y) >= 350f }
                if (apart && map.draft[i] == points[i]) map.draft[i] = at
                row.optString(2).takeIf { it.isNotBlank() }?.let { labels.add(map.draft[i] to it) }
            }
        }
        map.junctionMarks = labels
        val km = String.format(java.util.Locale.forLanguageTag("id-ID"), "%.1f", result.getDouble("km"))
        title.text = "$specification · ≈ $km km ${BusRoads.via(title.context)}"
        status.text = "${points.size} halte · Ketuk tujuan berikutnya. Rute mengikuti jalan."
    }
}
