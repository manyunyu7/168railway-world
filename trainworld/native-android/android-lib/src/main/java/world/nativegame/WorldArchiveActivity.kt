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
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        menu=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(menu) })
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
    private fun button(label: String, enabled: Boolean=true, action: ()->Unit) {
        menu.addView(Button(this).apply { text=label; isEnabled=enabled; setOnClickListener { action() } })
    }
    private fun render() {
        menu.removeAllViews()
        menu.addView(TextView(this).apply { text="Arsip dunia"; textSize=24f })
        feedback=TextView(this); menu.addView(feedback)
        button("Impor berkas TWG1") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1)
        }
        button("Ekspor dunia aktif",source()?.isFile==true) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"${intent.getStringExtra("city") ?: "dunia"}.save"),2)
        }
        val session=login.get("session")
        if(session==null) button("Hubungkan akun 168Railway") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW,login.start())) }.onFailure { feedback.text=it.localizedMessage }
        } else {
            menu.addView(TextView(this).apply { text="Akun 168Railway · ${session.getString("userId")}" })
            button("Pulihkan dari akun") { chooseRemote(session,false) }
            button("Simpan ke akun",source()?.isFile==true) { chooseRemote(session,true) }
            button("Putuskan akun") { job({ try { WorldCloud.logout(session) } finally { login.forget() } }) { render() } }
        }
        WorldDocuments.list(this).forEach { slot ->
            button("Buka ${slot.title} · ${slot.city.uppercase()}") { open(slot) }
        }
        button("Kembali") { finish() }
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
    }
    private fun chooseCity(bytes: ByteArray, name: String) {
        val city = WorldDocuments.startingCity(WorldDocuments.header(bytes),store.cities().toList())
            ?: intent.getStringExtra("city").orEmpty()
        StudioZonePicker.show(this,store,city,"Peta untuk $name",allowCurrent=true) { id ->
            val place = store.city(id)
            val content = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                StudioMapPreview.image(context,StudioMapPreview.cityPath(id),"Peta wilayah "+place.name)?.let { addView(it) }
                addView(StudioUi.text(context,name,19f,true,StudioDepot.TEAL))
                addView(StudioUi.text(context,place.name+" · "+Formatter.formatShortFileSize(context,bytes.size.toLong()),15f,color=StudioDepot.SECONDARY))
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
                    val name=EditText(this).apply { hint="Nama arsip"; setSingleLine(true); filters=arrayOf(android.text.InputFilter.LengthFilter(100)) }
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
                if (records.isEmpty()) add(StudioUi.text(this,"Belum ada dunia tersimpan di akun ini.",15f,color=StudioDepot.SECONDARY))
                for(row in records) {
                    val id=row.getString("id")
                    val title=row.getString("name")
                    val detail=saveDetails(row)
                    add(StudioDepot.ticket(this,title,detail,StudioUi.Icon.CLOUD) {
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
