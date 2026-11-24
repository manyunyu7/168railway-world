// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.text.InputFilter
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.widget.HorizontalScrollView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import java.util.Locale

internal object StudioCityStart {
    private val scenarios = linkedMapOf(
        "jak_mulai" to "Red Line, Brown Line dan TransJakarta",
        "indo" to "Seluruh Indonesia · GAPEKA 2026",
        "jak" to "Jaringan Jabodetabek 2026 lengkap",
        "jak_2019" to "Jabodetabek 2019",
        "jak_krl" to "KRL Jabodetabek",
        "jak_tj" to "TransJakarta",
        "jak_bogor" to "Commuter Line Bogor"
    )
    private val scenarioDetails = mapOf(
        "jak_mulai" to "KRL Bogor dan Tangerang beroperasi bersama TransJakarta koridor 1–14.",
        "indo" to "KA penumpang Jawa dan Sumatera mengikuti GAPEKA 2026, bersama jaringan perkotaan Jabodetabek.",
        "jak" to "28 layanan mencakup KRL, MRT, LRT, Kalayang, dan TransJakarta.",
        "jak_2019" to "Kembangkan Jabodetabek dari jaringan yang beroperasi pada 2019.",
        "jak_krl" to "Mulai dengan sembilan line KRL Jabodetabek.",
        "jak_tj" to "Mulai dengan 14 koridor TransJakarta, lalu bangun jaringan rel pertama.",
        "jak_bogor" to "Mulai dengan Commuter Line Bogor dan Nambo."
    )

    private fun owner(activity: Activity): String? = StudioPendingSaves.user(activity)?.takeIf { account ->
        account.isNotBlank() && runCatching { StudioPendingSaves.session(activity, account) }.isSuccess
    }

    fun begin(activity: Activity, onWorldReady: (WorldDocuments.Slot) -> Unit) {
        val account = owner(activity) ?: return
        val store = StudioCatalog.get(activity)
        var city = store.city("jak")
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val regional = activity.assets.list("world-native/zone-previews").orEmpty().toSet()
        lateinit var reviewButton: android.widget.Button
        StudioUi.sheet(activity, "Bangun dunia baru",pinnedAction={ reviewButton }) { form, dialog ->
            val progress = StudioUi.text(activity,"Langkah 1 dari 3 · Pilih peta",13f,color = StudioUi.BLUE).apply {
                typeface = StudioUi.heavy(activity)
            }
            progress.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Three-segment step bar under the sheet title.
            val steps = LinearLayout(activity)
            val segments = List(3) { View(activity).also { steps.addView(it, LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (steps.childCount > 0) marginStart = dp(6) }) } }
            fun markStep(n: Int) = segments.forEachIndexed { i, v -> v.background = StudioUi.pill(activity, if (i < n) StudioUi.BLUE else 0x1f22204a, 3) }
            markStep(1)
            form.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            form.addView(progress)
            fun page() = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                form.addView(this,LinearLayout.LayoutParams(-1,-2))
            }
            val mapPage = page()
            val settingsPage = page().apply { visibility = View.GONE }
            var section = mapPage
            fun row(view: View, gap: Int = 6) =
                section.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
            fun label(text: String) = row(StudioUi.title(activity, text, 19f), 14)
            fun choices() = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }
            fun option(group: RadioGroup, title: String) = RadioButton(activity).apply {
                id = View.generateViewId()
                text = title
                textSize = 14f
                typeface = StudioUi.face(activity,true)
                setTextColor(StudioUi.INK)
                buttonTintList = ColorStateList.valueOf(StudioUi.BLUE)
                minHeight = dp(54)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked),KScene.choiceBackground(activity,true))
                    addState(intArrayOf(),KScene.choiceBackground(activity,false))
                }
                group.addView(this,RadioGroup.LayoutParams(0,-2,1f).apply {
                    if (group.childCount > 0) marginStart = dp(6)
                })
            }

            val destination = FrameLayout(activity)
            row(destination,8)
            val quickCities = LinearLayout(activity)
            val shortcuts = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(quickCities)
            }
            row(shortcuts,4)
            label("Jaringan awal")
            val network = choices()
            val empty = option(network,"Peta kosong")
            val preset = option(network,"Jaringan contoh")
            row(network,4)
            var scenario = scenarios.keys.first()
            val scenarioRow = FrameLayout(activity)
            row(scenarioRow,4)
            val preview = FrameLayout(activity)
            row(preview,4)
            val originHint = StudioUi.text(activity,"",13f,true,StudioUi.MUTED)
            row(originHint,0)

            fun updateNetwork() {
                val showScenarios = city.id == "jak" && preset.isChecked
                scenarioRow.visibility = if (showScenarios) View.VISIBLE else View.GONE
                scenarioRow.removeAllViews()
                if (showScenarios) {
                    scenarioRow.addView(KScene.choice(activity,StudioUi.Icon.TRAIN,StudioUi.TINT_PURPLE,scenarios.getValue(scenario),"Ketuk untuk ganti skenario",true) {
                        StudioUi.sheet(activity, "Pilih skenario Jakarta") { content, picker ->
                            scenarios.forEach { (id, title) ->
                                val item = StudioMapPreview.card(activity,StudioMapPreview.scenarioPath(id),title,
                                    scenarioDetails.getValue(id),id == scenario) {
                                    scenario = id
                                    picker.dismiss()
                                    updateNetwork()
                                }
                                content.addView(item,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(7) })
                            }
                        }
                    })
                }
                preview.removeAllViews()
                if (showScenarios) {
                    StudioMapPreview.image(activity,StudioMapPreview.scenarioPath(scenario),"Cuplikan peta "+scenarios.getValue(scenario))?.let { preview.addView(it,FrameLayout.LayoutParams(-1,-2)) }
                } else if ("${city.id}.webp" in regional) {
                    StudioMapPreview.image(activity,StudioMapPreview.cityPath(city.id),"Peta wilayah ${city.name}")?.let { preview.addView(it,FrameLayout.LayoutParams(-1,-2)) }
                }
                preview.visibility = if (preview.childCount == 0) View.GONE else View.VISIBLE
                originHint.text = when {
                    showScenarios -> scenarioDetails.getValue(scenario)
                    preset.isChecked -> "Line dan stasiun sudah tersedia."
                    else -> "Bangun stasiun dan line pertama sendiri."
                }
            }
            network.setOnCheckedChangeListener { _, _ -> updateNetwork() }
            lateinit var name: EditText
            fun updateCity() {
                destination.removeAllViews()
                val download = if (store.cached(city.id)) "Peta tersedia di perangkat"
                    else "Unduhan %.1f MB".format(Locale.getDefault(),city.bytes / 1_000_000.0)
                destination.addView(KScene.choice(activity,StudioUi.Icon.PIN,StudioUi.TINT_ORANGE,city.name,"Ubah kota awal · $download",true) {
                    StudioZonePicker.show(activity,store,city.id,"Pilih kota awal") { id ->
                        city=store.city(id)
                        updateCity()
                        name.hint = "Lintas ${city.name}"
                    }
                })
                quickCities.removeAllViews()
                for (id in StudioZonePicker.featured) {
                    val place = store.city(id)
                    val selected = id == city.id
                    val chip = StudioUi.button(activity,place.name,if(selected) StudioUi.Tone.BLUE else StudioUi.Tone.SURFACE) {
                        if (city.id != id) {
                            city = place
                            updateCity()
                            name.hint = "Lintas ${city.name}"
                        }
                    }.apply {
                        isSelected=selected
                        contentDescription="Pilih "+place.name
                        setPadding(dp(12),dp(4),dp(12),dp(4))
                        minHeight=dp(48)
                    }
                    quickCities.addView(chip, LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(5) })
                }
                val hasPresets = city.hasNetwork || city.id == "jak"
                preset.visibility = if (hasPresets) View.VISIBLE else View.GONE
                val requested = if (city.id == "jak") preset.id else empty.id
                if (network.checkedRadioButtonId == requested) updateNetwork() else network.check(requested)
            }
            updateCity()
            section = settingsPage
            label("Mode permainan")
            val modes = choices()
            val normal = option(modes,"Normal")
            val sandbox = option(modes,"Sandbox")
            val modeHint = StudioUi.text(activity,"",13f,true,StudioUi.MUTED)
            modes.setOnCheckedChangeListener { _, _ ->
                modeHint.text = if (sandbox.isChecked) "Bangun bebas dengan dana tanpa batas." else "Kelola layanan dan anggaran kota."
            }
            normal.isChecked = true
            row(modes,4)
            row(modeHint,2)
            label("Nama dunia")
            name = EditText(activity).apply {
                setSingleLine()
                typeface = StudioUi.face(activity)
                textSize = 16f
                setTextColor(StudioUi.INK)
                setHintTextColor(StudioUi.MUTED)
                hint = "Lintas ${city.name}"
                filters = arrayOf(InputFilter.LengthFilter(100))
                minHeight = dp(54)
                background = StudioUi.pill(activity,StudioUi.WHITE,27)
                setPadding(dp(18),dp(5),dp(18),dp(5))
            }
            row(name,3)
            lateinit var currency: android.widget.Button
            var displayCurrency = StudioCurrency.current(activity)
            fun currencyLabel() = "Mata uang · ${displayCurrency.code}"
            currency = StudioUi.button(activity,currencyLabel()) {
                StudioCurrency.choose(activity,displayCurrency) { picked ->
                    displayCurrency = picked
                    currency.text = currencyLabel()
                }
            }
            row(currency,8)
            fun displaySettings(show: Boolean) {
                mapPage.visibility = if (show) View.GONE else View.VISIBLE
                settingsPage.visibility = if (show) View.VISIBLE else View.GONE
                progress.text = if (show) "Langkah 2 dari 3 · Atur permainan" else "Langkah 1 dari 3 · Pilih peta"
                markStep(if (show) 2 else 1)
                reviewButton.text = if (show) "Tinjau dunia" else "Lanjut"
                form.requestFocus()
                (form.parent as? android.widget.ScrollView)?.scrollTo(0,0)
            }
            row(StudioUi.button(activity,"Kembali ke peta") { displaySettings(false) })
            dialog.setOnKeyListener { _, key, event ->
                if (key == android.view.KeyEvent.KEYCODE_BACK && settingsPage.visibility == View.VISIBLE) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) displaySettings(false)
                    true
                } else false
            }
            reviewButton = StudioUi.button(activity, "Lanjut", StudioUi.Tone.PRIMARY) {
                if (mapPage.visibility == View.VISIBLE) {
                    displaySettings(true)
                    return@button
                }
                val title = name.text.toString().trim().ifBlank { "Lintas ${city.name}" }
                val populated = preset.isChecked
                val free = sandbox.isChecked
                val selected = scenario.takeIf { city.id == "jak" && populated }
                StudioUi.sheet(activity,"Langkah 3 dari 3 · Tinjau dunia") { review, confirmation ->
                    fun add(view:View,gap:Int=8) = review.addView(view,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(gap) })
                    val path = selected?.let(StudioMapPreview::scenarioPath) ?: StudioMapPreview.cityPath(city.id)
                    StudioMapPreview.image(activity,path,"Pratinjau "+(selected?.let(scenarios::getValue) ?: city.name))?.let { add(it) }
                    add(StudioUi.title(activity,title,26f))
                    val mode = if(free) "Sandbox" else "Normal"
                    val facts = LinearLayout(activity)
                    for ((value, tint, ink) in listOf(Triple(city.name,StudioUi.TINT_ORANGE,0xff9a3a0b.toInt()),
                            Triple(mode,StudioUi.TINT_PURPLE,0xff3f2aa8.toInt()),Triple(displayCurrency.code,StudioUi.TINT_YELLOW,0xff7a5600.toInt())))
                        facts.addView(StudioUi.chip(activity,value,tint,ink),LinearLayout.LayoutParams(-2,-2).apply { marginEnd = dp(6) })
                    add(facts,6)
                    add(KScene.choice(activity,StudioUi.Icon.TRAIN,StudioUi.TINT_PURPLE,
                        selected?.let(scenarios::getValue) ?: if(populated) "Jaringan contoh" else "Peta kosong",originHint.text.toString(),true) {},10)
                    add(StudioUi.button(activity,"Mulai petualangan",StudioUi.Tone.PRIMARY,StudioUi.Icon.PLAY) {
                        confirmation.dismiss()
                        dialog.dismiss()
                        prepare(activity,store,city,populated,free,title,account,selected) { slot ->
                            StudioCurrency.select(activity,displayCurrency.code)
                            onWorldReady(slot)
                        }
                    },14)
                    add(StudioUi.button(activity,"Ubah pilihan") { confirmation.dismiss() },4)
                }
            }.apply { minHeight = dp(56); textSize = 17f }
        }
    }

    private fun prepare(
        activity: Activity, store: CityPackStore, city: CityPackStore.City, populated: Boolean, sandbox: Boolean,
        title: String, account: String, scenario: String?, onWorldReady: (WorldDocuments.Slot) -> Unit
    ) {
        lateinit var worker: Thread
        val meter = KScene.meter(activity).apply { isIndeterminate = true }
        val (mapStep, markMap) = KScene.step(activity, "Data peta kota")
        val (networkStep, markNetwork) = KScene.step(activity, "Jaringan awal")
        val (worldStep, markWorld) = KScene.step(activity, "Dunia siap dimainkan")
        markMap(if (store.cached(city.id)) 0 else 1)
        markNetwork(if (store.cached(city.id)) 1 else 2)
        val preparation = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(KScene.Island(context), LinearLayout.LayoutParams(-1, StudioUi.dp(activity,150)))
            addView(StudioUi.title(context,title,22f).apply { gravity = android.view.Gravity.CENTER })
            addView(meter,LinearLayout.LayoutParams(-1,StudioUi.dp(activity,14)).apply { topMargin=StudioUi.dp(activity,12); bottomMargin=StudioUi.dp(activity,8) })
            addView(mapStep); addView(networkStep); addView(worldStep)
            addView(StudioUi.text(context,"Tip: tahan stasiun untuk melihat penumpang yang menunggu.",13f,true,0xff7a5600.toInt()).apply {
                background = StudioUi.pill(context, StudioUi.TINT_YELLOW, 18)
                setPadding(StudioUi.dp(activity,12),StudioUi.dp(activity,10),StudioUi.dp(activity,12),StudioUi.dp(activity,10))
            }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = StudioUi.dp(activity,10) })
        }
        val progress = StudioDialogBuilder(activity).setTitle("Menyiapkan ${city.name}")
            .setMessage("Membuka peta…").setView(preparation).setNegativeButton("Batal") { _, _ -> worker.interrupt() }.create()
        progress.setOnCancelListener { worker.interrupt() }
        progress.setCanceledOnTouchOutside(false)
        worker = Thread({
            val result = runCatching {
                var previous = -1
                val pack = try {
                    store.load(city.id) { done, total ->
                        val percent = (done * 100 / total).toInt()
                        if (percent != previous) {
                            previous = percent
                            activity.runOnUiThread {
                                if (progress.isShowing && !activity.isDestroyed) {
                                    meter.isIndeterminate = false
                                    meter.progress = percent
                                    progress.setMessage("Mengunduh peta · $percent%")
                                    if (percent >= 100) { markMap(0); markNetwork(1) }
                                }
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    if (city.id != "nyc" || Thread.currentThread().isInterrupted) throw e
                    null
                }
                val source = if (scenario != null) {
                    require(city.id == "jak" && scenario in scenarios && populated)
                    activity.assets.open("world-native/scenarios/$scenario.save").use { it.readBytes() }
                } else initialSave(city.id, pack, populated) {
                    activity.assets.open("world-native/nyc.save").use { it.readBytes() }
                }
                activity.runOnUiThread { markMap(0); markNetwork(0); markWorld(1) }
                StudioNewGame.prepare(source, city.id, sandbox)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !progress.isShowing || worker.isInterrupted) return@runOnUiThread
                progress.dismiss()
                if (owner(activity) != account) return@runOnUiThread
                result.mapCatching { WorldDocuments.import(activity, it, title, city.id, account) }
                    .mapCatching(onWorldReady)
                    .onFailure { error ->
                        StudioDialogBuilder(activity).setTitle("Dunia belum bisa dibuka").setMessage(error.localizedMessage)
                            .setNegativeButton("Tutup", null).setPositiveButton("Coba lagi") { _, _ ->
                                if (owner(activity) == account) prepare(activity, store, city, populated, sandbox, title, account, scenario, onWorldReady)
                            }.show()
                    }
            }
        }, "studio-city-start")
        progress.show()
        worker.start()
    }

    fun initialSave(city: String, pack: CityPackStore.CityPack?, populated: Boolean, bundledNyc: () -> ByteArray): ByteArray {
        require(pack?.city?.id == city || (pack == null && city == "nyc"))
        if (populated) return if (pack == null) bundledNyc() else checkNotNull(pack.network).readBytes()
        val handle = if (pack == null) Engine.workshop() else CitySurface.empty(city, pack.header.readText())
        check(handle != 0L) { "Peta kota gagal dibuka." }
        return try { Engine.save(handle, 111600.0) } finally { Engine.close(handle) }
    }
}
