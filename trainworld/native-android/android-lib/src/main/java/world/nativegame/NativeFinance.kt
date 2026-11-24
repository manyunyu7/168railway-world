package world.nativegame

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** Engine calls use the same UI-thread ownership as track editing. */
object NativeFinance {
    @JvmStatic external fun fares(handle: Long, now: Double, patch: String): String
    @JvmStatic private external fun query(handle: Long, now: Double, op: Int): String

    internal fun balance(handle: Long, clock: Double): Double =
        JSONObject(query(handle, clock, 0)).optDouble("cash")

    private fun editFares(activity: Activity, handle: Long, clock: () -> Double, transaction: PanelEdit, persist: () -> Unit) {
        val state = JSONObject(fares(handle,clock(),"{}"))
        val initial = state.optJSONObject("values") ?: return
        val currency = StudioCurrency.current(activity)
        val prices = setOf("base","perKm","transfer")
        val labels = linkedMapOf("base" to "Tarif dasar (${currency.code})", "perKm" to "Tarif per km (${currency.code})",
            "transfer" to "Biaya transit (${currency.code})", "hsr" to "Pengali kereta cepat", "bus" to "Pengali bus", "ferry" to "Pengali feri")
        StudioUi.sheet(activity,"Tarif penumpang") { column, dialog ->
            val preview = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            column.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,8); bottomMargin=StudioUi.dp(activity,8) })
            column.addView(StudioUi.section(activity,"Harga"))
            val inputs = labels.mapValues { (key,label) ->
                if (key == "hsr") column.addView(StudioUi.section(activity,"Pengali line"))
                column.addView(StudioUi.text(activity,label,13f,true).apply { setPadding(StudioUi.dp(activity,2),0,0,StudioUi.dp(activity,2)) })
                android.widget.EditText(activity).apply {
                    setSingleLine()
                    contentDescription = label
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(java.math.BigDecimal.valueOf(initial.getDouble(key)).multiply(java.math.BigDecimal.valueOf(if (key in prices) currency.rate else 1.0)).stripTrailingZeros().toPlainString())
                    StudioUi.field(this)
                    column.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=StudioUi.dp(activity,4) })
                    // Slider for the three prices: drag to explore, the field keeps the exact value.
                    if (key in prices) {
                        val now = initial.getDouble(key)*currency.rate
                        // Step scales with the currency so IDR and USD both get ~300 notches.
                        val scale = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(maxOf(now, currency.rate*0.01)))).coerceAtLeast(0.01)
                        val step = scale/10
                        val top = maxOf(now*3, scale*10)
                        val field = this
                        var syncing = false
                        val seek = android.widget.SeekBar(activity).apply {
                            max = (top/step).toInt().coerceAtLeast(10)
                            progress = (now/step).toInt().coerceIn(0,max)
                            contentDescription = label
                            StudioUi.styleSeek(this, if (key=="base") StudioUi.GREEN else if (key=="perKm") StudioUi.BLUE else StudioUi.LEAF)
                            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                                    if (!fromUser) return
                                    syncing = true
                                    field.setText(java.math.BigDecimal.valueOf(p*step).stripTrailingZeros().toPlainString())
                                    syncing = false
                                }
                                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
                            })
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {}
                            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {}
                            override fun afterTextChanged(e:android.text.Editable?) {
                                if (syncing) return
                                e?.toString()?.replace(',','.')?.toDoubleOrNull()?.let { seek.progress = (it/step).toInt().coerceIn(0,seek.max) }
                            }
                        })
                        column.addView(seek,LinearLayout.LayoutParams(-1,StudioUi.dp(activity,40)).apply { bottomMargin=StudioUi.dp(activity,4) })
                    }
                }
            }
            val fmt = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply { maximumFractionDigits=2 }
            fun proposedFare() {
                preview.removeAllViews()
                val base=inputs.getValue("base").text.toString().replace(',','.').toDoubleOrNull()
                val km=inputs.getValue("perKm").text.toString().replace(',','.').toDoubleOrNull()
                val changed=base!=null && km!=null && (kotlin.math.abs(base-initial.getDouble("base")*currency.rate)>1e-9 ||
                    kotlin.math.abs(km-initial.getDouble("perKm")*currency.rate)>1e-9)
                val local = state.getJSONObject("local")
                val curve = local.optJSONArray("curve")
                fun existing(distance: Double): Double {
                    if (curve != null && curve.length()>=4) {
                        var i=2
                        while (i+2 < curve.length() && distance>curve.getDouble(i)) i+=2
                        val x0=curve.getDouble(i-2); val x1=curve.getDouble(i)
                        if (x1>x0) return currency.rate*(curve.getDouble(i-1)+(distance-x0)/(x1-x0)*(curve.getDouble(i+1)-curve.getDouble(i-1)))
                    }
                    return currency.rate*(local.getDouble("base")+distance*local.getDouble("rate"))
                }
                val card=StudioDecision(activity,if(changed) "Simulasi tarif linear" else "Tarif lokal saat ini",
                    if(changed) "Tarif acuan sebelum penyesuaian wilayah dan jenis layanan." else "Satu perjalanan tanpa transit atau pengali layanan.")
                if(base==null || km==null || !base.isFinite() || !km.isFinite() || base<0 || km<0 || base+100*km>1000*currency.rate) {
                    card.fact("Simulasi","Periksa tarif dasar dan tarif per km")
                } else {
                    val samples=listOf(5,20,100).map { it to if(changed) base+km*it else existing(it.toDouble()) }
                    val maximum=samples.maxOf { it.second }.coerceAtLeast(.0001)
                    samples.forEach { (distance,price) -> card.bar("$distance km",currency.symbol+fmt.format(price),price/maximum,if (changed) StudioUi.BLUE else StudioUi.GREEN) }
                }
                preview.addView(card)
            }
            proposedFare()
            for (key in listOf("base","perKm")) inputs.getValue(key).addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
                override fun afterTextChanged(s:android.text.Editable?)=proposedFare()
            })
            val warning = StudioUi.text(activity,"",14f,true,StudioUi.BAD)
            column.addView(warning)
            column.addView(StudioUi.button(activity,"Terapkan tarif",StudioUi.Tone.PRIMARY) {
                val patch = JSONObject()
                for ((key,field) in inputs) {
                    val value = field.text.toString().trim().replace(',','.').toDoubleOrNull()
                    if (value == null || !value.isFinite() || value < 0) {
                        field.error = "Masukkan angka nol atau lebih."
                        field.requestFocus()
                        return@button
                    }
                    val factor = if (key in prices) currency.rate else 1.0
                    if (kotlin.math.abs(value-initial.getDouble(key)*factor)>1e-9) patch.put(key,value/factor)
                }
                fun applyPatch() = transaction { now ->
                    val result = JSONObject(fares(handle,now,patch.toString()))
                    if (result.has("error")) warning.text = result.getString("error")
                    else { if (patch.length()>0) persist(); dialog.dismiss() }
                }
                if (patch.has("base") || patch.has("perKm")) {
                    StudioDialogBuilder(activity).setTitle("Gunakan tarif linear?")
                        .setView(StudioDecision(activity,"Tarif dasar baru","Tarif dasar dan tarif per km akan menentukan harga pada semua jarak.").apply {
                            fact("Naik kendaraan",currency.symbol+fmt.format(inputs.getValue("base").text.toString().replace(',','.').toDouble()))
                            fact("Setiap kilometer",currency.symbol+fmt.format(inputs.getValue("perKm").text.toString().replace(',','.').toDouble()))
                        })
                        .setNegativeButton("Kembali",null).setPositiveButton("Simpan") { _,_ -> applyPatch() }.show()
                } else applyPatch()
            })
        }
    }

    /** Web `ledgerNet` (MoneyTab.tsx): fares less running, upkeep, the loan's payment, new trains and construction. */
    internal fun ledgerNet(d: JSONObject) = d.optDouble("fares",0.0) + d.optDouble("parking",0.0) + d.optDouble("ads",0.0) -
        d.optDouble("running",0.0) - d.optDouble("upkeep",0.0) - d.optDouble("parkUpkeep",0.0) -
        d.optDouble("loan",0.0) - d.optDouble("trains",0.0) - d.optDouble("build",0.0)

    /**
     * The web's ledger table (MoneyTab.tsx): Kemarin and Hari ini side by side, Tarif, Operasi
     * kereta, Perawatan, Pinjaman (while a loan is out or was paid yesterday), Kereta baru,
     * Pembangunan and a bold Bersih; a cost shown negative, red when it shows a minus.
     */
    private fun ledgerTable(activity: Activity, body: LinearLayout, ledger: org.json.JSONArray?, today: Int, loanOut: Boolean, currency: StudioCurrency) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val days = (0 until (ledger?.length() ?: 0)).mapNotNull { ledger!!.optJSONObject(it) }
        val now = days.lastOrNull { it.optInt("day", Int.MIN_VALUE) == today }
        val prev = days.lastOrNull { it.optInt("day", Int.MIN_VALUE) == today - 1 }
        val rows = buildList<Pair<String, (JSONObject) -> Double>> {
            add("Tarif" to { d -> d.optDouble("fares",0.0) })
            // 168Railway's own: parking and adverts (WorldExtras.kt), shown once there is any
            fun any(k: String) = listOfNotNull(now, prev).any { it.optDouble(k,0.0) != 0.0 }
            if (any("parking")) add("Parkir" to { d -> d.optDouble("parking",0.0) })
            if (any("ads")) add("Iklan" to { d -> d.optDouble("ads",0.0) })
            add("Operasi kereta" to { d -> -d.optDouble("running",0.0) })
            add("Perawatan" to { d -> -d.optDouble("upkeep",0.0) })
            if (any("parkUpkeep")) add("Perawatan parkir" to { d -> -d.optDouble("parkUpkeep",0.0) })
            if (loanOut || (prev?.optDouble("loan",0.0) ?: 0.0) > 0) add("Pinjaman" to { d -> -d.optDouble("loan",0.0) })
            add("Kereta baru" to { d -> -d.optDouble("trains",0.0) })
            add("Pembangunan" to { d -> -d.optDouble("build",0.0) })
            add("Bersih" to ::ledgerNet)
        }
        val table = android.widget.TableLayout(activity).apply {
            background = StudioUi.card(activity, 14)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setColumnStretchable(0, true)
        }
        fun cell(text: String, bold: Boolean, ink: Int = StudioUi.INK, end: Boolean = true) = StudioUi.text(activity, text, 13f, bold, ink).apply {
            if (bold) typeface = StudioUi.heavy(activity)
            gravity = if (end) android.view.Gravity.END else android.view.Gravity.START
            setPadding(if (end) dp(10) else 0, dp(3), 0, dp(3))
        }
        // no yesterday yet (day 1, or a fresh ledger): the Kemarin column of dashes is left out
        val columns = listOfNotNull(prev?.let { "Kemarin" to it }, "Hari ini" to now)
        table.addView(android.widget.TableRow(activity).apply {
            addView(cell("", false, end = false)); columns.forEach { addView(cell(it.first, true, StudioUi.MUTED)) }
        })
        for ((label, value) in rows) {
            val net = label == "Bersih"
            table.addView(android.widget.TableRow(activity).apply {
                addView(cell(label, net, end = false))
                for (d in columns.map { it.second }) {
                    val v = d?.let(value)
                    addView(cell(if (v == null) "–" else StudioFormat.money(v * 1e6, currency), net,
                        if (v != null && StudioFormat.moneyNegative(v * 1e6, currency)) StudioUi.BAD else StudioUi.INK))
                }
            })
        }
        body.addView(table, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    fun show(activity: Activity, handle: Long, clock: () -> Double, transaction: PanelEdit, onCurrencyChanged: () -> Unit = {}, persist: () -> Unit) {
        if (handle == 0L) return
        val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply { maximumFractionDigits = 2 }
        fun usd(value: Double) = StudioCurrency.current(activity).millions(value)
        fun short(value: Double) = StudioCurrency.current(activity).compact(value)
        fun dp(n: Int) = StudioUi.dp(activity, n)
        StudioUi.sheet(activity, "Celengan kota") { sheet, _ ->
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            sheet.addView(body)
            fun refresh() {
                fun act(op: Int) = transaction { now ->
                    val result = JSONObject(query(handle,now,op))
                    if (result.has("error")) Toast.makeText(activity,result.getString("error"),Toast.LENGTH_LONG).show()
                    else persist()
                    refresh()
                }
                val state = JSONObject(query(handle, clock(), 0))
                val run = state.getJSONArray("operating")
                body.removeAllViews()
                val ledger = state.optJSONArray("ledger")
                val latest = ledger?.let { l -> (l.length()-1 downTo 0).firstNotNullOfOrNull { l.optJSONObject(it) } }
                // Gold piggy-bank card with the latest day's net result.
                val balance = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(12))
                    background = android.graphics.drawable.LayerDrawable(arrayOf(
                        StudioUi.panel(activity, 0xffc98500.toInt(), 18, 0xffc98500.toInt()),
                        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(0xffffd23f.toInt(), 0xffffb020.toInt())).apply { cornerRadius = dp(18).toFloat() })).apply {
                        setLayerInset(1, 0, 0, 0, dp(3))
                    }
                }
                // web MoneyTab: "Kas" to four figures, or in a sandbox "Sejak hari 1", the cash over the start
                val currency = StudioCurrency.current(activity)
                val sandbox = state.getBoolean("sandbox")
                val shown = if (sandbox) state.getDouble("cash") - state.optDouble("startCash", 10000.0) else state.getDouble("cash")
                // the currency as a small chip in the card's corner instead of a full-width button
                val top = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                top.addView(StudioUi.text(activity, (if (sandbox) "Sejak hari 1" else "Kas")+" · "+currency.code, 12f, true, 0xff7a5600.toInt()),
                    LinearLayout.LayoutParams(0,-2,1f))
                top.addView(StudioUi.choiceChip(activity, "Mata uang · "+currency.code, false) {
                    StudioCurrency.picker(activity) { onCurrencyChanged(); refresh() }
                }.apply { textSize = 12f; contentDescription = "Ganti mata uang, sekarang ${currency.code}" })
                balance.addView(top)
                balance.addView(StudioUi.title(activity, StudioFormat.cash(shown * 1e6, currency), 26f))
                balance.contentDescription = (if (sandbox) "Sejak hari 1: tarif dikurangi biaya operasi, perawatan, kereta, dan pembangunan. " else "Kas. ") +
                    "Semua arus uang dikali 100. Satu hari setara 100 hari untuk uang masuk dan keluar."
                if (latest != null) {
                    val net = ledgerNet(latest)
                    balance.addView(StudioUi.chip(activity, (if (net >= 0) "▲ +" else "▼ ")+StudioFormat.money(net * 1e6, currency).removePrefix("−")+" · Hari "+latest.optInt("day"),
                        0xb3ffffff.toInt(), if (net >= 0) StudioUi.GOOD else StudioUi.BAD),
                        LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(4) })
                }
                body.addView(balance, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(2) })
                fun row(title: String, value: String, icon: StudioUi.Icon? = null, tint: Int = StudioUi.TINT_GREY, ink: Int = StudioUi.INK) {
                    body.addView(StudioUi.row(activity, icon, tint, title, null,
                        StudioUi.text(activity,value,14f,color = ink).apply { typeface = StudioUi.heavy(activity) }),
                        LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                }
                fun heading(label: String) {
                    body.addView(StudioUi.section(activity,label),LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(2) })
                }
                ledgerTable(activity, body, ledger, state.optInt("today", latest?.optInt("day") ?: 0), state.optDouble("loan", 0.0) > 0, currency)
                body.addView(StudioUi.button(activity,"Atur tarif penumpang",StudioUi.Tone.PRIMARY,StudioUi.Icon.WALLET) {
                    editFares(activity,handle,clock,transaction,persist)
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
                // web MoneyHistory, after the fares as the money tab lays it out
                if ((ledger?.length() ?: 0) > 0) {
                    heading("Riwayat")
                    body.addView(FinanceHistory.view(activity, state, currency), LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                }
                heading("Biaya operasional per jam")
                val hours = LinearLayout(activity)
                listOf("Jam sibuk","Jam sedang","Jam sepi").forEachIndexed { i, label ->
                    hours.addView(StudioUi.stat(activity,label,short(run.getDouble(i))),
                        LinearLayout.LayoutParams(0,-2,1f).apply { if (i>0) marginStart = dp(6) })
                }
                body.addView(hours)
                row("Perawatan per hari", short(state.getDouble("upkeep")), StudioUi.Icon.MENU, StudioUi.TINT_RED)
                // web MoneyTab "Kereta": vehicles owned, needed by the lines and spare, by kind, then
                // the value of the track and stations built
                state.optJSONObject("fleet")?.let { f ->
                    heading("Kereta")
                    val price = { k: String -> StudioFormat.money(f.optDouble(k) * 1e6, currency) }
                    // one table instead of a tall card per figure: kind × dimiliki / dibutuhkan / cadangan
                    val fleet = android.widget.TableLayout(activity).apply {
                        background = StudioUi.card(activity, 14)
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setColumnStretchable(0, true)
                    }
                    fun cell(text: String, head: Boolean = false, end: Boolean = true) = StudioUi.text(activity, text, 13f, true, if (head) StudioUi.MUTED else StudioUi.INK).apply {
                        if (!head && end) typeface = StudioUi.heavy(activity)
                        if (head) textSize = 11.5f
                        gravity = if (end) android.view.Gravity.END else android.view.Gravity.START
                        setPadding(if (end) dp(10) else 0, dp(3), 0, dp(3))
                    }
                    fleet.addView(android.widget.TableRow(activity).apply {
                        addView(cell("", true, false)); listOf("Dimiliki", "Dibutuhkan", "Cadangan").forEach { addView(cell(it, true)) }
                    })
                    val notes = mutableListOf<String>()
                    fun kind(label: String, owned: Int, needed: Int) = fleet.addView(android.widget.TableRow(activity).apply {
                        addView(cell(label, end = false)); addView(cell(owned.toString())); addView(cell(needed.toString())); addView(cell(maxOf(0, owned - needed).toString()))
                        contentDescription = "$label: $owned dimiliki, $needed dibutuhkan line, ${maxOf(0, owned - needed)} cadangan"
                    })
                    val cars = f.optInt("cars")
                    kind("Gerbong", cars, f.optInt("carsNeeded"))
                    notes += "Gerbong ${price("carPrice")} per buah, total ${StudioFormat.money(cars * f.optDouble("carPrice") * 1e6, currency)}"
                    if (f.optInt("fast") > 0 || f.optInt("fastNeeded") > 0) {
                        kind("Kecepatan tinggi", f.optInt("fast"), f.optInt("fastNeeded")); notes += "Gerbong kecepatan tinggi ${price("fastPrice")} per gerbong"
                    }
                    if (f.optInt("tram") > 0 || f.optInt("tramsNeeded") > 0) kind("Trem", f.optInt("tram"), f.optInt("tramsNeeded"))
                    if (f.optInt("bus") > 0 || f.optInt("busesNeeded") > 0) {
                        val n = f.optInt("bus"); kind("Bus", n, f.optInt("busesNeeded"))
                        notes += "Bus ${price("busPrice")} per buah, total ${StudioFormat.money(n * f.optDouble("busPrice") * 1e6, currency)}"
                    }
                    if (f.optInt("ferry") > 0 || f.optInt("boatsNeeded") > 0) {
                        val n = f.optInt("ferry"); kind("Kapal", n, f.optInt("boatsNeeded"))
                        notes += "Kapal ${price("boatPrice")} per buah, total ${StudioFormat.money(n * f.optDouble("boatPrice") * 1e6, currency)}"
                    }
                    body.addView(fleet, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(2) })
                    body.addView(StudioUi.text(activity, notes.joinToString(" · "), 11.5f, false, StudioUi.MUTED).apply { setPadding(dp(2), dp(3), 0, 0) })
                    row("Rel dan stasiun dibangun", StudioFormat.money(state.optDouble("built") * 1e6, currency))
                }
                // web LoanRows: none in a sandbox; while no loan is out the three sizes, each refused past
                // what the profit pays; while one is, the loan, its payment, the days left and Lunasi
                if (sandbox) return
                heading("Pinjaman")
                val money = { usdM: Double -> StudioFormat.money(usdM * 1e6, currency) }
                val outstanding = state.getDouble("outstanding")
                val cash = state.getDouble("cash")
                if (state.getDouble("remainingDays") <= 0) {
                    val offers = state.optJSONArray("offers")
                    val term = state.optDouble("term", 60.0).toInt()
                    // the amounts as chips, one Pinjam button for the one picked (was three big buttons)
                    val count = offers?.length() ?: 0
                    var pick = (0 until count).firstOrNull { offers!!.getJSONObject(it).optBoolean("allowed", true) } ?: 0
                    val chips = LinearLayout(activity)
                    val slot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    if (count > 0) {
                        body.addView(chips, LinearLayout.LayoutParams(-1,-2))
                        body.addView(slot, LinearLayout.LayoutParams(-1,-2))
                    }
                    fun offer(i: Int) {
                        slot.removeAllViews()
                        for (k in 0 until chips.childCount) StudioUi.select(chips.getChildAt(k) as Button, k == i)
                        val o = offers!!.getJSONObject(i)
                        val amount = o.getDouble("amount")
                        val allowed = o.optBoolean("allowed", true)
                        val why = when {
                            allowed -> "Pinjam ${money(amount)} dan bayar kembali ${money(o.getDouble("payment"))} per hari selama $term hari"
                            o.isNull("profit") -> "Sampai ada tarif satu hari penuh, pinjaman paling banyak ${money(offers.getJSONObject(0).getDouble("amount"))}."
                            else -> "Cicilannya ${money(o.getDouble("payment"))} per hari. " +
                                (if (o.optInt("days") == 1) "Laba kemarin ${money(o.getDouble("profit"))} per hari."
                                else "Rata-rata laba ${o.optInt("days")} hari terakhir ${money(o.getDouble("profit"))} per hari.")
                        }
                        val button = StudioUi.button(activity,"Pinjam " + StudioFormat.cash(amount * 1e6, currency), StudioUi.Tone.BLUE) {
                            StudioDialogBuilder(activity).setTitle("Ajukan pinjaman?")
                                .setView(StudioDecision(activity,"Dana pinjaman",money(amount)).apply {
                                    fact("Cicilan per hari",money(o.getDouble("payment")))
                                    fact("Sisa hari",term.toString())
                                    fact("Kas sekarang",StudioFormat.cash(cash * 1e6, currency))
                                    fact("Kas setelah konfirmasi",StudioFormat.cash((cash + amount) * 1e6, currency))
                                }).setNegativeButton("Batal", null)
                                .setPositiveButton("Pinjam") { _,_ -> act(amount.toInt()) }.show()
                        }
                        button.isEnabled = allowed
                        button.contentDescription = why
                        slot.addView(StudioUi.text(activity,why,12f,false,StudioUi.MUTED),LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(2) })
                        slot.addView(button,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                    }
                    for (i in 0 until count) {
                        val o = offers!!.getJSONObject(i)
                        chips.addView(StudioUi.choiceChip(activity, StudioFormat.cash(o.getDouble("amount") * 1e6, currency), i == pick) {
                            pick = i; offer(i)
                        }.apply { alpha = if (o.optBoolean("allowed", true)) 1f else .55f },
                            LinearLayout.LayoutParams(0,-2,1f).apply { if (i > 0) marginStart = dp(6) })
                    }
                    if (count > 0) offer(pick)
                } else {
                    row("Dipinjam", money(state.optDouble("loan", 0.0)))
                    body.addView(StudioUi.row(activity,null,StudioUi.TINT_GREY,"Cicilan per hari",
                        "Bunga ${(state.optDouble("rate",0.01)*100).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}% per hari dari sisa pinjaman",
                        StudioUi.text(activity,money(state.getDouble("dailyPayment")),14f).apply { typeface = StudioUi.heavy(activity) }),
                        LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                    row("Sisa hari", kotlin.math.ceil(state.getDouble("remainingDays")).toInt().toString())
                    val canPay = cash >= outstanding
                    val repay = StudioUi.button(activity,"Lunasi",StudioUi.Tone.PRIMARY) {
                        StudioDialogBuilder(activity).setTitle("Lunasi pinjaman?")
                            .setView(StudioDecision(activity,"Lunasi sisa ${money(outstanding)} dari kas",money(outstanding)).apply {
                                fact("Kas sekarang",StudioFormat.cash(cash * 1e6, currency))
                                fact("Kas setelah konfirmasi",StudioFormat.cash((cash - outstanding) * 1e6, currency))
                            }).setNegativeButton("Batal", null)
                            .setPositiveButton("Lunasi") { _,_ -> act(-1) }.show()
                    }
                    repay.isEnabled = canPay
                    val why = if (canPay) "Lunasi sisa ${money(outstanding)} dari kas" else "Butuh kas ${money(outstanding)}"
                    repay.contentDescription = why
                    body.addView(repay,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
                    body.addView(StudioUi.text(activity,why,12f,true,StudioUi.MUTED),LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(2) })
                }
            }
            refresh()
        }
    }
}
