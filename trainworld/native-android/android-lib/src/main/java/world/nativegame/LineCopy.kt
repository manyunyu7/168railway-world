package world.nativegame

import android.app.Activity
import org.json.JSONObject

/**
 * "Salin line", web LineInspector `copy` (op `copyLine`): the line's stops and settings, colour and bullet
 * shape on a new line named and lettered as the web clones it (Rust `line_copy.rs`), no trains yet;
 * one edit (one undo) through the panel's transaction.
 */
internal object LineCopy {
    @JvmStatic private external fun copy(handle: Long, id: Int, now: Double): String

    /** The undo entry's label, and what tells the activity to redraw the map's lines. */
    const val LABEL = "Salin line"

    /** Copy line `id`; `done` gets the copy's id once it is made. */
    fun run(host: Activity, handle: Long, id: Int, transaction: PanelEdit, done: (Int) -> Unit) {
        transaction { now ->
            val r = runCatching { JSONObject(copy(handle, id, now)) }.getOrNull()
            when {
                r == null -> WorldSound.refused(host, "Line tidak bisa disalin.")
                r.has("error") -> WorldSound.refused(host, r.getString("error"))
                else -> {
                    ServicePanel.editLabel = LABEL
                    LineLook.remember(r)
                    done(r.getInt("id"))
                }
            }
        }
    }
}
