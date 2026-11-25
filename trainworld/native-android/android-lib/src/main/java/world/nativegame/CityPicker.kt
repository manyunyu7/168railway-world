// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.content.Intent

internal fun showCityPicker(activity: Activity, store: CityPackStore, current: String, beforeOpen: () -> Boolean) {
    StudioZonePicker.show(activity,store,current,"Pilih jaringan kota",allowCurrent=true,sourceCredits={
        StudioUi.sheet(activity,"Sumber data kota") { body,_ ->
            val credits = activity.assets.open("world-native/licenses/city-data.txt").bufferedReader().use { it.readText() }
            body.addView(StudioUi.text(activity,credits,14f).apply { setTextIsSelectable(true) })
        }
    }) { id ->
        if (beforeOpen()) activity.startActivity(Intent(activity,NativeWorldActivity::class.java).putExtra("city",id))
    }
}
