// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class StudioLibraryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val links by lazy { getSharedPreferences("studio-host-worlds", MODE_PRIVATE) }
    private val cities by lazy { StudioCatalog.get(this).cities().toList() }
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var owner: String
    private var operation = 0
    private var locked = false
    private var allLocal = false
    private var requestedAction: String? = null
    private val source get() = intent.getStringExtra("archiveSource")
    private fun user() = StudioPendingSaves.user(this)
    private fun session() = StudioPendingSaves.session(this,owner)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        owner = state?.getString("owner") ?: user().orEmpty()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            body.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom)
                insets
            }
        }
        window.statusBarColor = 0xff9fd8f7.toInt()
        window.navigationBarColor = 0xffbfe6a8.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        setContentView(FrameLayout(this).apply {
            addView(KScene.Sky(context), FrameLayout.LayoutParams(-1, -1))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) }, FrameLayout.LayoutParams(-1, -1))
        })
        requestedAction = if (state == null) intent.getStringExtra("studioAction") else null
        if (owner.isBlank() || owner != user()) {
            setResult(RESULT_CANCELED, Intent().putExtra("studioError","Masuk kembali ke akun 168Railway untuk membuka koleksi dunia."))
            finish()
            return
        }
        StudioPendingSaves.schedule(this)
        refresh()
    }
    override fun onRestart() {
        super.onRestart()
        if (user() == owner) refresh() else finish()
    }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("owner", owner) }
    private fun action(
        label: String,
        icon: StudioUi.Icon? = null,
        tone: StudioUi.Tone = StudioUi.Tone.SURFACE,
        click: () -> Unit
    ): Button {
        val button = StudioUi.button(this,label,tone,icon) { if (!locked) click() }
        button.minHeight = dp(54)
        body.addView(button,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
        return button
    }
    private fun <T> runJob(message: String, task: () -> T, allowNavigation: Boolean = false, failed: ((Throwable) -> Unit)? = null, done: (T) -> Unit) {
        val sequence = ++operation
        locked = !allowNavigation
        if (!allowNavigation) for (i in 0 until body.childCount) body.getChildAt(i).isEnabled = false
        status.text = message
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing || operation != sequence) return@runOnUiThread
                locked = false
                for (i in 0 until body.childCount) body.getChildAt(i).isEnabled = true
                if (owner != user()) { finish(); return@runOnUiThread }
                result.onSuccess(done).onFailure { status.text = it.localizedMessage ?: "Coba lagi."; failed?.invoke(it) }
            }
        }
    }
    private fun refresh() {
        body.removeAllViews()
        fun append(view: View, gap: Int = 0, height: Int = -2) =
            body.addView(view,LinearLayout.LayoutParams(-1,if (height < 0) height else dp(height)).apply { topMargin = dp(gap) })
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val heading = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(context,"168Railway Studio",13f,true,StudioUi.MUTED))
                addView(StudioUi.title(context,"Duniaku",32f).apply { includeFontPadding = false })
            }
            addView(KScene.glassIcon(context,StudioUi.Icon.BACK,"Kembali") { if (!locked) finish() },
                LinearLayout.LayoutParams(dp(50),dp(50)))
            addView(heading, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(12) })
            addView(KScene.glassIcon(context,StudioUi.Icon.CLOUD,"Muat ulang daftar akun") { if (!locked) refresh() },
                LinearLayout.LayoutParams(dp(50),dp(50)))
        }
        append(toolbar)
        status = StudioUi.text(this,"",14f,true,StudioUi.MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val mine = WorldDocuments.list(this).mapNotNull { slot ->
            val link = links.getString(slot.id,null)?.let(::JSONObject)
            if (link?.optString("owner") == owner) slot to link else null
        }
        val active = mine.firstOrNull { it.first.id == source }
        val covers = mine.associate { (slot, _) -> slot.id to WorldCovers.read(this, slot.id) }
        fun createWorld() {
            StudioCityStart.begin(this) { slot ->
                remember(slot.id,JSONObject().put("id",UUID.randomUUID().toString()).put("version",0).put("name",slot.title))
                StudioPendingSaves.markDirty(this,slot.id)
                open(slot)
            }
        }
        val pending = StudioPendingSaves.pendingIds(this)
        fun syncLabel(slot: WorldDocuments.Slot, link: JSONObject): String {
            val revision = link.getInt("version")
            return when {
                StudioPendingSaves.conflicted(this,slot.id) -> "Ada perubahan di HP dan web"
                StudioPendingSaves.failure(this,slot.id) != null -> "Belum tersinkron"
                slot.id in pending -> "Menunggu sinkronisasi"
                revision == 0 -> "Belum diunggah"
                else -> "Tersinkron · versi $revision"
            }
        }
        fun details(slot: WorldDocuments.Slot, link: JSONObject) =
            listOfNotNull(cities.firstOrNull { it.id == slot.city }?.name, syncLabel(slot,link)).joinToString(" · ")
        // Hero: the world just played (or the newest), a floating island over a glass card.
        val hero = active ?: mine.firstOrNull()
        if (hero != null) {
            val (slot, link) = hero
            val cover = covers[slot.id]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = StudioUi.glass(context, 30)
                elevation = dp(3).toFloat()
                setPadding(dp(12),dp(12),dp(12),dp(16))
            }
            if (cover?.image != null) card.addView(KScene.mapCover(this,cover.image,22),LinearLayout.LayoutParams(-1,dp(300)))
            else append(KScene.Island(this),4,190)
            val heading = LinearLayout(this).apply {
                gravity = Gravity.BOTTOM
                setPadding(dp(6),dp(12),dp(6),0)
                addView(StudioUi.title(context,slot.title,26f).apply { maxLines = 2 },LinearLayout.LayoutParams(0,-2,1f))
                cover?.let { addView(StudioUi.text(context,it.dayTime,13f,true,StudioUi.MUTED).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(8); bottomMargin = dp(4) }) }
            }
            card.addView(heading)
            // The save time is a sentence, not a chip: it was cut off at the edge of the chip row.
            if (cover != null) card.addView(StudioUi.text(this,WorldDocuments.savedWhen(slot.at),13f,false,StudioUi.MUTED).apply {
                setPadding(dp(6),dp(2),dp(6),0)
            })
            val chips = LinearLayout(this).apply { setPadding(dp(6),dp(8),dp(6),dp(12)) }
            fun chip(text: String, tint: Int, ink: Int) =
                chips.addView(StudioUi.chip(this,text,tint,ink),LinearLayout.LayoutParams(-2,-2).apply { marginEnd = dp(6) })
            if (cover != null) {
                chip("${cover.lines} lini",StudioUi.TINT_ORANGE,0xff9a3a0b.toInt())
                chip("${cover.stations} stasiun",StudioUi.TINT_PURPLE,0xff3f2aa8.toInt())
                // web savesPanel.cash: the cash when saved, as the money tab writes it
                if (cover.cash.isFinite()) chip(StudioFormat.cash(cover.cash * 1e6, StudioCurrency.current(this)),StudioUi.TINT_YELLOW,0xff7a5600.toInt())
            } else cities.firstOrNull { it.id == slot.city }?.name?.let { chip(it,StudioUi.TINT_ORANGE,0xff9a3a0b.toInt()) }
            // web savesPanel.playing: the world open in the game is marked
            if (active != null) chip("Dimainkan",StudioUi.TINT_GREEN,StudioUi.GOOD)
            val sync = syncLabel(slot,link)
            if (sync.startsWith("Tersinkron")) chip(sync,StudioUi.TINT_GREEN,StudioUi.GOOD)
            else if (cover == null) chip(sync,StudioUi.TINT_PURPLE,0xff3f2aa8.toInt())
            card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            card.addView(StudioUi.button(this,"Main lagi",StudioUi.Tone.PRIMARY,StudioUi.Icon.PLAY) {
                if (!locked) confirmPlay(slot.title,slot.city,details(slot,link)) { open(slot) }
            }.apply { minHeight = dp(56); textSize = 17f },LinearLayout.LayoutParams(-1,-2).apply { marginStart = dp(6); marginEnd = dp(6) })
            // Deleting is rare and cannot be undone: a quiet text action, not a second big button beside "Main lagi".
            if (active == null) card.addView(StudioUi.text(this,"Hapus dari HP ini",14f,true,StudioUi.BAD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                isClickable = true
                background = android.util.TypedValue().let { tv ->
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground,tv,true); context.getDrawable(tv.resourceId) }
                setOnClickListener { if (!locked) WorldDeletion.ask(this@StudioLibraryActivity,worker,slot) { refresh() } }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
            if (active != null) {
                card.addView(StudioUi.button(this,"Simpan perubahan ke Studio",StudioUi.Tone.BLUE,StudioUi.Icon.SAVE) { if (!locked) upload(active.first, false) },
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
                card.addView(StudioUi.button(this,"Simpan sebagai dunia baru",icon = StudioUi.Icon.FOLDER) { if (!locked) upload(active.first, true) },
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
            }
            append(card,0)
        } else {
            append(KScene.Island(this),8,200)
            append(StudioUi.text(this,"Belum ada dunia tersimpan di HP ini. Bangun pulau pertamamu atau pilih simpanan akun di bawah.",15f,true,StudioUi.MUTED).apply {
                gravity = Gravity.CENTER
            },4)
        }
        // Grid of the other islands on this phone, then the dashed "Pulau baru" card.
        val others = mine.filter { it.first.id != hero?.first?.id }
        val shown = if (allLocal) others else others.take(3)
        val tiles = shown.map { (slot, link) ->
            val cover = covers[slot.id]
            // web SlotFacts: lines and when it was saved
            val detail = cover?.let { "${it.lines} lini · " + WorldDocuments.savedWhen(slot.at) } ?: details(slot,link)
            (KScene.worldCard(this,slot.title,detail,0xffb5e08f.toInt(),cover?.image) {
                if (!locked) confirmPlay(slot.title,slot.city,details(slot,link)) { open(slot) }
            } as LinearLayout).apply {
                // web SavesPanel: a bin on every card but the game being played
                if (slot.id != source) addView(StudioUi.iconButton(this@StudioLibraryActivity,"Hapus ${slot.title} dari HP ini",StudioUi.Icon.CLOSE) {
                    if (!locked) WorldDeletion.ask(this@StudioLibraryActivity,worker,slot) { refresh() }
                }, LinearLayout.LayoutParams(dp(44),dp(44)).apply { gravity = Gravity.END })
            } as View
        } + KScene.newIsland(this,"Dunia baru") { if (!locked) createWorld() }
        if (others.isNotEmpty()) append(StudioUi.section(this,"Di perangkat · ${mine.size}"),12)
        for (pair in tiles.chunked(2)) {
            val line = LinearLayout(this).apply { minimumHeight = dp(150) }
            pair.forEachIndexed { i, tile ->
                line.addView(tile, LinearLayout.LayoutParams(0,-1,1f).apply { if (i == 1) marginStart = dp(12) })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0,1,1f).apply { marginStart = dp(12) })
            append(line,12)
        }
        if (others.size > 3) action(if (allLocal) "Tampilkan lebih sedikit" else "Lihat semua dunia di perangkat") {
            allLocal = !allLocal
            refresh()
        }
        action("Kelola arsip di HP",StudioUi.Icon.FOLDER) {
            startActivity(Intent(this, WorldArchiveActivity::class.java)
                .putExtra("localOnly",true).putExtra("archiveSource",source))
        }
        val accountHeader = StudioUi.section(this,"Dunia di akun")
        append(accountHeader,20)
        append(status,3)
        if (requestedAction == "save" && active != null) {
            requestedAction = null
            upload(active.first,false)
            return
        }
        runJob("Mengambil dunia dari Studio…", { WorldCloud.list(session()) }, allowNavigation = true,
            failed = { error -> if (error is WorldCloud.Unavailable) { accountHeader.visibility = View.GONE; status.visibility = View.GONE } }) { rows ->
            status.text = if (rows.length() == 0) "Belum ada dunia di akun Studio." else ""
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val card = StudioUi.row(this,StudioUi.Icon.CLOUD,StudioUi.TINT_BLUE,row.getString("name"),"Simpanan akun · ${saveDetails(row)}") {
                    if (locked) return@row
                    val local = mine.firstOrNull { (_, link) ->
                        link.getString("id") == row.getString("id") && link.getInt("version") == row.getInt("version")
                    }
                    confirmPlay(row.getString("name"),local?.first?.city,"Simpanan akun · ${saveDetails(row)}") { if (local == null) download(row) else open(local.first) }
                }
                append(card,7)
            }
        }
    }
    private fun confirmPlay(title: String, cityId: String?, detail: String, play: () -> Unit) {
        val city = cities.firstOrNull { it.id == cityId }
        lateinit var go: Button
        StudioUi.sheet(this,"Lanjutkan permainan?",pinnedAction={ go }) { content, dialog ->
            val map = city?.let { StudioMapPreview.image(this,StudioMapPreview.cityPath(it.id),"Peta wilayah "+it.name) }
            if (map != null) {
                content.addView(map,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
                content.addView(StudioUi.title(this,title,26f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
            } else {
                content.addView(KScene.Island(this),LinearLayout.LayoutParams(-1,dp(150)).apply { topMargin=dp(4) })
                content.addView(StudioUi.title(this,title,26f))
            }
            content.addView(StudioUi.text(this,detail,15f,true,StudioUi.MUTED))
            content.addView(StudioUi.button(this,"Kembali") { dialog.dismiss() },
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
            go = StudioUi.button(this,"Main lagi",StudioUi.Tone.PRIMARY,StudioUi.Icon.PLAY) {
                dialog.dismiss()
                if (!locked && user() == owner) play()
            }
        }
    }
    private fun download(row: JSONObject) {
        runJob("Mengunduh dunia…", {
            val account = session()
            val id = row.getString("id")
            val bytes = WorldCloud.read(account,id,row.getInt("version"))
            val header = WorldDocuments.header(bytes)
            Triple(bytes,header,cities)
        }) { (bytes,header,catalog) ->
            fun install(city: String) {
                runJob("Membuka jaringan…", {
                    session()
                    val slot = WorldDocuments.import(this,bytes,row.getString("name"),city,owner)
                    remember(slot.id,row,StudioPendingSaves.hash(bytes))
                    slot
                }, done = ::open)
            }
            val city = WorldDocuments.startingCity(header,catalog)
            if (city != null) install(city)
            else StudioZonePicker.show(this,StudioCatalog.get(this),"","Pilih peta dunia ini",select=::install)
        }
    }
    private fun remember(slot: String, row: JSONObject, hash: String? = null) {
        val link = JSONObject().put("owner",owner).put("id",row.getString("id")).put("version",row.getInt("version")).put("name",row.optString("name"))
        if (hash != null) link.put("hash",hash)
        check(links.edit().putString(slot,link.toString()).commit())
    }
    private fun open(slot: WorldDocuments.Slot) {
        startActivityForResult(Intent(this,NativeWorldActivity::class.java).putExtra("archiveSlot",slot.id).putExtra("hostLibrary",true),168)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 168 && resultCode == RESULT_OK) {
            setResult(RESULT_OK,data)
            finish()
        }
    }
    private fun upload(slot: WorldDocuments.Slot, copy: Boolean) {
        runJob("Menyimpan jaringan…", {
            StudioPendingSaves.upload(this,slot.id,owner,slot.title,copy)
        }) {
            Toast.makeText(this,"Dunia tersimpan di Studio",Toast.LENGTH_SHORT).show()
            if (intent.getStringExtra("studioAction") == "save") finish() else refresh()
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
