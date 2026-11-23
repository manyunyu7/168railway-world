// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context

internal object StudioCatalog {
    private var cached: CityPackStore? = null
    @Synchronized fun get(context: Context): CityPackStore {
        return cached ?: CityPackStore(context.filesDir,context.assets.open("world-native/cities.tsv").bufferedReader())
            .also { cached = it }
    }
}
