// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.HorizontalScrollView
import java.util.Locale
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

internal object StudioZonePicker {
    val featured = listOf("jak","sur","bad","yog","sem","med","maa","den")

    fun show(
        activity: Activity, catalog: CityPackStore, current: String,
        title: String = "Jelajahi dunia", allowCurrent: Boolean = false, sourceCredits: (() -> Unit)? = null, select: (String) -> Unit
    ) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val cities = catalog.cities().sortedBy { it.name }
        StudioUi.sheet(activity, title) { column, dialog ->
            val query = EditText(activity).apply {
                setSingleLine()
                hint = "Cari kota"
                minHeight = dp(54)
                textSize = 16f
                typeface = StudioUi.face(context, true)
                setTextColor(StudioUi.INK)
                setHintTextColor(StudioUi.MUTED)
                setPadding(dp(18), 0, dp(18), 0)
                background = StudioUi.pill(context, StudioUi.WHITE, 27)
            }
            column.addView(query, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            val local = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(local,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            local.addView(StudioUi.section(activity,"Kota Indonesia"))
            fun cityCard(city: CityPackStore.City): View {
                val cache = if (catalog.cached(city.id)) "Tersedia offline" else
                    "Unduh %.1f MB".format(Locale.forLanguageTag("id-ID"),city.bytes / 1_000_000.0)
                val network = if (city.hasNetwork || city.id == "jak") "Jaringan contoh" else "Peta kosong"
                return StudioMapPreview.card(activity,StudioMapPreview.cityPath(city.id),city.name,
                    "$network · $cache",city.id == current) {
                    dialog.dismiss()
                    if (city.id != current || allowCurrent) select(city.id)
                }
            }
            val shortList = LinearLayout(activity)
            local.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(shortList)
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
            for (id in featured) {
                shortList.addView(cityCard(catalog.city(id)),LinearLayout.LayoutParams(dp(235),-2).apply { marginEnd=dp(6) })
            }
            local.addView(StudioUi.section(activity,"Kota lainnya"),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            val missing = StudioUi.text(activity, "Kota tidak ditemukan.", 15f, true, StudioUi.MUTED)
            missing.visibility = View.GONE
            column.addView(missing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val matches = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(matches)
            val pageSize = 24
            var page = 0
            fun render() {
                val search = query.text.toString().trim()
                local.visibility = if (search.isEmpty()) View.VISIBLE else View.GONE
                val results = if (search.isEmpty()) cities.filter { it.id !in featured }.sortedBy { it.id != current } else
                    cities.filter { it.name.contains(search,ignoreCase = true) || it.id.equals(search,ignoreCase = true) }
                matches.removeAllViews()
                missing.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                results.drop(page * pageSize).take(pageSize).forEach { city ->
                    val row = cityCard(city)
                    matches.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(5) })
                }
                val pages = (results.size + pageSize - 1) / pageSize
                if (pages > 1) {
                    val navigation = LinearLayout(activity)
                    fun pageButton(text: String, step: Int) {
                        navigation.addView(StudioUi.button(activity,text) {
                            page += step
                            render()
                            query.requestFocus()
                            column.parent?.let { (it as? android.widget.ScrollView)?.scrollTo(0,0) }
                        }.apply { isEnabled = page + step in 0 until pages },LinearLayout.LayoutParams(0,-2,1f))
                    }
                    pageButton("Sebelumnya",-1)
                    pageButton("Berikutnya",1)
                    matches.addView(navigation)
                }
            }
            if (sourceCredits != null) column.addView(StudioUi.button(activity,"Sumber data") {
                dialog.dismiss()
                sourceCredits()
            })
            render()
            query.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(value: Editable?) { page = 0; render() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
    }
}
