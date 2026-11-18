// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
    private val store by lazy { CityPackStore(filesDir, assets.open("world-native/cities.tsv").bufferedReader()) }
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
        val cities=store.cities().toList()
        val h=WorldDocuments.header(bytes)
        val selected=h.optString("nativeArchiveCity").ifBlank { intent.getStringExtra("city") ?: h.optString("city") }
        var pick=cities.indexOfFirst { it.id==selected }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Peta untuk $name")
            .setSingleChoiceItems(cities.map { it.name }.toTypedArray(),pick) { _,i -> pick=i }
            .setPositiveButton("Buat slot baru") { _,_ ->
                job({ WorldDocuments.import(this,bytes,name,cities[pick].id) }) { open(it) }
            }.setNegativeButton("Batal",null).show()
    }
    private fun open(slot: WorldDocuments.Slot) {
        startActivity(Intent(this,NativeWorldActivity::class.java).putExtra("archiveSlot",slot.id))
    }
    private fun chooseRemote(session: JSONObject, write: Boolean) {
        job({ WorldCloud.list(session) }) { rows ->
            val records=List(rows.length()) { rows.getJSONObject(it) }
            val labels=records.map { "${it.getString("name")} · v${it.getInt("version")}" }.toMutableList()
            if(write) labels.add(0,"Buat arsip baru")
            if(labels.isEmpty()) { feedback.text="Akun ini belum punya arsip."; return@job }
            AlertDialog.Builder(this).setTitle(if(write) "Simpan ke akun" else "Pulihkan ke slot baru")
                .setItems(labels.toTypedArray()) { _, i ->
                    if(write && i==0) {
                        val name=EditText(this).apply { hint="Nama arsip"; setSingleLine(true) }
                        AlertDialog.Builder(this).setTitle("Arsip baru").setView(name).setPositiveButton("Simpan") { _,_ ->
                            val label=name.text.toString().trim().take(100)
                            if(label.isNotEmpty()) upload(session,UUID.randomUUID().toString(),0,label)
                            else feedback.text="Isi nama arsip."
                        }.setNegativeButton("Batal",null).show()
                    } else {
                        val row=records[if(write)i-1 else i]
                        val id=row.getString("id"); val title=row.getString("name")
                        if(write) {
                            AlertDialog.Builder(this).setTitle("Perbarui $title?")
                                .setMessage("Versi akun ${row.getInt("version")} akan diperbarui dengan dunia aktif.")
                                .setPositiveButton("Perbarui") { _,_->upload(session,id,row.getInt("version"),title) }
                                .setNegativeButton("Batal",null).show()
                        } else job({
                            WorldCloud.read(session,id).also { WorldDocuments.header(it) }
                        }) { bytes->chooseCity(bytes,title) }
                    }
                }.setNegativeButton("Batal",null).show()
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
