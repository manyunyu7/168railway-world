// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.graphics.PointF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The game's sounds, as the web plays them (map/buildSound.ts, map/stationSound.ts), under the
 * Sound switch and volume of Tampilan peta (web `display.sound` / `soundVolume`, on and 60%):
 *  - the four recordings of web public/sounds (assets/world-native/sounds) through a SoundPool:
 *    a snap when track is placed or a blueprint saved (three semitones lower for a station),
 *    a quiet pop for a panel change to the world, a knock for a refused action, and the construct
 *    sound when something is built for money (a blueprint, an upgrade, a passage);
 *  - synthesised through one AudioTrack: the two-note chime after a load over 30 s, and at
 *    stations up close one soft note per arriving train (the line's own) and the riders' pops.
 * Media volume rules them; a phone set to silent or vibrate hears none. No haptics.
 */
internal object WorldSound {
    @JvmStatic external fun arrivals(handle: Long, prev: Double, now: Double, x: Float, y: Float, hw: Float, hh: Float): FloatArray?

    enum class Kind { SNAP, STATION, POP, KNOCK, CONSTRUCT, CHIME }

    // ---- settings ----------------------------------------------------------------------------
    private var app: Context? = null
    private var foreground = false
    private fun prefs() = app?.getSharedPreferences("studio-display", Context.MODE_PRIVATE)
    /** The volume to play at, or 0: sound off, the phone silent, or the game in the background. */
    private fun volume(): Float {
        val c = app ?: return 0f
        if (!foreground) return 0f
        val p = prefs() ?: return 0f
        if (!p.getBoolean("sound", true)) return 0f
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0f
        return p.getFloat("soundVolume", .6f).coerceIn(0f, 1f)
    }

    /** The activity is in front: volume keys set the media volume, the samples load once. */
    fun resume(activity: Activity) {
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        app = activity.applicationContext
        foreground = true
        if (pool == null) loadSamples(activity.applicationContext)
        pool?.autoResume()
    }

    /** Silence at once when the game leaves the screen. */
    fun pause() {
        foreground = false
        pool?.autoPause()
        Mixer.stop()
        prevTime = Double.NaN
    }

    // ---- recordings (web samples.ts) ---------------------------------------------------------
    private val FILES = arrayOf("snap", "pop", "knock", "construct")
    /** Web PEAK: the loudest sample of each at volume 1, as a fraction of full scale. */
    private val PEAK = floatArrayOf(.21f, .09f, .12f, .15f)
    private var pool: SoundPool? = null
    private val ids = IntArray(4)
    private val filePeak = FloatArray(4) { 1f }
    private val ready = BooleanArray(4)

    private fun loadSamples(c: Context) {
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        p.setOnLoadCompleteListener { _, id, status -> val i = ids.indexOf(id); if (i >= 0 && status == 0) ready[i] = true }
        FILES.forEachIndexed { i, name ->
            runCatching {
                val path = "world-native/sounds/$name.wav"
                filePeak[i] = c.assets.open(path).use { wavPeak(it.readBytes()) }
                c.assets.openFd(path).use { ids[i] = p.load(it, 1) }
            }
        }
    }

    /** The loudest sample of a 16-bit PCM WAV, as a fraction of full scale (web decodeSamples). */
    private fun wavPeak(b: ByteArray): Float {
        var i = 12
        while (i + 8 <= b.size) {
            val size = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
            if (b[i] == 'd'.code.toByte() && b[i + 1] == 'a'.code.toByte() && b[i + 2] == 't'.code.toByte() && b[i + 3] == 'a'.code.toByte()) {
                var peak = 0
                var j = i + 8
                val end = min(b.size - 1, i + 8 + size)
                while (j < end) { peak = max(peak, kotlin.math.abs(((b[j].toInt() and 255) or (b[j + 1].toInt() shl 8)).toShort().toInt())); j += 2 }
                return if (peak > 0) peak / 32768f else 1f
            }
            i += 8 + size + (size and 1)
        }
        return 1f
    }

    /** Web VARIANTS (snap, pop) and CONSTRUCT_VARIANTS: a little pitch and level, never the same twice running. */
    private val VARIANTS = arrayOf(floatArrayOf(.94f, .95f), floatArrayOf(.98f, 1f), floatArrayOf(1.02f, .85f), floatArrayOf(1.06f, .9f))
    private val CONSTRUCT_VARIANTS = arrayOf(floatArrayOf(.96f, .95f), floatArrayOf(1f, 1f), floatArrayOf(1.04f, .9f))
    private val lastVariant = IntArray(4) { -1 }
    private fun pickVariant(file: Int): FloatArray {
        val set = if (file == 3) CONSTRUCT_VARIANTS else VARIANTS
        val prev = lastVariant[file]
        var v = (Math.random() * (if (prev < 0) set.size else set.size - 1)).toInt()
        if (prev >= 0 && v >= prev) v++
        lastVariant[file] = v
        return set[v]
    }

    /** Web GAP_S: the same sound twice within this many seconds plays once. */
    private val GAP_MS = longArrayOf(150, 150, 50, 250, 300, 1000)
    private val last = LongArray(Kind.values().size) { Long.MIN_VALUE / 2 }
    private val STATION_RATE = 2.0.pow(-3 / 12.0).toFloat()
    /** Web MASTER: the synthesised sounds' level at volume 1. */
    private const val MASTER = .5f

    /** Web playBuild. */
    fun play(kind: Kind) {
        val vol = volume()
        if (vol <= 0f) return
        val now = SystemClock.elapsedRealtime()
        if (now - last[kind.ordinal] < GAP_MS[kind.ordinal]) return
        if (kind == Kind.CHIME) {
            last[kind.ordinal] = now
            Mixer.add(Synth.loadChime(.35f * MASTER * vol), 0f)
            return
        }
        val file = when (kind) { Kind.SNAP, Kind.STATION -> 0; Kind.POP -> 1; Kind.KNOCK -> 2; else -> 3 }
        val p = pool ?: return
        if (!ready[file]) return // still loading: silent, without using up the gap
        last[kind.ordinal] = now
        val v = if (kind == Kind.KNOCK) floatArrayOf(1f, 1f) else pickVariant(file)
        val level = (PEAK[file] * v[1] * vol / filePeak[file]).coerceIn(0f, 1f)
        val rate = (v[0] * if (kind == Kind.STATION) STATION_RATE else 1f).coerceIn(.5f, 2f)
        p.play(ids[file], level, level, 1, 0, rate)
    }

    // ---- the hooks ---------------------------------------------------------------------------
    /** A refused action: the panel's message and the knock (web `say(text, true)`). */
    fun refused(c: Context, text: String) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show()
        play(Kind.KNOCK)
    }
    fun knock() = play(Kind.KNOCK)

    /** The sound a panel's next change makes, set by the panel just before it reports the change. */
    private var cued: Kind? = null
    fun cue(kind: Kind) { cued = kind }
    /** Web afterEditSound: something built for money is construction, else the quiet pop. */
    fun cueBuilt(result: JSONObject, building: Boolean = true) =
        cue(if (building && result.optDouble("charge", 0.0) > 0) Kind.CONSTRUCT else Kind.POP)

    /** A panel changed the world (web soundOf by the edit; a rename, a loan or a hidden line is silent). */
    fun panelChange(label: String) {
        val kind = cued ?: when (label) {
            "Jadwal line", "Perhentian line", "Batas laju line", "Kelipatan tarif", "Flyover" -> Kind.POP
            "Bangun ulang rel" -> Kind.CONSTRUCT
            else -> null
        }
        cued = null
        kind?.let(::play)
    }

    /** A blueprint edit committed (BlueprintPanel): built is construction, a change of plan a pop. */
    fun blueprint(label: String) = when {
        label == "Bangun rencana" -> play(Kind.CONSTRUCT)
        label.startsWith("Ubah") -> play(Kind.POP)
        else -> {}
    }

    /** A placement from the build tools committed: track or a saved plan snaps, a station snaps lower,
     *  a new service built for money is construction. */
    fun built(tool: BuildTool, deferred: Boolean, charge: Double) = play(when {
        tool == BuildTool.STATION -> Kind.STATION
        tool == BuildTool.RAIL || deferred -> Kind.SNAP
        charge > 0 -> Kind.CONSTRUCT
        else -> Kind.POP
    })

    /** Web T-1655: a chime when a game's first load took over 30 s, once per game. */
    private val chimed = java.util.WeakHashMap<Activity, Boolean>()
    fun loaded(activity: Activity, tookMs: Long) {
        if (chimed.put(activity, true) == true) return
        if (tookMs > 30_000) play(Kind.CHIME)
    }

    // ---- stations up close (web stationLife.ts read + stationSound.ts) -----------------------
    /** Web STATION_BOX_ZOOM: platforms are drawn from here; no station sound under it. */
    private const val STATION_BOX_ZOOM = 14f
    private const val SAMPLE_MS = 125L
    private var prevTime = Double.NaN
    private var sampledAt = 0L
    private val recent = ArrayDeque<Long>()

    /** The running simulation's riders at arrivals (SimulationClock.arrivalRiders), set by the activity. */
    var riders: ((Double, FloatArray, (FloatArray?) -> Unit) -> Unit)? = null
    /** train_arrivals.rs STRIDE: x, y, line, note, profile, departure ×100, stop, node, riders' side x, y. */
    private const val STRIDE = 10
    /** An answer from the simulation later than this is dropped: the moment has passed. */
    private const val LATE_MS = 1500L

    /** Called with each train frame: the trains that pulled in at a stop in view since the last
     *  call ring their station and raise faces from its platform (web stationLife.ts `read`). */
    fun trains(map: NetworkView, rate: Double, playing: Boolean) {
        val wall = SystemClock.elapsedRealtime()
        if (wall - sampledAt < SAMPLE_MS) return
        sampledAt = wall
        val time = map.trainTime()
        val prev = prevTime
        val zoom = map.zoomLevel()
        val vol = volume()
        val faces = foreground && FaceLayer.enabled(prefs())
        if (zoom < STATION_BOX_ZOOM || !faces) FaceLayer.clear()
        val handle = map.trainEngine()
        if (!playing || (vol <= 0f && !faces) || zoom < STATION_BOX_ZOOM || handle == 0L || !time.isFinite() || map.width == 0) { prevTime = Double.NaN; return }
        prevTime = time
        if (!prev.isFinite()) return
        val w = map.width; val h = map.height
        val centre = map.worldAt(w / 2f, h / 2f)
        val corner = map.worldAt(w.toFloat(), 0f)
        val hw = kotlin.math.abs(corner.x - centre.x); val hh = kotlin.math.abs(corner.y - centre.y)
        val rows = arrivals(handle, prev, time, centre.x, centre.y, hw, hh) ?: return
        if (rows.size < STRIDE) return
        // web speed index: 60 plays everything, 240 a note and a pop or two, 960 nothing
        val speed = if (rate <= 90) 0 else if (rate <= 480) 1 else 2
        val ask = riders
        if (ask == null) { land(map, rows, null, centre.x, centre.y, speed); return }
        val n = rows.size / STRIDE
        ask(time, FloatArray(n * 3) { rows[it / 3 * STRIDE + 4 + it % 3] }) { got ->
            if (SystemClock.elapsedRealtime() - wall < LATE_MS) land(map, rows, got, centre.x, centre.y, speed)
        }
    }

    /** Web stationLife `arrive` for each arrival of [rows], its riders from [got] (off, on, mood
     *  per arrival). Without them (no rider account yet) the train's fill stands in for both. */
    private fun land(map: NetworkView, rows: FloatArray, got: FloatArray?, cx: Float, cy: Float, speed: Int) {
        val zoom = map.zoomLevel()
        if (zoom < STATION_BOX_ZOOM) return
        val vol = volume()
        val faces = foreground && FaceLayer.enabled(prefs())
        val w = max(1, map.width)
        var i = 0
        var j = 0
        while (i + STRIDE <= rows.size) {
            val x = cx + rows[i]; val y = cy + rows[i + 1]
            var off: Double; var on: Double; var mood: Int
            if (got != null && j + 3 <= got.size && !got[j].isNaN()) {
                off = got[j].toDouble(); on = got[j + 1].toDouble(); mood = got[j + 2].toInt()
            } else {
                val fill = map.trainLoad(rows[i + 4].toInt(), rows[i + 5].toInt()) ?: 0
                off = fill * .4; on = off; mood = if (fill >= 200) FaceLayer.SAD else FaceLayer.HAPPY
            }
            if (vol > 0f) {
                val screen = map.screenOf(PointF(x, y))
                arrive(screen.x / w * 2 - 1, zoom, vol, speed, popsFor(off), popsFor(on), off, rows[i + 3].toInt(), mood != FaceLayer.HAPPY)
            }
            if (faces && off >= 1) FaceLayer.arrive(map, rows[i + 7].toInt(), x, y, rows[i + 8], rows[i + 9], off, mood)
            i += STRIDE
            j += 3
        }
    }

    private const val RECENT_MS = 900L
    private const val MAX_RECENT = 3
    private const val MAX_POPS = 4
    private const val OFF_SKIP = .3
    private const val OFF_SHIFT_SEMITONES = 4.0
    private const val OFF_JITTER_SEMITONES = 1.0
    private const val SHORT_CHANCE = .45
    private const val MEDIUM_CHANCE = .35
    private fun offGain(riders: Double) = (riders / 80).coerceIn(.25, 1.25).toFloat()
    private fun zoomGain(zoom: Float) = if (zoom < STATION_BOX_ZOOM) 0f else .25f + .75f * min(1f, (zoom - STATION_BOX_ZOOM) / 3)
    private fun popsFor(riders: Double) = if (riders < 1) 0 else min(MAX_POPS, (1 + .7 * log2(1 + riders / 8)).roundToInt())

    /** Web StationSound.play. */
    private fun arrive(pan: Float, zoom: Float, volume: Float, speed: Int, off: Int, on: Int, offRiders: Double, note: Int, crowded: Boolean) {
        if (speed >= 2 || volume <= 0f) return
        val now = SystemClock.elapsedRealtime()
        while (recent.isNotEmpty() && now - recent.first() >= RECENT_MS) recent.removeFirst()
        if (recent.size >= MAX_RECENT) return
        val level = MASTER * volume * zoomGain(zoom) / sqrt(1f + recent.size)
        if (level <= .002f) return
        val together = recent.isNotEmpty()
        recent.addLast(now)
        val p = (pan * .7f).coerceIn(-.7f, .7f)
        val r = Math.random()
        val length = if (r < SHORT_CHANCE) 0 else if (r < SHORT_CHANCE + MEDIUM_CHANCE) 1 else 2
        Mixer.add(Synth.note(level, note, crowded, if (length == 0) .6f else 1f), p)
        val cap = if (together) 0 else if (speed == 1 || length != 2) 2 else MAX_POPS
        var offPops = if (Math.random() < OFF_SKIP) 0 else min(cap, off)
        var onPops = min(cap, on)
        var onAt = .75
        if (length == 0) {
            if (offPops > 0 && (onPops == 0 || Math.random() < .5)) onPops = 0
            else { offPops = 0; onAt = .3 }
        }
        val shift = 2.0.pow((Math.random() * 2 - 1) * OFF_SHIFT_SEMITONES / 12)
        val offLevel = level * offGain(offRiders)
        for (k in 0 until offPops)
            Mixer.add(Synth.pop(offLevel, 640 * shift * 2.0.pow((Math.random() * 2 - 1) * OFF_JITTER_SEMITONES / 12)), p, .28 + k * .09 + Math.random() * .05)
        for (k in 0 until onPops)
            Mixer.add(Synth.pop(level, 360 + Math.random() * 140), p, onAt + k * .1 + Math.random() * .06)
    }

    // ---- synthesis: WebAudio's oscillators and exponential ramps, rendered ahead -------------
    internal object Synth {
        const val RATE = 22050
        /** WebAudio exponentialRampToValueAtTime from `a` at `t0` to `b` at `t1`, at `t`. */
        private fun ramp(a: Double, b: Double, t0: Double, t1: Double, t: Double) =
            if (t <= t0) a else if (t >= t1) b else a * Math.exp(ln(b / a) * (t - t0) / (t1 - t0))

        /** A sine whose pitch and level follow `freq(t)` and `gain(t)` for `seconds`. */
        private fun sine(seconds: Double, freq: (Double) -> Double, gain: (Double) -> Double): FloatArray {
            val n = (seconds * RATE).toInt()
            val out = FloatArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                out[i] = (sin(phase) * gain(t)).toFloat()
                phase += 2 * PI * freq(t) / RATE
            }
            return out
        }

        /** Web loadChime: E5 then A5, 0.16 s apart, each fading in about 0.7 s. */
        fun loadChime(level: Float): FloatArray {
            val a = sine(.75, { 659.25 }) { t -> ramp(1e-4, .5 * level, 0.0, .02, t).let { if (t > .02) ramp(.5 * level, 1e-4, .02, .7, t) else it } }
            val b = sine(.75, { 880.0 }) { t -> ramp(1e-4, .5 * level, 0.0, .02, t).let { if (t > .02) ramp(.5 * level, 1e-4, .02, .7, t) else it } }
            val shift = (.16 * RATE).toInt()
            val out = FloatArray(shift + b.size)
            for (i in a.indices) out[i] += a[i]
            for (i in b.indices) out[shift + i] += b[i]
            return out
        }

        /** Web StationSound.chime: the line's soft sine note; crowded, a minor third lower, softer, sagging. */
        fun note(level: Float, note: Int, crowded: Boolean, len: Float): FloatArray {
            val f = 523.25 * 2.0.pow(note / 12.0) * (if (crowded) .84 else 1.0)
            val end = (if (crowded) .9 else .7) * len
            val peak = (if (crowded) .22 else .32) * level
            return sine(end + .05, { t -> if (crowded) ramp(f, f * .96, 0.0, end - .1, t) else f }) { t ->
                if (t <= .015) ramp(1e-4, peak, 0.0, .015, t) else ramp(peak, 1e-4, .015, end, t)
            }
        }

        /** Web StationSound.pop: a short rounded blip whose pitch drops a little, like a bubble. */
        fun pop(level: Float, f: Double): FloatArray {
            val peak = .24 * level
            return sine(.1, { t -> ramp(f * 1.5, f, 0.0, .035, t) }) { t ->
                if (t <= .004) ramp(1e-4, peak, 0.0, .004, t) else ramp(peak, 1e-4, .004, .09, t)
            }
        }
    }

    /** One stereo stream for the synthesised sounds; its thread ends a second after the last one. */
    private object Mixer : Runnable {
        private class Voice(val data: FloatArray, var at: Int, val left: Float, val right: Float)
        private val voices = ArrayList<Voice>()
        private var thread: Thread? = null
        private const val BLOCK = 512

        fun add(data: FloatArray, pan: Float, delay: Double = 0.0) {
            // equal-power pan, as StereoPannerNode
            val a = (pan.coerceIn(-1f, 1f) + 1) * (PI / 4)
            synchronized(voices) {
                voices.add(Voice(data, -(delay * Synth.RATE).toInt(), kotlin.math.cos(a).toFloat(), sin(a).toFloat()))
                if (thread == null) thread = Thread(this, "world-sound").apply { priority = Thread.MAX_PRIORITY; start() }
            }
        }

        fun stop() = synchronized(voices) { voices.clear() }

        override fun run() {
            val minBuffer = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Synth.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(max(minBuffer, BLOCK * 4 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
            }.getOrNull()
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                track?.release()
                synchronized(voices) { voices.clear(); thread = null }
                return
            }
            val mix = FloatArray(BLOCK * 2)
            val pcm = ShortArray(BLOCK * 2)
            var idle = 0
            try {
                track.play()
                while (true) {
                    java.util.Arrays.fill(mix, 0f)
                    synchronized(voices) {
                        if (voices.isEmpty()) {
                            if (++idle * BLOCK > Synth.RATE) { thread = null; return }
                        } else idle = 0
                        val it = voices.iterator()
                        while (it.hasNext()) {
                            val v = it.next()
                            for (k in 0 until BLOCK) {
                                val j = v.at + k
                                if (j < 0) continue
                                if (j >= v.data.size) break
                                mix[2 * k] += v.data[j] * v.left
                                mix[2 * k + 1] += v.data[j] * v.right
                            }
                            v.at += BLOCK
                            if (v.at >= v.data.size) it.remove()
                        }
                    }
                    // a soft limit in place of the web's compressor
                    for (k in mix.indices) {
                        val s = mix[k]
                        pcm[k] = ((if (s > .9f) .9f + (s - .9f) / (1 + (s - .9f) * 10) else if (s < -.9f) -.9f + (s + .9f) / (1 - (s + .9f) * 10) else s) * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    if (track.write(pcm, 0, pcm.size) < 0) { synchronized(voices) { voices.clear(); thread = null }; return }
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}
