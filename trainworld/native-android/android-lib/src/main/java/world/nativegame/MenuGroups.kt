// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

/** The Cari & menu sheet's groups, so its dozen commands read as a few short lists. */
internal object MenuGroups {
    val NAMES = listOf("Riwayat", "Peta", "Kota", "Lainnya")
    fun of(label: String): Int = when {
        label.startsWith("Urungkan") || label.startsWith("Ulangi") -> 0
        label.startsWith("Pilih stasiun") || label.startsWith("Lapisan") || label.startsWith("Diagram") ||
            label.startsWith("Jelajahi") || label.startsWith("Pilih kota") -> 1
        label.startsWith("Celengan") || label.startsWith("Pencapaian") || label.startsWith("Cetak") || label.startsWith("Bongkar") -> 2
        else -> 3
    }
}
