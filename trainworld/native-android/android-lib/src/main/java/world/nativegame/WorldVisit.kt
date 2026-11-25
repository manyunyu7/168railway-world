// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The web game's visit note (app/src/game/visitNote.ts) for the native World: the same
 * GET /api/web/studio/visit?ev=open|start|continue, so the owner's /admin/world-visits counts Android
 * players too. Fire and forget on a background thread; nothing waits for it and a failure is silent.
 * Signed in, the app's token goes along as Bearer so the visit carries the account (the server reads
 * it through Sanctum); the user agent says "168RailwayApp 168RailwayNative" so the row is marked native.
 */
internal object WorldVisit {
    private const val URL_BASE = "https://168railway.com/api/web/studio/visit"
    /** A new world's id between its creation ("start") and its first open, which then is no "continue". */
    var justStarted: String? = null

    private fun anon(c: Context): String {
        val prefs = c.getSharedPreferences("world-native", Context.MODE_PRIVATE)
        prefs.getString("visit-anon", null)?.let { return it }
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = (1..10).map { chars.random() }.joinToString("")
        prefs.edit().putString("visit-anon", id).apply()
        return id
    }

    private fun token(c: Context): String? = c.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        .getString("flutter.railway_auth_token", null)?.takeIf { it.isNotBlank() }

    fun open(c: Context) = note(c, "open")
    fun continued(c: Context, slotId: String) {
        if (justStarted == slotId) { justStarted = null; return }
        note(c, "continue")
    }
    fun started(c: Context, slotId: String, city: String, sandbox: Boolean, scenario: String?) {
        justStarted = slotId
        note(c, "start", "mode" to (if (sandbox) "sandbox" else "normal"), "city" to city, "scen" to scenario)
    }

    private fun note(c: Context, event: String, vararg extra: Pair<String, String?>) {
        val app = c.applicationContext
        val params = (listOf("ev" to event, "anon" to anon(app), "ref" to "android-native") + extra)
            .filter { !it.second.isNullOrBlank() && Regex("^[A-Za-z0-9_.-]{1,24}$").matches(it.second!!) }
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val bearer = token(app)
        Thread({
            runCatching {
                val conn = URL("$URL_BASE?$params").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "168RailwayApp 168RailwayNative Android ${android.os.Build.VERSION.RELEASE}")
                conn.setRequestProperty("Accept", "application/json")
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.responseCode
                conn.disconnect()
            }
        }, "world-visit").start()
    }
}
