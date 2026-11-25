// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import java.util.ArrayDeque

/** Each snapshot belongs to one open world. The next edit is at the tail of each stack. */
internal class WorldEditHistory(
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val maxEntries: Int = 12
) {
    data class Entry(val label: String, val bytes: ByteArray)
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    private var bytesUsed = 0L

    fun canKeep(bytes: ByteArray) = bytes.isNotEmpty() && bytes.size <= maxBytes
    fun next(backwards: Boolean): Entry? = if (backwards) undo.peekLast() else redo.peekLast()

    fun record(label: String, before: ByteArray) {
        while (redo.isNotEmpty()) bytesUsed -= redo.removeLast().bytes.size
        if (!canKeep(before)) {
            undo.clear()
            bytesUsed = 0
            return
        }
        append(undo, undo, Entry(label, before))
    }

    /** Advance history only after the caller has validated and installed the requested snapshot. */
    fun moved(backwards: Boolean, current: ByteArray) {
        val from = if (backwards) undo else redo
        val to = if (backwards) redo else undo
        require(canKeep(current) && from.isNotEmpty())
        val target = from.removeLast()
        bytesUsed -= target.bytes.size
        append(to, from, Entry(target.label, current))
    }

    private fun append(to: ArrayDeque<Entry>, other: ArrayDeque<Entry>, entry: Entry) {
        while (bytesUsed + entry.bytes.size > maxBytes || undo.size + redo.size >= maxEntries) {
            val farthest = if (other.isNotEmpty()) other else to
            bytesUsed -= farthest.removeFirst().bytes.size
        }
        to.addLast(entry)
        bytesUsed += entry.bytes.size
    }
}
