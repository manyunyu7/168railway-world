// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray

internal interface SimulationClock {
    val isReady: Boolean
    var onCash: ((Double) -> Unit)?
    var onTrainLoads: ((Long, JSONArray) -> Unit)?
    fun open(snapshot: ByteArray, ready: () -> Unit)
    fun play(rate: Double)
    /** Game time at wall `ms` (elapsedRealtime) as last reported, or null before the first tick. */
    fun timeAt(ms: Long): Double?
    /** Hot swap: move this (paused, just opened) runtime on to game `time` at wall `ms`, settling the gap once. */
    fun catchUp(time: Double, ms: Long)
    fun snapshot(pause: Boolean, done: (Result<ByteArray>) -> Unit)
    /** Riders aboard one train (train_riders.rs `train`); `done(null)` before they are in. */
    fun trainRiders(profile: Int, dep: Double, time: Double, seed: Long, done: (String?) -> Unit)
    /** Boardings and purposes at a station complex (train_riders.rs `station`). */
    fun stationRiders(node: Int, time: Double, done: (String?) -> Unit)
    /** A line's stop rows: boardings, alightings, loads and where riders go (train_riders.rs `line`). */
    fun lineRiders(line: Int, time: Double, done: (String?) -> Unit)
    /** Every line's riders, money and vehicles as web LinesTab shows them (line_summary.rs). */
    fun lineSummary(time: Double, done: (String?) -> Unit)
    /** The crowding report: crowding dots, past day's full trains, crowded stretches (crowding.rs). */
    fun crowding(time: Double, done: (String?) -> Unit)
    /** Riders off, on and the faces' mood per arrival (station_life.rs); `done(null)` before they are in. */
    fun arrivalRiders(time: Double, ask: FloatArray, done: (FloatArray?) -> Unit)
    fun close()
}

internal object SimulationEngine {
    init { System.loadLibrary("world_native") }
    external fun supportsEditor(editor: Long): Boolean
    external fun open(snapshot: ByteArray, ms: Long): Long
    external fun openNetwork(snapshot: ByteArray, manifest: String, ms: Long): Long
    /** How far the openNetwork running now has come, 0..1 (its forecast's rounds done); callable from any thread. */
    external fun openProgress(): Float
    external fun loads(id: Long, time: Double): IntArray?
    external fun play(id: Long, ms: Long, rate: Double): Boolean
    external fun pause(id: Long, ms: Long): Boolean
    external fun catchUp(id: Long, ms: Long, time: Double): Boolean
    external fun state(id: Long, ms: Long): DoubleArray?
    external fun snapshot(id: Long, ms: Long): ByteArray?
    external fun trainRiders(id: Long, profile: Int, dep: Double, time: Double, seed: Long): String?
    external fun stationRiders(id: Long, node: Int, time: Double): String?
    external fun lineRiders(id: Long, line: Int, time: Double): String?
    external fun lineSummary(id: Long, time: Double): String?
    external fun crowding(id: Long, time: Double): String?
    external fun arrivalRiders(id: Long, time: Double, ask: FloatArray): FloatArray?
    external fun close(id: Long)
}

internal class NativeSimulationClock(
    private val manifest: ((ByteArray) -> String)?,
    private val onClock: (Double) -> Unit,
    private val onFailure: (String) -> Unit,
) : SimulationClock {
    private val main = Handler(Looper.getMainLooper())
    // Each Rust session owns thread-local TrackApi state. Open, tick, snapshot and close
    // all use this executor, including when the activity is paused during loading.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var id = 0L
    @Volatile private var closed = false
    private var playing = false
    override var isReady = false
        private set
    override var onCash: ((Double) -> Unit)? = null
    override var onTrainLoads: ((Long, JSONArray) -> Unit)? = null
    // Main thread: the game time at a wall instant and the rate since, as Rust settles it.
    private var anchorTime = Double.NaN
    private var anchorMs = 0L
    private var rate = 0.0
    override fun timeAt(ms: Long): Double? =
        anchorTime.takeIf { it.isFinite() }?.let { it + (ms - anchorMs).coerceAtLeast(0L) * rate / 1000.0 }
    private fun anchor(time: Double, ms: Long) { if (ms >= anchorMs) { anchorTime = time; anchorMs = ms } }
    private fun submit(action: () -> Unit) {
        if (closed) return
        worker.execute {
            if (!closed) try { action() } catch (e: Exception) {
                main.post { if (!closed) onFailure(e.localizedMessage ?: "Simulasi gagal dimuat.") }
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!playing || closed) return
            submit {
                val at = SystemClock.elapsedRealtime()
                val s = checkNotNull(SimulationEngine.state(id,at))
                val rows = SimulationEngine.loads(id,s[0])
                main.post {
                    if (playing && !closed) {
                        anchor(s[0], at)
                        onCash?.invoke(s[1])
                        if (rows != null) onTrainLoads?.invoke(kotlin.math.floor(s[0]/3600).toLong()*3600,JSONArray(rows))
                        onClock(s[0])
                        if (playing && !closed) main.postDelayed(this,500)
                    }
                }
            }
        }
    }
    override fun open(snapshot: ByteArray, ready: () -> Unit) {
        submit {
            val spec=manifest?.invoke(snapshot)
            if (closed) return@submit
            val now=SystemClock.elapsedRealtime()
            id=if(spec==null) SimulationEngine.open(snapshot,now) else SimulationEngine.openNetwork(snapshot,spec,now)
            check(id!=0L) { "Simpanan simulasi tidak dapat dibuka." }
            main.post { if (!closed) { isReady=true; ready() } }
        }
    }
    override fun play(rate: Double) {
        val alreadyPlaying = playing
        playing=true
        val now=SystemClock.elapsedRealtime()
        timeAt(now)?.let { anchor(it, now) }
        this.rate = rate
        submit { check(SimulationEngine.play(id,now,rate)) { "Laju simulasi tidak valid." } }
        if (!alreadyPlaying) tick.run()
    }
    override fun catchUp(time: Double, ms: Long) {
        anchor(time, ms)
        submit { check(SimulationEngine.catchUp(id,ms,time)) { "Simulasi tidak dapat dilanjutkan." } }
    }
    override fun snapshot(pause: Boolean, done: (Result<ByteArray>) -> Unit) {
        val now=SystemClock.elapsedRealtime()
        if (pause) {
            timeAt(now)?.let { anchor(it, now) }
            rate=0.0
            playing=false
            main.removeCallbacks(tick)
        }
        submit {
            val result=runCatching {
                if (pause) check(SimulationEngine.pause(id,now))
                checkNotNull(SimulationEngine.snapshot(id,now)) { "Simpanan simulasi tidak tersedia." }
            }
            main.post { if (!closed) done(result) }
        }
    }
    private fun ask(read: () -> String?, done: (String?) -> Unit) {
        if (!isReady || closed) { done(null); return }
        submit {
            val text = read()?.takeIf { it != "null" }
            main.post { if (!closed) done(text) }
        }
    }
    override fun trainRiders(profile: Int, dep: Double, time: Double, seed: Long, done: (String?) -> Unit) =
        ask({ SimulationEngine.trainRiders(id, profile, dep, time, seed) }, done)
    override fun stationRiders(node: Int, time: Double, done: (String?) -> Unit) =
        ask({ SimulationEngine.stationRiders(id, node, time) }, done)
    override fun lineRiders(line: Int, time: Double, done: (String?) -> Unit) =
        ask({ SimulationEngine.lineRiders(id, line, time) }, done)
    override fun lineSummary(time: Double, done: (String?) -> Unit) =
        ask({ SimulationEngine.lineSummary(id, time) }, done)
    override fun crowding(time: Double, done: (String?) -> Unit) =
        ask({ SimulationEngine.crowding(id, time) }, done)
    override fun arrivalRiders(time: Double, ask: FloatArray, done: (FloatArray?) -> Unit) {
        if (!isReady || closed) { done(null); return }
        submit {
            val got = SimulationEngine.arrivalRiders(id, time, ask)
            main.post { if (!closed) done(got) }
        }
    }
    override fun close() {
        if (closed) return
        closed=true
        isReady=false
        playing=false
        main.removeCallbacks(tick)
        worker.execute { if(id!=0L) { SimulationEngine.close(id);id=0L } }
        worker.shutdown()
    }
}
