package world.nativegame

/**
 * Web map/stationNames.ts `nextSuggestion` (station_names.rs): the rename dialog's "Nama usulan
 * berikutnya" steps through the names a station could take from the city places near it.
 */
internal object StationNames {
    @JvmStatic private external fun places(xy: DoubleArray, kinds: IntArray, pops: DoubleArray, names: String)
    @JvmStatic private external fun next(handle: Long, node: Int, current: String): String

    /** The loaded cities' places, kept by the engine for the suggestions. */
    fun places(layers: List<CitySurface.Layer>) {
        val all = layers.flatMap { it.places }
        runCatching {
            places(DoubleArray(all.size * 2) { i -> all[i / 2].let { if (i % 2 == 0) it.x else it.y }.toDouble() },
                IntArray(all.size) { all[it].kind }, DoubleArray(all.size) { all[it].pop },
                all.joinToString("\n") { it.name.replace('\n', ' ') })
        }
    }

    /** The suggestion after [current] for station [node], or null when there is nothing else. */
    fun nextFor(handle: Long, node: Int, current: String): String? =
        runCatching { next(handle, node, current) }.getOrNull()?.takeIf { it.isNotBlank() }
}
