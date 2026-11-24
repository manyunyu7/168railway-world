// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.text.format.Formatter
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class WorldArchiveActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val login by lazy { WorldLogin(this) }
    private val store by lazy { StudioCatalog.get(this) }
    private lateinit var menu: LinearLayout
    private lateinit var feedback: TextView
    private var busy=false
    /** The world whose file is being written (web `exportSlot`), kept across a recreate while the picker is open. */
    private var exporting: String? = null
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("exporting", exporting) }
    /** A file name from a world's name: "Jakarta 2026.save". */
    private fun fileName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "dunia" }.take(80) + ".save"
    override fun onCreate(state: Bundle?) {
        exporting = state?.getString("exporting")
        super.onCreate(state)
        val d=resources.displayMetrics.density
        menu=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding((18*d).toInt(),(14*d).toInt(),(18*d).toInt(),(28*d).toInt()) }
        if (android.os.Build.VERSION.SDK_INT >= 30) menu.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding((18*d).toInt()+bars.left,(14*d).toInt()+bars.top,(18*d).toInt()+bars.right,(28*d).toInt()+bars.bottom)
            insets
        }
        window.statusBarColor = 0xff9fd8f7.toInt()
        window.navigationBarColor = 0xffbfe6a8.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(FrameLayout(this).apply {
            addView(KScene.Sky(context), FrameLayout.LayoutParams(-1,-1))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled=false; addView(menu) }, FrameLayout.LayoutParams(-1,-1))
        })
        render()
    }
    override fun onResume() { super.onResume(); if(!busy) render() }
    private fun source(): File? {
        val name = intent.getStringExtra("archiveSource") ?: return null
        if(Regex("world-native-[a-z0-9]{2,12}\\.save").matches(name)) return File(filesDir,name)
        val id=runCatching { UUID.fromString(name).toString() }.getOrNull() ?: return null
        return WorldDocuments.path(this,id)
    }
    private fun sourceBytes(): ByteArray {
        val file=source() ?: error("Buka dunia terlebih dahulu.")
        return AtomicFile(file).openRead().use { WorldDocuments.read(it) }
    }
    private fun dp(n: Int) = StudioUi.dp(this,n)
    private fun button(label: String, enabled: Boolean=true, action: ()->Unit) {
        menu.addView(StudioUi.button(this,label) { action() }.apply { isEnabled=enabled },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
    }
    private fun item(icon: StudioUi.Icon, tint: Int, label: String, detail: String?, enabled: Boolean=true, action: ()->Unit) {
        menu.addView(StudioUi.row(this,icon,tint,label,detail) { if (!busy) action() }.apply {
            isEnabled=enabled
            alpha = if (enabled) 1f else .5f
        }, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
    }
    private val localOnly get() = intent.getBooleanExtra("localOnly",false)

    private fun render() {
        menu.removeAllViews()
        menu.addView(LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(KScene.glassIcon(context,StudioUi.Icon.BACK,"Kembali") { finish() },LinearLayout.LayoutParams(dp(50),dp(50)))
            val heading=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                background=StudioUi.glass(context,25)
                setPadding(dp(18),dp(6),dp(18),dp(8))
                addView(StudioUi.title(context,if (localOnly) "Arsip di HP" else "Arsip & akun",22f))
                intent.getStringExtra("city")?.let { addView(StudioUi.text(context,"Dunia aktif · ${it.uppercase()}",12f,true,StudioUi.MUTED)) }
            }
            addView(heading,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(10) })
        })
        feedback=StudioUi.text(this,"",14f,true,StudioUi.MUTED).apply { setPadding(dp(4),dp(10),dp(4),0) }
        menu.addView(feedback)
        if (!localOnly) accountActions()
        menu.addView(StudioUi.section(this,"Berkas"))
        item(StudioUi.Icon.OPEN_FILE,StudioUi.TINT_YELLOW,"Impor berkas dunia","Berkas .save dari penyimpanan HP") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1)
        }
        // a greyed-out row that can never be used here was noise: each world below has its own download
        if (source()?.isFile==true) item(StudioUi.Icon.DOWNLOAD,StudioUi.TINT_GREEN,"Ekspor dunia aktif","Simpan sebagai berkas .save") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,
                    WorldDocuments.list(this).firstOrNull { WorldDocuments.path(this,it.id).canonicalPath == source()?.canonicalPath }?.let { fileName(it.title) }
                        ?: "${intent.getStringExtra("city") ?: "dunia"}.save"),2)
        }
        val slots=WorldDocuments.list(this)
        menu.addView(StudioUi.section(this,"Dunia di HP"))
        // an empty list says so, rather than the section going missing
        if (slots.isEmpty()) menu.addView(StudioUi.text(this,"Belum ada simpanan di HP ini. Impor berkas di atas, atau mulai dunia baru dari menu.",14f,true,StudioUi.MUTED).apply {
            setPadding(dp(4),dp(6),dp(4),dp(6))
        })
        val playing = source()?.canonicalPath
        slots.forEach { slot ->
            // web SlotFacts: the city's name, lines and stations, and when it was saved
            val cover = WorldCovers.read(this,slot.id)
            val city = runCatching { store.city(slot.city).name }.getOrDefault(slot.city.uppercase())
            val facts = listOfNotNull(city, cover?.let { "${it.lines} lini · ${it.stations} stasiun" },
                slot.at.takeIf { it > 0 }?.let { WorldDocuments.savedWhen(it) }).joinToString(" · ")
            val here = WorldDocuments.path(this,slot.id).canonicalPath == playing
            val row = StudioUi.row(this,StudioUi.Icon.TRAIN,StudioUi.TINT_ORANGE,slot.title,
                if (here) "Dimainkan · $facts" else facts) { if (!busy) { if (localOnly) actions(slot,here) else open(slot) } }
            // web SavesPanel card buttons: rename, copy and (not the game being played) delete
            row.addView(StudioUi.iconButton(this,"Tindakan untuk ${slot.title}",StudioUi.Icon.MORE_VERT) {
                if (!busy) actions(slot, here)
            }, LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(6) })
            menu.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        }
    }
    private fun accountActions() {
        val session=login.get("session")
        val account=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            background=StudioUi.glass(this@WorldArchiveActivity,24)
            setPadding(dp(14),dp(12),dp(14),dp(12))
            addView(StudioDepot.symbol(context,StudioUi.Icon.CLOUD,22f,StudioUi.INK).apply {
                background=StudioUi.panel(context,if (session==null) StudioUi.TINT_ORANGE else StudioUi.TINT_GREEN,15,0)
            },LinearLayout.LayoutParams(dp(46),dp(46)))
            val texts=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                addView(StudioUi.text(context,if (session==null) "Belum terhubung" else "Akun 168Railway",15f,true))
                addView(StudioUi.text(context,if (session==null) "Arsip tersimpan di HP ini saja" else "ID ${session.getString("userId")}",12f,true,StudioUi.MUTED))
            }
            addView(texts,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) })
        }
        menu.addView(account,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        if(session==null) {
            menu.addView(StudioUi.button(this,"Hubungkan akun 168Railway",StudioUi.Tone.PRIMARY,StudioUi.Icon.CLOUD) {
                if (!busy) runCatching { startActivity(Intent(Intent.ACTION_VIEW,login.start())) }.onFailure { feedback.text=it.localizedMessage }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        } else {
            menu.addView(StudioUi.section(this,"Akun"))
            item(StudioUi.Icon.SAVE,StudioUi.TINT_PURPLE,"Simpan ke akun","Buat atau perbarui arsip",source()?.isFile==true) { chooseRemote(session,true) }
            item(StudioUi.Icon.UNDO,StudioUi.TINT_BLUE,"Pulihkan dari akun","Buka arsip sebagai dunia baru") { chooseRemote(session,false) }
            item(StudioUi.Icon.CLOSE,StudioUi.TINT_RED,"Putuskan akun",null) { job({ try { WorldCloud.logout(session) } finally { login.forget() } }) { render() } }
        }
    }
    private fun actions(slot: WorldDocuments.Slot, playing: Boolean) {
        StudioUi.sheet(this, slot.title, .5f) { column, sheet ->
            fun action(icon: StudioUi.Icon, tint: Int, label: String, detail: String?, go: () -> Unit) =
                column.addView(StudioUi.row(this, icon, tint, label, detail) { sheet.dismiss(); go() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            action(StudioUi.Icon.EDIT, StudioUi.TINT_BLUE, "Ganti nama", null) { askRename(slot) }
            // web exportSlot: "Unduh sebagai file", any world, named after it
            action(StudioUi.Icon.DOWNLOAD, StudioUi.TINT_GREEN, "Unduh sebagai file", fileName(slot.title)) {
                exporting = slot.id
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, fileName(slot.title)), 3)
            }
            action(StudioUi.Icon.COPY, StudioUi.TINT_YELLOW, "Buat salinan", "Salinan ${slot.title}") {
                job({ WorldDocuments.copy(this, slot) }) { feedback.text = "${it.title} dibuat di HP ini." }
            }
            if (!playing) action(StudioUi.Icon.DELETE, StudioUi.TINT_RED, "Hapus", "Dari HP ini") {
                WorldDeletion.ask(this, worker, slot) { render(); feedback.text = "${slot.title} dihapus dari HP ini." }
            }
        }
    }
    /** Web NameField ("Ganti nama"): the name typed, Selesai or the keyboard's done keeps it. */
    private fun askRename(slot: WorldDocuments.Slot) {
        val field = EditText(this).apply {
            setText(slot.title); setSingleLine(); setSelectAllOnFocus(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            contentDescription = "Nama"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
        val save = { job({ WorldDocuments.rename(this, slot.id, field.text.toString()) }) { if (it) feedback.text = "Nama diganti." } }
        val dialog = StudioDialogBuilder(this).setTitle("Ganti nama").setView(box)
            .setNegativeButton("Batal", null).setPositiveButton("Selesai") { _, _ -> save() }.show()
        field.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); save(); true }
        field.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    private fun <T> job(work: ()->T, done: (T)->Unit) {
        if(busy) return
        busy=true
        for(i in 0 until menu.childCount) menu.getChildAt(i).isEnabled=false
        feedback.text="Memproses…"
        worker.execute {
            val result=runCatching(work)
            runOnUiThread {
                if(isDestroyed || isFinishing) return@runOnUiThread
                busy=false; render()
                result.onSuccess(done).onFailure { feedback.text=it.localizedMessage ?: "Arsip gagal diproses." }
            }
        }
    }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        val uri=data?.data ?: return
        if(result!=RESULT_OK) return
        if(request==1) {
            job({
                val bytes=WorldDocuments.read(checkNotNull(contentResolver.openInputStream(uri)))
                val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) it.getString(0) else null
                } ?: "Dunia impor"
                WorldDocuments.header(bytes)
                bytes to name
            }) { (bytes,name)-> chooseCity(bytes,name) }
        } else if(request==2) job({
            val bytes=sourceBytes()
            checkNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) }
        }) { feedback.text="Dunia berhasil diekspor." }
        else if(request==3) {
            val id = exporting ?: return
            exporting = null
            job({
                val bytes = android.util.AtomicFile(WorldDocuments.path(this,id)).openRead().use { WorldDocuments.read(it) }
                checkNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) }
            }) { feedback.text="Dunia berhasil diunduh sebagai file." }
        }
    }
    private fun chooseCity(bytes: ByteArray, name: String) {
        val city = WorldDocuments.startingCity(WorldDocuments.header(bytes),store.cities().toList())
            ?: intent.getStringExtra("city").orEmpty()
        StudioZonePicker.show(this,store,city,"Peta untuk $name",allowCurrent=true) { id ->
            val place = store.city(id)
            val content = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                StudioMapPreview.image(context,StudioMapPreview.cityPath(id),"Peta wilayah "+place.name)?.let { addView(it) }
                addView(StudioUi.title(context,name,21f))
                addView(StudioUi.text(context,place.name+" · "+Formatter.formatShortFileSize(context,bytes.size.toLong()),15f,true,StudioUi.MUTED))
            }
            StudioDialogBuilder(this).setTitle("Impor ke dunia baru?").setView(content)
                .setPositiveButton("Buat slot baru") { _,_ ->
                    job({ WorldDocuments.import(this,bytes,name,id) }) { open(it) }
                }.setNegativeButton("Batal",null).show()
        }
    }
    private fun open(slot: WorldDocuments.Slot) {
        startActivity(Intent(this,NativeWorldActivity::class.java).putExtra("archiveSlot",slot.id))
    }
    private fun chooseRemote(session: JSONObject, write: Boolean) {
        job({ WorldCloud.list(session) }) { rows ->
            val records=List(rows.length()) { rows.getJSONObject(it) }
            StudioUi.sheet(this,if (write) "Simpan ke akun" else "Pulihkan dunia") { content, dialog ->
                fun add(view: View) = content.addView(view,LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(this@WorldArchiveActivity,8) })
                if (write) add(StudioUi.button(this,"Buat arsip baru",StudioUi.Tone.PRIMARY,StudioUi.Icon.PLUS) {
                    dialog.dismiss()
                    val name=EditText(this).apply {
                        hint="Nama arsip"; setSingleLine(true); filters=arrayOf(android.text.InputFilter.LengthFilter(100))
                        typeface=StudioUi.face(context,true); textSize=16f
                        setTextColor(StudioUi.INK); setHintTextColor(StudioUi.MUTED)
                        background=StudioUi.pill(context,StudioUi.WHITE,27); minHeight=dp(54); setPadding(dp(18),0,dp(18),0)
                    }
                    val entry = LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(name)
                        intent.getStringExtra("city")?.let { city ->
                            StudioMapPreview.image(context,StudioMapPreview.cityPath(city),"Peta dunia aktif")?.let { addView(it) }
                        }
                    }
                    val newArchive = StudioDialogBuilder(this).setTitle("Arsip baru").setView(entry)
                        .setPositiveButton("Simpan",null).setNegativeButton("Batal",null).show()
                    newArchive.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val label=name.text.toString().trim()
                        if(label.isBlank()) {
                            name.error="Isi nama arsip."
                            name.requestFocus()
                        } else {
                            newArchive.dismiss()
                            upload(session,UUID.randomUUID().toString(),0,label)
                        }
                    }
                })
                if (records.isEmpty()) add(StudioUi.text(this,"Belum ada dunia tersimpan di akun ini.",15f,true,StudioUi.MUTED))
                for(row in records) {
                    val id=row.getString("id")
                    val title=row.getString("name")
                    val detail=saveDetails(row)
                    add(StudioUi.row(this,StudioUi.Icon.CLOUD,StudioUi.TINT_BLUE,title,detail) {
                        dialog.dismiss()
                        if(write) {
                            StudioDialogBuilder(this).setTitle("Perbarui $title?")
                                .setMessage("$detail\nSimpanan di akun akan diganti dengan dunia aktif.")
                                .setPositiveButton("Perbarui") { _,_->upload(session,id,row.getInt("version"),title) }
                                .setNegativeButton("Batal",null).show()
                        } else job({
                            WorldCloud.read(session,id,row.getInt("version")).also { WorldDocuments.header(it) }
                        }) { bytes->chooseCity(bytes,title) }
                    })
                }
            }
        }
    }
    private fun upload(session: JSONObject,id: String,version: Int,name: String) {
        job({ WorldCloud.put(session,id,version,name,sourceBytes()) }) { world ->
            feedback.text="Tersimpan di akun · versi ${world.getInt("version")}"
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}

class WorldLoginCallbackActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri=intent.data ?: run { finish(); return }
        Thread {
            val result=runCatching { WorldLogin(this).complete(uri) }
            runOnUiThread {
                if(!isFinishing) {
                    if(result.isSuccess) {
                        startActivity(Intent(this,WorldArchiveActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    Toast.makeText(this,result.fold({"Akun terhubung."},{it.localizedMessage ?: "Login gagal."}),Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }
}
