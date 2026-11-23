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
    external fun play(id: Long, ms: Long, rate: Double): Boolean
    external fun pause(id: Long, ms: Long): Boolean
    external fun state(id: Long, ms: Long): DoubleArray?
    external fun snapshot(id: Long, ms: Long): ByteArray?
    external fun close(id: Long)
}

internal class NativeSimulationClock(
    private val onClock: (Double) -> Unit,
    private val onFailure: (String) -> Unit,
) : SimulationClock {
    private val main = Handler(Looper.getMainLooper())
    private var id = 0L
    private var playing = false
    override val isReady get() = id != 0L
    override var onCash: ((Double) -> Unit)? = null
    override var onTrainLoads: ((Long, JSONArray) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val state = SimulationEngine.state(id, SystemClock.elapsedRealtime())
            if (state == null) {
                close()
                onFailure("Jam simulasi tidak tersedia.")
                return
            }
            onCash?.invoke(state[1])
            onClock(state[0])
            if (playing) main.postDelayed(this, 500)
        }
    }

    override fun open(snapshot: ByteArray, ready: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper() && id == 0L)
        id = SimulationEngine.open(snapshot, SystemClock.elapsedRealtime())
        if (isReady) ready() else onFailure("Simpanan simulasi tidak dapat dibuka.")
    }
    override fun play(rate: Double) {
        if (!SimulationEngine.play(id, SystemClock.elapsedRealtime(), rate)) {
            close()
            onFailure("Laju simulasi tidak valid.")
            return
        }
        playing = true
        main.removeCallbacks(tick)
        tick.run()
    }
    override fun snapshot(done: (Result<ByteArray>) -> Unit) {
        playing = false
        main.removeCallbacks(tick)
        val ms = SystemClock.elapsedRealtime()
        done(runCatching {
            check(SimulationEngine.pause(id, ms)) { "Simulasi belum siap disimpan." }
            checkNotNull(SimulationEngine.snapshot(id, ms)) { "Simpanan simulasi tidak tersedia." }
        })
    }
    override fun close() {
        playing = false
        main.removeCallbacks(tick)
        if (id != 0L) SimulationEngine.close(id)
        id = 0L
    }
}
