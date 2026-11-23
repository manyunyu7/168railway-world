// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** The bundled Studio workers own elapsed time. Kotlin publishes only complete TWG1 checkpoints. */
internal class StudioReferenceClock(
    private val activity: Activity,
    private val onClock: (Double) -> Unit,
    private val onUnavailable: (String) -> Unit,
) {
    var onTrainLoads: ((Long,org.json.JSONArray) -> Unit)? = null
    var overlayKind: String? = null
    var overlayCity: String? = null
    var onOverlayPart: ((String, org.json.JSONArray) -> Unit)? = null
    var onOverlayEnd: (() -> Unit)? = null
    var onCash: ((Double) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val origin = Uri.parse("https://studio-clock.invalid")
    private val entry get() = "$origin/clock/index.html"
    private val resources = StudioClockResources(activity.applicationContext, origin)
    private var view: WebView? = null
    private var channel: WebMessagePort? = null
    private var deadline: Runnable? = null
    private var phase = Phase.NEW
    private var sequence = 0
    private var openId = 0
    private var snapshotId = 0
    private var initial: ByteArray? = null
    private var opened: (() -> Unit)? = null
    private var pending: ((Result<ByteArray>) -> Unit)? = null
    private var output: ByteArray? = null
    private var offset = 0
    private var lastClock = Double.NEGATIVE_INFINITY

    private enum class Phase { NEW, LOADING, OPENING, READY, SAVING, CLOSED }

    fun open(snapshot: ByteArray, ready: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(phase == Phase.NEW)
        phase = Phase.LOADING
        if (snapshot.size !in 1..WorldDocuments.LIMIT) return fail("Ukuran arsip Studio tidak valid.")
        initial = snapshot.copyOf()
        opened = ready
        arm(180_000)
        runCatching {
            val web = WebView(activity)
            view = web
            web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            web.isFocusable = false
            web.isClickable = false
            web.alpha = 0f
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse =
                    resources.get(r)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = true
                override fun onPageFinished(v: WebView, url: String) {
                    if (phase == Phase.LOADING && url == entry) waitForListener(v)
                }
                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail("Proses simulasi Studio berhenti.")
                    return true
                }
            }
            // A one-pixel attached document keeps workers alive while the native map is visible.
            activity.findViewById<ViewGroup>(android.R.id.content).addView(web, ViewGroup.LayoutParams(1, 1))
            web.loadUrl(entry)
        }.onFailure { fail(it.message ?: "WebView Studio tidak dapat dibuka.") }
    }

    private fun waitForListener(web: WebView) {
        if (phase != Phase.LOADING || view !== web || web.url != entry) return
        web.evaluateJavascript("window.__studioClockReady === true") { ready ->
            if (phase == Phase.LOADING && view === web && web.url == entry) {
                if (ready == "true") connect(web)
                else main.postDelayed({ waitForListener(web) }, 100)
            }
        }
    }

    private fun connect(web: WebView) {
        runCatching {
            phase = Phase.OPENING
            val ports = web.createWebMessageChannel()
            channel = ports[0]
            ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (phase == Phase.CLOSED) return
                    runCatching { receive(JSONObject(requireNotNull(message.data))) }
                        .onFailure { fail(it.message ?: "Pesan mesin Studio tidak valid.") }
                }
            }, main)
            web.postWebMessage(WebMessage("""{"type":"connect"}""", arrayOf(ports[1])), origin)
        }.onFailure { fail(it.message ?: "Kanal mesin Studio gagal dibuka.") }
    }

    fun play(rate: Double) {
        if (phase != Phase.READY) return
        require(rate.isFinite() && rate > 0)
        send(JSONObject().put("type", "play").put("rate", rate))
    }

    fun pause() {
        if (phase == Phase.READY) send(JSONObject().put("type", "pause"))
    }

    fun snapshot(done: (Result<ByteArray>) -> Unit) = checkpoint(true, done)

    fun checkpoint(pause: Boolean, done: (Result<ByteArray>) -> Unit) {
        if (phase != Phase.READY) {
            done(Result.failure(IllegalStateException("Simulasi Studio belum siap menyimpan.")))
            return
        }
        phase = Phase.SAVING
        pending = done
        snapshotId = ++sequence
        arm(45_000)
        if (!pause || send(JSONObject().put("type", "pause"))) {
            send(JSONObject().put("type", "snapshot").put("id", snapshotId))
        }
    }

    private fun send(obj: JSONObject): Boolean = try {
        checkNotNull(channel).postMessage(WebMessage(obj.toString()))
        true
    } catch (e: RuntimeException) {
        fail(e.message ?: "Kanal mesin Studio terputus.")
        false
    }

    private fun feed(bytes: ByteArray, from: Int) {
        if (phase != Phase.OPENING) return
        if (from == bytes.size) {
            initial = null
            send(JSONObject().put("type", "finish").put("id", openId))
            return
        }
        val length = minOf(48 * 1024, bytes.size - from)
        val chunk = Base64.encodeToString(bytes, from, length, Base64.NO_WRAP)
        if (send(JSONObject().put("type", "input").put("id", openId).put("offset", from).put("base64", chunk))) {
            main.post { feed(bytes, from + length) }
        }
    }

    private fun receive(msg: JSONObject) {
        when (msg.getString("type")) {
            "ready" -> {
                check(phase == Phase.OPENING && openId == 0)
                val bytes = checkNotNull(initial)
                openId = ++sequence
                val command = JSONObject().put("type","open").put("id",openId).put("size",bytes.size)
                if (overlayKind != null) command.put("overlay",overlayKind).put("overlayCity",overlayCity)
                if (onTrainLoads != null) command.put("trainLoads",true)
                if (send(command)) feed(bytes,0)
            }
            "opened" -> {
                check(phase == Phase.OPENING && msg.getInt("id") == openId)
                disarm()
                phase = Phase.READY
                deliverClock(msg)
                val action = opened
                opened = null
                action?.invoke()
            }
            "overlayPart" -> if (phase == Phase.READY && overlayKind != null) {
                val values = msg.getJSONArray("rows")
                require(values.length() <= 1280)
                onOverlayPart?.invoke(msg.getString("section"),values)
            }
            "overlayEnd" -> if (phase == Phase.READY && overlayKind != null) onOverlayEnd?.invoke()
            "trainLoads" -> if (phase == Phase.READY || phase == Phase.SAVING) {
                val rows = msg.optJSONArray("rows")
                if (rows != null && rows.length() <= 1536 && rows.length()%3 == 0) onTrainLoads?.invoke(msg.getLong("epoch"),rows)
            }
            "state" -> if (phase == Phase.READY || phase == Phase.SAVING) deliverClock(msg)
            "snapshotBegin" -> {
                check(phase == Phase.SAVING && msg.getInt("id") == snapshotId && output == null)
                val size = msg.getInt("size")
                require(size in 1..WorldDocuments.LIMIT)
                output = ByteArray(size)
                offset = 0
            }
            "output" -> {
                check(phase == Phase.SAVING && msg.getInt("id") == snapshotId && msg.getInt("offset") == offset)
                val dest = checkNotNull(output)
                val chunk = msg.getString("base64")
                require(chunk.length in 1..(256 * 1024))
                val data = Base64.decode(chunk, Base64.NO_WRAP)
                require(data.isNotEmpty() && data.size <= dest.size - offset)
                data.copyInto(dest, offset)
                offset += data.size
            }
            "snapshotEnd" -> {
                check(phase == Phase.SAVING && msg.getInt("id") == snapshotId)
                val bytes = checkNotNull(output)
                check(offset == bytes.size)
                val clock = msg.getDouble("clock")
                val header = WorldDocuments.header(bytes)
                check(clock.isFinite() && clock + 0.01 >= lastClock && kotlin.math.abs(clock - header.getDouble("clock")) < 0.01)
                output = null
                disarm()
                deliverClock(msg)
                if (phase == Phase.SAVING) {
                    phase = Phase.READY
                    val done = pending
                    pending = null
                    done?.invoke(Result.success(bytes))
                }
            }
            "error" -> fail(msg.optString("message").take(240).ifBlank { "Simulasi Studio gagal." })
        }
    }

    private fun deliverClock(msg: JSONObject) {
        val time = msg.getDouble("clock")
        require(time.isFinite() && time >= 0)
        if (time >= lastClock) {
            lastClock = time
            msg.optDouble("cash").takeIf { it.isFinite() }?.let { onCash?.invoke(it) }
            onClock(time)
        }
    }

    private fun arm(ms: Long) {
        disarm()
        deadline = Runnable { fail("Mesin Studio tidak merespons tepat waktu.") }.also { main.postDelayed(it, ms) }
    }

    private fun disarm() {
        deadline?.let(main::removeCallbacks)
        deadline = null
    }

    private fun fail(message: String) {
        if (phase == Phase.CLOSED) return
        shutdown(message)
        onUnavailable(message)
    }

    fun close() {
        if (phase != Phase.CLOSED) shutdown("Simulasi Studio ditutup.")
    }

    private fun shutdown(message: String) {
        phase = Phase.CLOSED
        disarm()
        initial = null
        output = null
        opened = null
        channel?.close()
        channel = null
        view?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        view = null
        val done = pending
        pending = null
        done?.invoke(Result.failure(IllegalStateException(message)))
    }
}
