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

    private fun editFares(activity: Activity, handle: Long, clock: () -> Double, persist: () -> Unit) {
        val state = JSONObject(fares(handle,clock(),"{}"))
        val initial = state.optJSONObject("values") ?: return
        val currency = StudioCurrency.current(activity)
        val prices = setOf("base","perKm","transfer")
        val labels = linkedMapOf("base" to "Tarif dasar (${currency.code})", "perKm" to "Tarif per km (${currency.code})",
            "transfer" to "Biaya transit (${currency.code})", "hsr" to "Pengali kereta cepat", "bus" to "Pengali bus", "ferry" to "Pengali feri")
        StudioUi.sheet(activity,"Tarif penumpang") { column, dialog ->
            val preview = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            column.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=StudioUi.dp(activity,8); bottomMargin=StudioUi.dp(activity,8) })
            val inputs = labels.mapValues { (key,label) ->
                column.addView(StudioUi.text(activity,label,15f,true))
                android.widget.EditText(activity).apply {
                    setSingleLine()
                    contentDescription = label
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(java.math.BigDecimal.valueOf(initial.getDouble(key)).multiply(java.math.BigDecimal.valueOf(if (key in prices) currency.rate else 1.0)).stripTrailingZeros().toPlainString())
                    typeface = StudioUi.face(activity,true)
                    setTextColor(StudioDepot.TEAL)
                    minHeight = StudioUi.dp(activity,48)
                    setPadding(StudioUi.dp(activity,12),0,StudioUi.dp(activity,12),0)
                    background = StudioUi.panel(activity,StudioDepot.PAPER,12,StudioDepot.LINE)
                    column.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=StudioUi.dp(activity,8) })
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
                    samples.forEach { (distance,price) -> card.bar("$distance km",currency.symbol+fmt.format(price),price/maximum,StudioDepot.TEAL) }
                }
                preview.addView(card)
            }
            proposedFare()
            for (key in listOf("base","perKm")) inputs.getValue(key).addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
                override fun afterTextChanged(s:android.text.Editable?)=proposedFare()
            })
            val warning = StudioUi.text(activity,"",14f,color = 0xffa32c18.toInt())
            column.addView(warning)
            column.addView(StudioUi.button(activity,"Simpan tarif",StudioUi.Tone.PRIMARY) {
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
                fun applyPatch() {
                    val result = JSONObject(fares(handle,clock(),patch.toString()))
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

    fun show(activity: Activity, handle: Long, clock: () -> Double, onCurrencyChanged: () -> Unit = {}, persist: () -> Unit) {
        if (handle == 0L) return
        val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply { maximumFractionDigits = 2 }
        fun usd(value: Double) = StudioCurrency.current(activity).millions(value)
        fun dp(n: Int) = StudioUi.dp(activity, n)
        StudioUi.sheet(activity, "Keuangan") { sheet, _ ->
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            sheet.addView(body)
            fun refresh() {
                val state = JSONObject(query(handle, clock(), 0))
                val run = state.getJSONArray("operating")
                body.removeAllViews()
                val balance = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(16))
                    background = StudioUi.panel(activity, 0xff173f48.toInt(), 18, 0xff173f48.toInt())
                }
                balance.addView(StudioUi.text(activity, "Saldo kas", 14f, true, 0xffbde5d7.toInt()))
                balance.addView(StudioUi.text(activity, StudioCurrency.current(activity).compact(state.getDouble("cash")), 27f, true, StudioUi.WHITE))
                body.addView(balance, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
                fun row(title: String, value: String) {
                    val line = LinearLayout(activity).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0,dp(7),0,dp(7))
                    }
                    line.addView(StudioUi.text(activity,title,15f), LinearLayout.LayoutParams(0,-2,1f))
                    line.addView(StudioUi.text(activity,value,15f,true))
                    body.addView(line)
                }
                fun heading(label: String) {
                    body.addView(StudioUi.text(activity,label,18f,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
                }
                body.addView(StudioUi.button(activity,"Mata uang · "+StudioCurrency.current(activity).code) {
                    StudioCurrency.picker(activity) { onCurrencyChanged(); refresh() }
                })
                val ledger = state.optJSONArray("ledger")
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
                        body.addView(object : android.view.View(activity) {
                            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            init { contentDescription = "Pendapatan tiket dan biaya operasi selama ${history.size} hari" }
                            override fun onDraw(canvas: android.graphics.Canvas) {
                                val slot = width.toFloat()/history.size
                                val bottom = height-dp(22).toFloat()
                                val bar = minOf(slot*.25f,dp(25).toFloat())
                                amounts.forEachIndexed { i, values ->
                                    val mid = slot*(i+.5f)
                                    values.forEachIndexed { j, amount ->
                                        pen.color = if (j==0) 0xff087874.toInt() else 0xffbb542c.toInt()
                                        val x = mid+(j-1)*bar
                                        canvas.drawRect(x,bottom-(bottom-dp(8))*(amount/maxAmount).toFloat(),x+bar-1,bottom,pen)
                                    }
                                    pen.color = StudioUi.MUTED
                                    pen.textSize = dp(11).toFloat()
                                    pen.textAlign = android.graphics.Paint.Align.CENTER
                                    canvas.drawText("H"+history[i].optInt("day"),mid,height-dp(4).toFloat(),pen)
                                }
                            }
                        },LinearLayout.LayoutParams(-1,dp(92)))
                        body.addView(StudioUi.text(activity,"Hijau · tiket     Jingga · operasi dan perawatan",13f))
                        heading("Buku harian")
                        days.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                            entries.map { "Hari " + it.optInt("day") })
                        body.addView(days)
                        val report = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        body.addView(report)
                        fun day(index: Int) {
                            val entry = entries[index]
                            report.removeAllViews()
                            val metrics = listOf(
                                "Perjalanan" to number.format(entry.optDouble("rides",0.0)),
                                "Pendapatan tiket" to usd(entry.optDouble("fares",0.0)),
                                "Operasional" to usd(entry.optDouble("running",0.0)),
                                "Perawatan" to usd(entry.optDouble("upkeep",0.0)))
                            for ((label,value) in metrics) {
                                val line = LinearLayout(activity)
                                line.addView(StudioUi.text(activity,label,14f), LinearLayout.LayoutParams(0,-2,1f))
                                line.addView(StudioUi.text(activity,value,14f,true))
                                report.addView(line)
                            }
                        }
                        day(0)
                        days.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = day(position)
                        }
                    }
                }
                body.addView(StudioUi.button(activity,"Atur tarif penumpang",StudioUi.Tone.BLUE) {
                    editFares(activity,handle,clock,persist)
                })
                heading("Biaya operasional per jam")
                listOf("Jam sibuk","Jam sedang","Jam sepi").forEachIndexed { i, label -> row(label, usd(run.getDouble(i))) }
                row("Perawatan per hari", usd(state.getDouble("upkeep")))
                heading("Pinjaman")
                row("Sisa pinjaman",usd(state.getDouble("outstanding")))
                row("Angsuran harian",usd(state.getDouble("dailyPayment")))
                if (state.getBoolean("sandbox")) return
                val choices = if (state.getDouble("remainingDays") > 0) listOf(-1) else listOf(1000,5000,10000)
                choices.forEach { op ->
                    val label = if (op == -1) "Lunasi pinjaman" else "Pinjam " + usd(op.toDouble())
                    body.addView(StudioUi.button(activity,label) {
                        val cash = state.getDouble("cash")
                        val delta = if (op < 0) -state.getDouble("outstanding") else op.toDouble()
                        StudioDialogBuilder(activity).setTitle(if(op<0) "Lunasi pinjaman?" else "Ajukan pinjaman?")
                            .setView(StudioDecision(activity,if(op<0) "Pelunasan" else "Dana pinjaman",usd(kotlin.math.abs(delta))).apply {
                                fact("Saldo sekarang",usd(cash))
                                fact("Saldo setelah konfirmasi",usd(cash+delta))
                                if(op<0) fact("Angsuran setelah pelunasan",usd(0.0))
                            }).setNegativeButton("Batal", null)
                            .setPositiveButton("Konfirmasi") { _,_ ->
                                val result = JSONObject(query(handle,clock(),op))
                                if (result.has("error")) Toast.makeText(activity,result.getString("error"),Toast.LENGTH_LONG).show()
                                else persist()
                                refresh()
                            }.show()
                    },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(5) })
                }
            }
            refresh()
        }
    }
}
