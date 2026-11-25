// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

/**
 * Lines hidden on the map (web game/lineVisibility.ts, T-3420). Saved with the world as the web
 * saves them, in the save header's `hidden` (line_visibility.rs), outside the undo history. A view
 * setting only: a hidden line still runs. Its stroke (LineBands, RailLayer) and its trains
 * (train_scene.rs reads the header itself) are not drawn, nor its quad-track twins'; a tap on the
 * map passes over it. Stations stay.
 *
 * The Line list's eye button is the web's hide mode: on, each row shows its own eye; off again
 * ("Tampilkan semua lini") shows every line. A world with lines hidden opens in hide mode, the only
 * place they can be shown again.
 */
internal object LineVisibility {
    @JvmStatic private external fun hidden(handle: Long): IntArray
    @JvmStatic private external fun drawn(handle: Long): IntArray
    @JvmStatic private external fun set(handle: Long, ids: IntArray): Boolean
    @JvmStatic private external fun carry(from: Long, to: Long): Boolean

    /** Lines not drawn: the hidden ones and their twins. Read by the map's line painters. */
    @Volatile var off: Set<Int> = emptySet()
        private set
    /** Counts changes, so the train painter asks for a new scene even while the clock stands. */
    @Volatile var version = 0
        private set
    /** The web's `visToggles`: the rows show their eyes. */
    var eyes = false
    /** After a change: redraw the map and mark the world changed (NativeWorldActivity). */
    var changed: (() -> Unit)? = null
    /** Write the save, a moment after the last of a run of taps rather than on each. */
    var persist: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val save = Runnable { persist?.invoke() }

    private var lastHandle = 0L
    /** The world `handle` was (re)loaded: read what it hides. */
    fun refresh(handle: Long) {
        val now = if (handle == 0L) emptySet() else runCatching { drawn(handle).toSet() }.getOrDefault(emptySet())
        // another world opens out of hide mode unless it has lines hidden (web `takeHiddenLines`)
        if (handle != lastHandle) { lastHandle = handle; eyes = false }
        if (now.isNotEmpty()) eyes = true
        if (now != off) { off = now; version++ }
    }

    fun hiddenIds(handle: Long): Set<Int> = if (handle == 0L) emptySet() else runCatching { hidden(handle).toSet() }.getOrDefault(emptySet())

    /** Web `setLinesHidden`: hide or show lines `ids`. */
    fun setHidden(handle: Long, ids: Collection<Int>, hide: Boolean) {
        if (handle == 0L) return
        val was = hiddenIds(handle)
        val next = if (hide) was + ids else was - ids.toSet()
        if (next == was) return
        if (runCatching { set(handle, next.toIntArray()) }.getOrDefault(false)) {
            refresh(handle); version++
            changed?.invoke()
            main.removeCallbacks(save); main.postDelayed(save, 1500)
        }
    }

    /** Web `showAllLines`, leaving hide mode. */
    fun showAll(handle: Long) {
        eyes = false
        setHidden(handle, hiddenIds(handle), false)
    }

    /** The hidden lines of `from` onto `to`, when a checkpoint or an undo swaps the world; true if `to` changed. */
    fun carryTo(from: Long, to: Long): Boolean = from != 0L && to != 0L && runCatching { carry(from, to) }.getOrDefault(false)
}
