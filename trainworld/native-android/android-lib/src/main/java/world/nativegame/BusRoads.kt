// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout

/**
 * The roads a bus route may use, chosen from Alat while drawing a bus (Android only: the owner asked
 * for it). The draft's preview and the build route with the same choice (`bus_plan::roads` in Rust),
 * so the built route is the one previewed; the choice is remembered for the next bus. Not in the
 * save: the built route keeps the roads it was laid on, and the web reads the save unchanged.
 */
internal object BusRoads {
    /** 0 every road (web busRoad), 1 the main roads only (web railRoad), 2 no motorway. */
    @JvmStatic private external fun set(roads: Int)

    private val labels = listOf("Semua jalan", "Jalan besar saja", "Tanpa tol")
    private val hints = listOf(
        "Jalan apa pun yang bisa dilalui bus, termasuk tol.",
        "Hanya jalan utama sampai jalan tersier, serta tol. Jalan kecil tidak dipakai.",
        "Semua jalan kecuali tol.",
    )
    private const val PREFS = "world-native-bus"
    private const val KEY = "roads"

    fun current(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).takeIf { it in labels.indices } ?: 0

    fun label(context: Context) = labels[current(context)]

    /** Hands the remembered choice to the route planner; call before each preview. */
    fun apply(context: Context) { runCatching { set(current(context)) } }

    /** The draft's title after "≈ n km", e.g. "lewat jalan besar". */
    fun via(context: Context) = when (current(context)) {
        1 -> "lewat jalan besar"
        2 -> "lewat jalan tanpa tol"
        else -> "lewat jalan"
    }

    /** The Alat entry: "Jenis jalan · Semua jalan". */
    fun menuLabel(context: Context) = "Jenis jalan · ${label(context)}"

    /** Picks the roads; [changed] re-routes the draft when the choice is new. */
    fun choose(activity: Activity, changed: () -> Unit) {
        val selected = current(activity)
        StudioUi.sheet(activity, "Jenis jalan untuk bus", .6f) { column, dialog ->
            labels.forEachIndexed { i, label ->
                val mark = if (i == selected) StudioUi.chip(activity, "Dipakai", StudioUi.TINT_GREEN, StudioUi.GOOD) else null
                column.addView(StudioUi.row(activity, null, StudioUi.TINT_GREY, label, hints[i], mark) {
                    dialog.dismiss()
                    if (i == selected) return@row
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, i).apply()
                    apply(activity)
                    changed()
                }.apply { if (i == selected) contentDescription = "$label, dipakai" },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = StudioUi.dp(activity, 6) })
            }
        }
    }
}
