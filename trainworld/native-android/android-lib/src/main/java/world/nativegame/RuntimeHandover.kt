// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.os.SystemClock

/**
 * Hot swap after an edit, as the web keeps its clock and trains running while demand is worked
 * out again (clock.ts T-1665, capped at DEMAND_CAP 4×).
 *
 * The runtime that was playing is checkpointed into the editor world (the edit's clock T1), then
 * keeps playing as a "ghost": trains, clock, loads and riders as they were, its money shown with
 * the edits' cost added. Its ledger after T1 is never saved. The edited world warms on its own
 * runtime meanwhile; when ready, that runtime catches up to the ghost's game time T2 (Rust
 * `Session::catch_up` settles T1→T2 once, with the edited network and riders) and the ghost is
 * closed. A ghost stopped early (pause, building, leaving) leaves T2 as [catchUp] for the next
 * runtime, so the clock never runs backwards.
 */
internal class RuntimeHandover {
    var ghost: SimulationClock? = null
        private set
    /** Game time a stopped ghost reached; the next runtime that plays catches up to it. */
    var catchUp: Double? = null
        private set
    // The ghost's cash at its checkpoint (NaN: take its first report) and the editor's since.
    private var baseCash = Double.NaN
    private var editorCash = Double.NaN
    var overlayComputing = false
    /** The clock runs at most 4× while riders are being worked out (web DEMAND_CAP). */
    val capped get() = overlayComputing || ghost != null

    /**
     * [runtime] (paused at the editor's clock, or playing and just checkpointed into it) goes on
     * playing with its old riders. [checkpoint] is its cash at that clock, NaN for its first report;
     * [editor] the editor's balance now.
     */
    fun adopt(runtime: SimulationClock, checkpoint: Double, editor: Double) {
        ghost?.close()
        ghost = runtime
        catchUp = null
        baseCash = checkpoint
        editorCash = editor
    }

    /** The editor's balance after the edits made while the ghost runs. */
    fun edited(cash: Double) { if (ghost != null) editorCash = cash }

    /** The balance to show for the ghost's [cash]: its earnings plus what the edits since spent. */
    fun shown(cash: Double): Double {
        if (baseCash.isNaN()) baseCash = cash
        val offset = editorCash - baseCash
        return if (offset.isFinite()) cash + offset else cash
    }

    /** Stops the ghost; returns the game time it reached (kept as [catchUp]) or null without one. */
    fun stop(): Double? {
        val runtime = ghost ?: return null
        ghost = null
        runtime.timeAt(SystemClock.elapsedRealtime())?.let { t -> catchUp = maxOf(catchUp ?: t, t) }
        runtime.close()
        return catchUp
    }

    /** [next] (opened, paused) takes over at the ghost's game time, or at [catchUp]. */
    fun handOver(next: SimulationClock) {
        val now = SystemClock.elapsedRealtime()
        val runtime = ghost
        val target = listOfNotNull(runtime?.timeAt(now), catchUp).maxOrNull()
        ghost = null
        catchUp = null
        runtime?.close()
        if (target != null) next.catchUp(target, now)
    }

    /** The editor world was replaced (undo, leaving): nothing to catch up to. */
    fun forget() {
        ghost?.close()
        ghost = null
        catchUp = null
    }
}
