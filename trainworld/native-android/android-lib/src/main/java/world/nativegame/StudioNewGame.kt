// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

internal object StudioNewGame {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun localShift(city: String): Int
    @JvmStatic external fun prepare(bytes: ByteArray, city: String, sandbox: Boolean): ByteArray
}
