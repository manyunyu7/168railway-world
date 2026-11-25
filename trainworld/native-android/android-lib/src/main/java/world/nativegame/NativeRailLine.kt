// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

internal object NativeRailLine {
    @JvmStatic external fun create(handle: Long, stops: IntArray, name: String, clock: Double, vehicle: Int, commit: Boolean): String
}
