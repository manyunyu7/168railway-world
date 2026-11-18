// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.net.Uri
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import java.util.Locale

internal fun showCityPicker(activity: Activity, store: CityPackStore, current: String, beforeOpen: () -> Boolean) {
    val cities = store.cities().toList()
    val labels = cities.map { city ->
        val state = if (store.cached(city.id)) "offline" else "%.1f MB".format(Locale.getDefault(), city.bytes / 1_000_000.0)
        val kind = if (city.hasNetwork) "" else " · Bangun sendiri"
        "${city.name}$kind  ·  $state"
    }.toTypedArray()
    AlertDialog.Builder(activity).setTitle("Pilih jaringan kota")
        .setSingleChoiceItems(labels, cities.indexOfFirst { it.id == current }) { dialog, index ->
            dialog.dismiss()
            if (beforeOpen()) {
                activity.startActivity(Intent(activity, NativeWorldActivity::class.java).putExtra("city",cities[index].id))
            }
        }.setNegativeButton("Batal", null)
        .setNeutralButton("Sumber data") { _, _ ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CityPackStore.ORIGIN + "DATA-CREDITS.txt")))
        }.show()
}
