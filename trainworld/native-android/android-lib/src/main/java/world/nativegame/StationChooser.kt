// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

/**
 * "Pilih stasiun": a search box over light one-line rows (was a dialog of heavy 3D buttons with a
 * second "Tutup"). Stations sharing a name say which of them each is.
 */
internal object StationChooser {
    fun show(activity: Activity, stations: List<Pair<Int, String>>, open: (Int) -> Unit) {
        fun dp(n: Int) = StudioUi.dp(activity, n)
        val total = stations.groupingBy { it.second }.eachCount()
        val seen = mutableMapOf<String, Int>()
        val rows = stations.map { (id, name) ->
            val k = (seen[name] ?: 0) + 1; seen[name] = k
            Triple(id, name, if ((total[name] ?: 1) > 1) "$k dari ${total[name]} stasiun bernama ini" else null)
        }
        StudioUi.sheet(activity, "Pilih stasiun") { column, dialog ->
            val query = EditText(activity).apply {
                setSingleLine()
                hint = "Cari stasiun"
                StudioUi.field(this)
                setCompoundDrawablesRelative(StudioUi.glyph(activity, StudioUi.Icon.SEARCH, StudioUi.MUTED), null, null, null)
                compoundDrawablePadding = dp(8)
            }
            column.addView(query, LinearLayout.LayoutParams(-1, dp(48)))
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            fun render() {
                list.removeAllViews()
                val q = query.text.toString().trim()
                val found = rows.filter { q.isEmpty() || it.second.contains(q, ignoreCase = true) }
                found.take(120).forEach { (id, name, sub) ->
                    list.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(44)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = StudioDepot.touch(activity, 0xd9ffffff.toInt(), 12)
                        isClickable = true
                        contentDescription = listOfNotNull(name, sub).joinToString(", ")
                        addView(StudioUi.text(activity, name, 14f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        if (sub != null) addView(StudioUi.text(activity, sub, 11.5f, false, StudioUi.MUTED))
                        setOnClickListener { dialog.dismiss(); open(id) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }
                if (found.isEmpty()) list.addView(StudioUi.text(activity, "Tidak ada stasiun bernama “$q”.", 13f, true, StudioUi.MUTED).apply {
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                })
                else if (found.size > 120) list.addView(StudioUi.text(activity, "${found.size - 120} lainnya: ketik untuk mempersempit.", 12f, false, StudioUi.MUTED).apply {
                    setPadding(dp(4), dp(8), dp(4), dp(4))
                })
            }
            query.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = render()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            render()
        }
    }
}
