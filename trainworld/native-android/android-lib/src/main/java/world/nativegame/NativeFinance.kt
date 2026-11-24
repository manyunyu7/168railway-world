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
                if (key == "hsr") column.addView(StudioUi.section(activity,"Pengali layanan"))
                column.addView(StudioUi.text(activity,label,14f,true).apply { setPadding(StudioUi.dp(activity,4),0,0,StudioUi.dp(activity,4)) })
                android.widget.EditText(activity).apply {
                    setSingleLine()
                    contentDescription = label
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(java.math.BigDecimal.valueOf(initial.getDouble(key)).multiply(java.math.BigDecimal.valueOf(if (key in prices) currency.rate else 1.0)).stripTrailingZeros().toPlainString())
                    typeface = StudioUi.heavy(activity)
                    textSize = 17f
                    setTextColor(StudioUi.INK)
                    minHeight = StudioUi.dp(activity,52)
                    setPadding(StudioUi.dp(activity,18),0,StudioUi.dp(activity,18),0)
                    background = StudioUi.card(activity,26,-1)
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
                        column.addView(seek,LinearLayout.LayoutParams(-1,StudioUi.dp(activity,44)).apply { bottomMargin=StudioUi.dp(activity,8) })
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
                val state = JSONObject(query(handle, clock(), 0))
                val run = state.getJSONArray("operating")
                body.removeAllViews()
                val ledger = state.optJSONArray("ledger")
                val latest = ledger?.let { l -> (l.length()-1 downTo 0).firstNotNullOfOrNull { l.optJSONObject(it) } }
                // Gold piggy-bank card with the latest day's net result.
                val balance = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(18))
                    background = android.graphics.drawable.LayerDrawable(arrayOf(
                        StudioUi.panel(activity, 0xffc98500.toInt(), 26, 0xffc98500.toInt()),
                        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(0xffffd23f.toInt(), 0xffffb020.toInt())).apply { cornerRadius = dp(26).toFloat() })).apply {
                        setLayerInset(1, 0, 0, 0, dp(5))
                    }
                }
                balance.addView(StudioUi.text(activity, "Saldo kas · "+StudioCurrency.current(activity).code, 13f, true, 0xff7a5600.toInt()))
                balance.addView(StudioUi.title(activity, StudioCurrency.current(activity).compact(state.getDouble("cash")), 36f))
                if (latest != null) {
                    val net = latest.optDouble("fares",0.0)-latest.optDouble("running",0.0)-latest.optDouble("upkeep",0.0)
                    balance.addView(StudioUi.chip(activity, (if (net >= 0) "▲ +" else "▼ ")+short(net)+" · Hari "+latest.optInt("day"),
                        0xb3ffffff.toInt(), if (net >= 0) StudioUi.GOOD else StudioUi.BAD),
                        LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(8) })
                }
                body.addView(balance, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
                fun row(title: String, value: String, icon: StudioUi.Icon? = null, tint: Int = StudioUi.TINT_GREY, ink: Int = StudioUi.INK) {
                    body.addView(StudioUi.row(activity, icon, tint, title, null,
                        StudioUi.text(activity,value,15f,color = ink).apply { typeface = StudioUi.heavy(activity) }),
                        LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
                }
                fun heading(label: String) {
                    body.addView(StudioUi.section(activity,label),LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
                }
                body.addView(StudioUi.button(activity,"Mata uang · "+StudioCurrency.current(activity).code) {
                    StudioCurrency.picker(activity) { onCurrencyChanged(); refresh() }
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
                if (ledger != null && ledger.length() > 0) {
                    val days = android.widget.Spinner(activity)
                    val entries = (ledger.length()-1 downTo 0).mapNotNull { ledger.optJSONObject(it) }
                    if (entries.isNotEmpty()) {
                        heading("Riwayat kas operasi")
                        val history = entries.take(7).asReversed()
                        val amounts = history.map { entry ->
                            listOf(entry.optDouble("fares",0.0),
                                entry.optDouble("upkeep",0.0)+entry.optDouble("running",0.0))
                        }
                        val maxAmount = amounts.flatten().maxOrNull()?.coerceAtLeast(.001) ?: 1.0
                        val chart = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = StudioUi.card(activity, 22)
                            setPadding(dp(12),dp(10),dp(12),dp(8))
                        }
                        body.addView(chart, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                        chart.addView(object : android.view.View(activity) {
                            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            val box = android.graphics.RectF()
                            init { contentDescription = "Pendapatan tiket dan biaya operasi selama ${history.size} hari" }
                            override fun onDraw(canvas: android.graphics.Canvas) {
                                val slot = width.toFloat()/history.size
                                val bottom = height-dp(22).toFloat()
                                val bar = minOf(slot*.28f,dp(22).toFloat())
                                amounts.forEachIndexed { i, values ->
                                    val mid = slot*(i+.5f)
                                    values.forEachIndexed { j, amount ->
                                        pen.color = if (j==0) StudioUi.LEAF else StudioUi.GREEN
                                        val w = if (j==0) bar else bar*.55f
                                        val x = if (j==0) mid-bar*.7f else mid+bar*.4f
                                        val top = bottom-(bottom-dp(8))*(amount/maxAmount).toFloat()
                                        box.set(x,minOf(top,bottom-w),x+w,bottom)
                                        canvas.drawRoundRect(box,w/2,w/2,pen)
                                    }
                                    pen.color = StudioUi.MUTED
                                    pen.textSize = dp(11).toFloat()
                                    pen.textAlign = android.graphics.Paint.Align.CENTER
                                    canvas.drawText("H"+history[i].optInt("day"),mid,height-dp(4).toFloat(),pen)
                                }
                            }
                        },LinearLayout.LayoutParams(-1,dp(104)))
                        chart.addView(StudioUi.text(activity,"● Hijau · tiket     ● Oranye · operasi dan perawatan",12f,true,StudioUi.MUTED))
                        heading("Buku harian")
                        days.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                            entries.map { "Hari " + it.optInt("day") })
                        days.background = StudioUi.card(activity, 26, -1)
                        days.setPadding(dp(14),0,dp(8),0)
                        body.addView(days, LinearLayout.LayoutParams(-1,dp(48)))
                        val report = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        body.addView(report)
                        fun day(index: Int) {
                            val entry = entries[index]
                            report.removeAllViews()
                            data class M(val label: String, val value: String, val icon: StudioUi.Icon, val tint: Int, val ink: Int)
                            val metrics = listOf(
                                M("Perjalanan", number.format(entry.optDouble("rides",0.0)), StudioUi.Icon.TRAIN, StudioUi.TINT_BLUE, StudioUi.INK),
                                M("Tiket", "+"+short(entry.optDouble("fares",0.0)), StudioUi.Icon.WALLET, StudioUi.TINT_GREEN, StudioUi.GOOD),
                                M("Operasional", "−"+short(entry.optDouble("running",0.0)), StudioUi.Icon.ROUTE, StudioUi.TINT_RED, StudioUi.BAD),
                                M("Perawatan", "−"+short(entry.optDouble("upkeep",0.0)), StudioUi.Icon.MENU, StudioUi.TINT_RED, StudioUi.BAD))
                            for (m in metrics) {
                                report.addView(StudioUi.row(activity, m.icon, m.tint, m.label, null,
                                    StudioUi.text(activity,m.value,15f,color = m.ink).apply { typeface = StudioUi.heavy(activity) }),
                                    LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
                            }
                        }
                        day(0)
                        days.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = day(position)
                        }
                    }
                }
                body.addView(StudioUi.button(activity,"Atur tarif penumpang",StudioUi.Tone.PRIMARY,StudioUi.Icon.WALLET) {
                    editFares(activity,handle,clock,transaction,persist)
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
                heading("Biaya operasional per jam")
                val hours = LinearLayout(activity)
                listOf("Jam sibuk","Jam sedang","Jam sepi").forEachIndexed { i, label ->
                    hours.addView(StudioUi.stat(activity,label,short(run.getDouble(i))),
                        LinearLayout.LayoutParams(0,-2,1f).apply { if (i>0) marginStart = dp(6) })
                }
                body.addView(hours)
                row("Perawatan per hari", short(state.getDouble("upkeep")), StudioUi.Icon.MENU, StudioUi.TINT_RED)
                heading("Pinjaman")
                val loan = LinearLayout(activity)
                loan.addView(StudioUi.stat(activity,"Sisa pinjaman",short(state.getDouble("outstanding"))),LinearLayout.LayoutParams(0,-2,1f))
                loan.addView(StudioUi.stat(activity,"Angsuran harian",short(state.getDouble("dailyPayment"))),
                    LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(6) })
                body.addView(loan)
                if (!state.getBoolean("sandbox")) body.addView(StudioUi.text(activity,
                    "Angsuran dipotong otomatis dari kas tiap hari.",13f,true,0xff7a5600.toInt()).apply {
                    background = StudioUi.card(activity,18,StudioUi.TINT_YELLOW)
                    setPadding(dp(12),dp(10),dp(12),dp(10))
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
                if (state.getBoolean("sandbox")) return
                val choices = if (state.getDouble("remainingDays") > 0) listOf(-1) else listOf(1000,5000,10000)
                choices.forEach { op ->
                    val label = if (op == -1) "Lunasi pinjaman" else "Pinjam " + short(op.toDouble())
                    body.addView(StudioUi.button(activity,label,if (op == -1) StudioUi.Tone.PRIMARY else StudioUi.Tone.BLUE) {
                        val cash = state.getDouble("cash")
                        val delta = if (op < 0) -state.getDouble("outstanding") else op.toDouble()
                        StudioDialogBuilder(activity).setTitle(if(op<0) "Lunasi pinjaman?" else "Ajukan pinjaman?")
                            .setView(StudioDecision(activity,if(op<0) "Pelunasan" else "Dana pinjaman",usd(kotlin.math.abs(delta))).apply {
                                fact("Saldo sekarang",usd(cash))
                                fact("Saldo setelah konfirmasi",usd(cash+delta))
                                if(op<0) fact("Angsuran setelah pelunasan",usd(0.0))
                            }).setNegativeButton("Batal", null)
                            .setPositiveButton("Konfirmasi") { _,_ ->
                                transaction { now ->
                                    val result = JSONObject(query(handle,now,op))
                                    if (result.has("error")) Toast.makeText(activity,result.getString("error"),Toast.LENGTH_LONG).show()
                                    else persist()
                                    refresh()
                                }
                            }.show()
                    },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(5) })
                }
            }
            refresh()
        }
    }
}
