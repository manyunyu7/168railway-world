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
    fun snapshot(done: (Result<ByteArray>) -> Unit)
    fun close()
}

internal object SimulationEngine {
    init { System.loadLibrary("world_native") }
    external fun supportsEditor(editor: Long): Boolean
    external fun open(snapshot: ByteArray, ms: Long): Long
    external fun openNetwork(snapshot: ByteArray, manifest: String, ms: Long): Long
    external fun loads(id: Long, time: Double): IntArray?
    external fun play(id: Long, ms: Long, rate: Double): Boolean
    external fun pause(id: Long, ms: Long): Boolean
    external fun state(id: Long, ms: Long): DoubleArray?
    external fun snapshot(id: Long, ms: Long): ByteArray?
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
                val s = checkNotNull(SimulationEngine.state(id,SystemClock.elapsedRealtime()))
                val rows = SimulationEngine.loads(id,s[0])
                main.post {
                    if (playing && !closed) {
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
        playing=true
        val now=SystemClock.elapsedRealtime()
        submit { check(SimulationEngine.play(id,now,rate)) { "Laju simulasi tidak valid." } }
        main.removeCallbacks(tick)
        tick.run()
    }
    override fun snapshot(done: (Result<ByteArray>) -> Unit) {
        playing=false
        main.removeCallbacks(tick)
        val now=SystemClock.elapsedRealtime()
        submit {
            val result=runCatching {
                check(SimulationEngine.pause(id,now))
                checkNotNull(SimulationEngine.snapshot(id,now)) { "Simpanan simulasi tidak tersedia." }
            }
            main.post { if (!closed) done(result) }
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
