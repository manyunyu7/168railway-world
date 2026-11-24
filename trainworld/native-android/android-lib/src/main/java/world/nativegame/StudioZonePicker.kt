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
        title: String = "Jelajahi dunia", allowCurrent: Boolean = false, sourceCredits: (() -> Unit)? = null, opened: Set<String>? = null, allCities: (() -> Unit)? = null, select: (String) -> Unit
    ) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val cities = catalog.cities().sortedBy { it.name }
        StudioUi.sheet(activity, title) { column, dialog ->
            // search and "Semua kota" on one row
            val top = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val query = EditText(activity).apply {
                setSingleLine()
                hint = "Cari kota"
                StudioUi.field(this)
                setCompoundDrawablesRelative(StudioUi.glyph(context, StudioUi.Icon.SEARCH, StudioUi.MUTED), null, null, null)
                compoundDrawablePadding = dp(8)
            }
            top.addView(query, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (allCities != null) top.addView(StudioUi.button(activity,"Semua kota") { dialog.dismiss(); allCities() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            column.addView(top, LinearLayout.LayoutParams(-1, -2))
            val local = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(local,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })

            fun cityCard(city: CityPackStore.City): View {
                val cache = if (catalog.cached(city.id)) "Tersedia offline" else
                    "Unduh %.1f\u00a0MB".format(Locale.forLanguageTag("id-ID"),city.bytes / 1_000_000.0)
                val network = when {
                    opened != null -> if (city.id in opened) "Dalam dunia ini" else "Jelajahi kota"
                    city.hasNetwork || city.id == "jak" -> "Jaringan contoh"
                    else -> "Peta kosong"
                }
                return StudioMapPreview.card(activity,StudioMapPreview.cityPath(city.id),city.name,
                    "$network · $cache",city.id == current) {
                    dialog.dismiss()
                    if (city.id != current || allowCurrent) select(city.id)
                }
            }
            if (!opened.isNullOrEmpty()) {
                local.addView(StudioUi.section(activity,"Kota di dunia ini"))
                for (zone in cities.filter { it.id in opened }) local.addView(cityCard(zone),
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
            }
            local.addView(StudioUi.section(activity,"Kota Indonesia"))
            val shortList = LinearLayout(activity)
            local.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(shortList)
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            for (id in featured.filterNot { opened?.contains(it) == true }) {
                shortList.addView(cityCard(catalog.city(id)),LinearLayout.LayoutParams(dp(250),-2).apply { marginEnd=dp(6) })
            }
            local.addView(StudioUi.section(activity,"Kota lainnya"),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            val missing = StudioUi.text(activity, "Kota tidak ditemukan.", 14f, true, StudioUi.MUTED)
            missing.visibility = View.GONE
            column.addView(missing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val matches = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(matches)
            val pageSize = 24
            var page = 0
            fun render() {
                val search = query.text.toString().trim()
                local.visibility = if (search.isEmpty()) View.VISIBLE else View.GONE
                val results = if (search.isEmpty()) cities.filter { it.id !in featured && opened?.contains(it.id) != true }.sortedBy { it.id != current } else
                    cities.filter { it.name.contains(search,ignoreCase = true) || it.id.equals(search,ignoreCase = true) }
                matches.removeAllViews()
                missing.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                results.drop(page * pageSize).take(pageSize).forEach { city ->
                    val row = cityCard(city)
                    matches.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
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
