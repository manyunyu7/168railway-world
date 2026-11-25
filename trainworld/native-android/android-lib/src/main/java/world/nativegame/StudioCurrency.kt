// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View
import java.util.Currency
import android.content.Context
import org.json.JSONArray
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

internal data class StudioCurrency(val code: String, val label: String, val symbol: String, val rate: Double) {
    private fun fmt(value: Double) = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).apply {
        maximumFractionDigits = 2
    }.format(value)

    fun millions(millionUsd: Double) = symbol + fmt(millionUsd * rate) + " juta"
    fun compact(millionUsd: Double): String {
        val amount = millionUsd * rate
        return when {
            abs(amount) >= 1e6 -> symbol + fmt(amount / 1e6) + " T"
            abs(amount) >= 1000 -> symbol + fmt(amount / 1000) + " miliar"
            else -> symbol + fmt(amount) + " jt"
        }
    }

    companion object {
        private var catalog: List<StudioCurrency>? = null
        private fun all(c: Context): List<StudioCurrency> = catalog ?: run {
            val rows = c.assets.open("world-native/currencies.json").bufferedReader().use { JSONArray(it.readText()) }
            List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                StudioCurrency(row.getString("code"), row.getString("label"),
                    row.optString("full",row.getString("symbol")),row.getDouble("rate"))
            }.also { catalog = it }
        }
        fun current(c: Context): StudioCurrency {
            val selected = c.getSharedPreferences("world-native",0).getString("currency","IDR")
            return all(c).firstOrNull { it.code == selected } ?: all(c).first()
        }
        fun select(c: Context, code: String) {
            if (all(c).any { it.code == code }) c.getSharedPreferences("world-native",0).edit().putString("currency",code).apply()
        }
        fun picker(activity: Activity, onChanged: () -> Unit) {
            choose(activity,current(activity)) { picked ->
                select(activity,picked.code)
                onChanged()
            }
        }
        fun choose(activity: Activity, initial: StudioCurrency, onPick: (StudioCurrency) -> Unit) {
            val entries = all(activity)
            val idLocale = Locale.forLanguageTag("id-ID")
            var choice = initial
            lateinit var apply: Button
            fun dp(n: Int) = StudioUi.dp(activity,n)
            fun label(currency: StudioCurrency) = if (currency.code == "IDR") "Rupiah Indonesia" else
                runCatching { Currency.getInstance(currency.code).getDisplayName(idLocale) }.getOrDefault(currency.label)
            StudioUi.sheet(activity,"Mata uang",pinnedAction={apply}) { content, dialog ->
                val preview = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = StudioUi.panel(activity,StudioDepot.MINT,16,StudioDepot.LINE)
                    setPadding(dp(15),dp(12),dp(15),dp(12))
                }
                preview.addView(StudioUi.text(activity,"Contoh anggaran · setara US$1 juta",14f,true,StudioDepot.TEAL))
                val amount = StudioUi.text(activity,choice.compact(1.0),25f,true,StudioDepot.TEAL)
                amount.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                preview.addView(amount)
                content.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
                val list = RadioGroup(activity).apply { orientation=LinearLayout.VERTICAL }
                content.addView(list,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
                val ordered = entries.sortedBy { it.code != choice.code }
                for (currency in ordered) {
                    val row = RadioButton(activity).apply {
                        id=View.generateViewId()
                        text="${currency.code}  ·  ${label(currency)}"
                        gravity=Gravity.CENTER_VERTICAL
                        minHeight=dp(48)
                        setPadding(dp(8),0,dp(8),0)
                        typeface=StudioUi.face(activity,true)
                        setTextColor(StudioDepot.TEAL)
                        buttonTintList=android.content.res.ColorStateList.valueOf(StudioDepot.TEAL)
                    }
                    list.addView(row,RadioGroup.LayoutParams(-1,-2))
                    row.isChecked=currency.code==choice.code
                    row.setOnClickListener {
                        choice=currency
                        amount.text=currency.compact(1.0)
                        apply.text="Gunakan ${currency.code}"
                    }
                }
                apply=StudioUi.button(activity,"Gunakan ${choice.code}",StudioUi.Tone.PRIMARY,StudioUi.Icon.WALLET) {
                    dialog.dismiss()
                    onPick(choice)
                }
            }
        }
    }
}
