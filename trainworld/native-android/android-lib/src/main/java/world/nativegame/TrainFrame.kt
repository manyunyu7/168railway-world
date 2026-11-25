// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** `Engine.trainScene` written into one reused array: the map asks for it every display frame. */
internal object TrainScene {
    init { System.loadLibrary("world_native") }
    /** The scene's length; copied into [out] only when it fits, -1 without a scene (train_scene.rs). */
    @JvmStatic external fun fill(handle: Long, time: Double, x: Float, y: Float, halfWidth: Float, halfHeight: Float, detail: Int, out: FloatArray): Int
}

/**
 * The game time the trains are drawn at. The running clock reports only every half second and its
 * report reaches the UI thread late by however long the worker took, so running on from the report
 * jerked the trains at every report. The runtime's anchor ([SimulationClock.timeAt]) is the exact
 * time the simulation settles at any wall instant; this follows it every frame, never steps back,
 * and eases a small correction (a hand-over, a new speed) over a quarter second instead of a jump.
 */
internal class TrainClock {
    private var shown = Double.NaN
    private var shownAt = 0L
    fun reset() { shown = Double.NaN }
    fun at(now: Long, target: Double, rate: Double): Double {
        val previous = shown
        // A clock report between frames asks at a wall time past the coming frame's vsync time.
        if (previous.isFinite() && now < shownAt && rate > 0.0) return previous
        val dt = (now - shownAt).coerceIn(0L, 100L) / 1000.0
        shownAt = now
        // Paused, the first frame, or a real jump (seek, new runtime far off): show it as it is.
        if (rate <= 0.0 || !previous.isFinite() || abs(target - previous) > max(2.0 * rate, 5.0)) {
            shown = target
            return target
        }
        var next = previous + rate * dt
        next += (target - next) * min(1.0, dt / .25)
        shown = max(previous, next)
        return shown
    }
}

/**
 * Temporary frame timing for the stutter hunt: every 5 s, logcat tag `WorldFrame` gets the frames
 * drawn, mean and worst map draw, mean and worst train scene, and every static map re-render.
 * `adb logcat -s WorldFrame` while recording gfxinfo shows which part of a slow frame was ours.
 */
internal object FrameLog {
    private var since = 0L
    private var frames = 0
    private var drawNs = 0L; private var drawMax = 0L
    private var sceneNs = 0L; private var sceneMax = 0L
    private var staticCount = 0; private var staticMax = 0L
    private var groundCount = 0; private var groundMax = 0L
    fun ground(ns: Long) { groundCount++; groundMax = max(groundMax, ns) }
    fun scene(ns: Long) { sceneNs += ns; sceneMax = max(sceneMax, ns) }
    fun static(ns: Long) { staticCount++; staticMax = max(staticMax, ns) }
    fun frame(ns: Long) {
        frames++; drawNs += ns; drawMax = max(drawMax, ns)
        val now = SystemClock.elapsedRealtime()
        if (since == 0L) since = now
        if (now - since < 5000) return
        Log.w("WorldFrame", "frames=$frames draw avg=%.2f max=%.1f ms · scene avg=%.2f max=%.1f ms · static renders=%d max=%.0f ms · ground (worker) %d max=%.0f ms".format(
            java.util.Locale.ROOT, drawNs / 1e6 / frames, drawMax / 1e6, sceneNs / 1e6 / frames, sceneMax / 1e6, staticCount, staticMax / 1e6, groundCount, groundMax / 1e6))
        since = now; frames = 0; drawNs = 0; drawMax = 0; sceneNs = 0; sceneMax = 0; staticCount = 0; staticMax = 0; groundCount = 0; groundMax = 0
    }
}
