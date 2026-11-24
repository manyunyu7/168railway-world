// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Deleting a world from this phone as the web's `DeleteAsk` (SavesPanel.tsx): "Hapus … dari HP
 * ini?", and from `WorldDocuments.TYPE_TO_DELETE` stations up Hapus waits for the station count
 * typed. Shared by the archive and the Studio library.
 */
internal object WorldDeletion {
    /**
     * The world's built stations: its cover's, or with no cover (an old save, one never left from
     * the map) counted from the save itself, so the typed check is not skipped; null if unreadable.
     */
    fun stations(c: Context, slot: WorldDocuments.Slot): Int? {
        WorldCovers.read(c, slot.id)?.let { return it.stations }
        return runCatching {
            val bytes = android.util.AtomicFile(WorldDocuments.path(c, slot.id)).openRead().use { WorldDocuments.read(it) }
            val handle = Engine.open(bytes)
            check(handle != 0L)
            try {
                val stops = JSONObject(Engine.geometry(handle)).getJSONArray("stations")
                // a station stop carries its node id (a bus stop -1), as the map's station count
                (0 until stops.length()).count { stops.getJSONArray(it).optInt(3, -1) >= 0 }
            } finally {
                Engine.close(handle)
            }
        }.getOrNull()
    }

    /** Counts the stations on `worker`, asks, then deletes on `worker` and calls `deleted` on the UI thread. */
    fun ask(activity: Activity, worker: Executor, slot: WorldDocuments.Slot, deleted: () -> Unit) {
        worker.execute {
            val stations = stations(activity, slot)
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                dialog(activity, slot, stations) {
                    worker.execute {
                        val result = runCatching { WorldDocuments.delete(activity, slot.id) }
                        activity.runOnUiThread {
                            if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                            result.onSuccess { deleted() }.onFailure {
                                android.widget.Toast.makeText(activity, it.localizedMessage ?: "Dunia gagal dihapus.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dialog(activity: Activity, slot: WorldDocuments.Slot, stations: Int?, yes: () -> Unit) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        body.addView(StudioUi.text(activity, "Hapus “${slot.title}” dari HP ini? Salinan di akun tidak ikut terhapus.", 15f, true, StudioUi.INK))
        // an unreadable count asks for the name instead, so a big world is never one tap away
        val gate = stations == null || stations >= WorldDocuments.TYPE_TO_DELETE
        val want = stations?.toString() ?: slot.title.trim()
        val typed = EditText(activity).apply {
            inputType = if (stations != null) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
            hint = want
            contentDescription = if (stations != null) "Jumlah stasiun" else "Nama dunia"
        }
        if (gate) {
            body.addView(StudioUi.text(activity,
                if (stations != null) "Game ini punya $stations stasiun. Ketik $stations untuk menghapusnya."
                else "Jumlah stasiun tidak terbaca. Ketik nama dunia untuk menghapusnya.",
                14f, true, StudioUi.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            body.addView(typed)
        }
        val dialog = StudioDialogBuilder(activity).setTitle("Hapus dunia?").setView(body)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> yes() }
            .show()
        val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        DemolitionPanel.danger(button)
        if (gate) {
            button.isEnabled = false
            typed.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) { button.isEnabled = t.toString().trim() == want }
                override fun afterTextChanged(e: android.text.Editable?) {}
            })
        }
    }
}
