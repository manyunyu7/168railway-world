// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.view.Gravity
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** The full reference editor owns a bounded copy. Only an acknowledged checkpoint can replace the source. */
class StudioFullEditorActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bridge: StudioReferenceClock
    private lateinit var status: TextView
    private lateinit var save: Button
    private var owner: String? = null
    private var slot: WorldDocuments.Slot? = null
    private lateinit var file: AtomicFile
    private var picture: File? = null
    private var expected = ""
    private var ready = false
    private var failed = false
    private var saving = false
    private var leave = false
    private var foreground = false
    private var checkpointAgain = false
    private val autosave = object : Runnable {
        override fun run() {
            if (!foreground || isFinishing) return
            if (ready && !saving) checkpoint(false)
            main.postDelayed(this,45_000L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        picture = state?.getString("picture")?.takeIf { Regex("studio-diagram-[0-9]+\\.png").matches(it) }?.let { File(cacheDir,it) }
        owner = StudioPendingSaves.user(this)
        val id = intent.getStringExtra("archiveSlot")
        slot = if (id != null) WorldDocuments.list(this).firstOrNull { it.id == id } else null
        val source = if (id != null) slot?.let { WorldDocuments.path(this,it.id) }
            else intent.getStringExtra("legacyFile")?.takeIf { Regex("world-native-[A-Za-z0-9_-]+\\.save").matches(it) }?.let { File(filesDir,it) }
        if (source == null) { finish(); return }
        file = AtomicFile(source)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(StudioDepot.PAPER) }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(StudioUi.dp(context,8),StudioUi.dp(context,4),StudioUi.dp(context,8),StudioUi.dp(context,4))
        }
        toolbar.addView(StudioUi.iconButton(this,"Kembali",StudioUi.Icon.BACK) { requestLeave() },
            LinearLayout.LayoutParams(StudioUi.dp(this,48),StudioUi.dp(this,48)))
        status = StudioUi.text(this,"Membuka peta…",14f,true,StudioDepot.TEAL)
        toolbar.addView(status,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = StudioUi.dp(this@StudioFullEditorActivity,8) })
        save = StudioUi.button(this,"Simpan",StudioUi.Tone.PRIMARY,StudioUi.Icon.SAVE) { checkpoint(true) }.apply { isEnabled = false }
        toolbar.addView(save)
        val view = FrameLayout(this)
        root.addView(toolbar)
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        bridge = StudioReferenceClock(this,{}, { fail(it) }).apply { 
            editorSurface = view
            showTutorial = intent.getBooleanExtra("showTutorial",false)
            mapLayer = intent.getStringExtra("mapLayer")
            feature = intent.getStringExtra("feature")
            station = intent.getIntExtra("station",-1)
            onPicture = ::exportDiagram
            onEditorSave = { checkpoint(true) }
         }
        io.execute {
            val read = runCatching { file.readFully().also { WorldDocuments.header(it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                read.onSuccess { bytes ->
                    expected = StudioPendingSaves.hash(bytes)
                    bridge.open(bytes) {
                        ready = true
                        save.isEnabled = true
                        status.text = slot?.title ?: "Peta dunia"
                    }
                }.onFailure { fail(it.localizedMessage ?: "Arsip tidak bisa dibuka.") }
            }
        }
    }

    private fun exportDiagram(bytes: ByteArray) {
        if (picture != null) return
        val temp = runCatching { File.createTempFile("studio-diagram-",".png",cacheDir) }.getOrElse {
            Toast.makeText(this,"Gambar gagal diekspor.",Toast.LENGTH_LONG).show()
            return
        }
        picture = temp
        io.execute {
            val written = runCatching { temp.writeBytes(bytes) }
            runOnUiThread {
                if (isFinishing || isDestroyed) { temp.delete(); return@runOnUiThread }
                written.mapCatching {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png").putExtra(Intent.EXTRA_TITLE,(slot?.title ?: "Studio") + " - diagram.png"),314)
                }.onFailure {
                    temp.delete()
                    picture = null
                    Toast.makeText(this,"Gambar gagal diekspor.",Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Android document picker") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        if (request != 314) return
        val temp = picture ?: return
        picture = null
        val destination = data?.data
        if (result != RESULT_OK || destination == null) { temp.delete(); return }
        io.execute {
            val written = runCatching {
                checkNotNull(contentResolver.openOutputStream(destination)).use { out -> temp.inputStream().use { it.copyTo(out) } }
            }
            temp.delete()
            runOnUiThread { Toast.makeText(this,if (written.isSuccess) "Diagram tersimpan" else "Gambar gagal ditulis.",Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        picture?.let { state.putString("picture", it.name) }
    }

    private fun accessible() = StudioPendingSaves.user(this) == owner &&
        (slot == null || WorldDocuments.list(this).any { it.id == slot?.id })

    private fun checkpoint(pause: Boolean) {
        if (!ready || isFinishing || isDestroyed) return
        if (saving) { if (pause) checkpointAgain = true; return }
        saving = true
        save.isEnabled = false
        if (pause) status.text = "Menyimpan…"
        bridge.checkpoint(pause) { response ->
            if (isDestroyed || isFinishing) return@checkpoint
            response.onFailure {
                saving = false
                fail(it.localizedMessage ?: "Editor gagal membuat arsip.")
            }.onSuccess { bytes ->
                io.execute {
                    val result = runCatching {
                        check(accessible()) { "Akun pemilik dunia telah berubah." }
                        val digest = StudioPendingSaves.hash(bytes)
                        val handle = Engine.open(bytes)
                        check(handle != 0L) { "Arsip hasil editor tidak valid." }
                        Engine.close(handle)
                        check(StudioPendingSaves.hash(file.readFully()) == expected) { "Dunia di perangkat telah berubah. Simpan salinan untuk mempertahankan kedua versi." }
                        if (digest != expected) {
                            val stream = file.startWrite()
                            try { stream.write(bytes); file.finishWrite(stream) }
                            catch (error: Exception) { file.failWrite(stream); throw error }
                            expected = digest
                            slot?.let { StudioPendingSaves.markDirty(this,it.id) }
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        saving = false
                        result.onFailure { error ->
                            leave = false
                            main.removeCallbacks(autosave)
                            if (accessible() && slot != null) offerCopy(bytes,error.localizedMessage.orEmpty())
                            else fail(error.localizedMessage ?: "Arsip tidak bisa disimpan.")
                        }.onSuccess {
                            status.text = slot?.title ?: "Tersimpan di perangkat"
                            save.isEnabled = true
                            if (checkpointAgain) { checkpointAgain = false; checkpoint(true) }
                            else if (leave) finish()
                            else if (pause && foreground) Toast.makeText(this,"Dunia tersimpan di perangkat",Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun offerCopy(bytes: ByteArray, reason: String) {
        save.isEnabled = true
        status.text = "Arsip asal telah berubah"
        StudioDialogBuilder(this).setTitle("Simpan sebagai salinan?")
            .setView(StudioDecision(this,slot?.title ?: "Dunia", "Arsip asal telah berubah").apply {
                fact("Nama salinan", (slot?.title ?: "Dunia")+" · salinan")
            }).setMessage(reason).setNegativeButton("Tetap di editor",null)
            .setNeutralButton("Keluar") { _,_ -> finish() }
            .setPositiveButton("Simpan salinan") { _,_ ->
                val original = slot ?: return@setPositiveButton
                if (!accessible()) return@setPositiveButton
                io.execute {
                    val saved = runCatching {
                        check(accessible()) { "Akun pemilik dunia telah berubah." }
                        val copy = WorldDocuments.import(this,bytes,original.title + " · salinan",original.city,owner)
                        owner?.let { StudioPendingSaves.registerCopy(this,copy,it) }
                    }
                    runOnUiThread { saved.onSuccess { finish() }.onFailure { fail(it.localizedMessage ?: "Salinan gagal dibuat.") } }
                }
            }.show()
    }
    private fun fail(message: String) {
        if (isFinishing || isDestroyed || failed) return
        failed = true
        ready = false
        save.isEnabled = false
        status.text = message
        StudioDialogBuilder(this).setTitle("Peta dunia").setMessage(message)
            .setPositiveButton("Kembali") { _,_ -> finish() }.show()
    }
    private fun requestLeave() { if (!ready) finish() else { leave = true; checkpoint(true) } }
    @Deprecated("Android back button") override fun onBackPressed() = requestLeave()
    override fun onResume() {
        super.onResume()
        foreground = true
        if (::file.isInitialized && !accessible()) { finish(); return }
        main.removeCallbacks(autosave)
        main.postDelayed(autosave,45_000L)
    }
    override fun onPause() {
        foreground = false
        main.removeCallbacks(autosave)
        if (ready && !leave && !isFinishing) checkpoint(true)
        super.onPause()
    }
    override fun onDestroy() {
        main.removeCallbacks(autosave)
        if (::bridge.isInitialized) bridge.close()
        if (isFinishing) picture?.delete()
        io.shutdown()
        super.onDestroy()
    }
}
