// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The static: a few small noise tiles that are swapped in turn, as in the motion study (6 tiles of 48 x 45, grey from
 * 40 to 240, a touch warm). Pure and seeded, so the same pixels come out every time; the renderer turns them into
 * bitmaps once and keeps them.
 */
internal object StanNoise {
    const val TILE_COUNT = 6
    const val TILE_WIDTH = 48
    const val TILE_HEIGHT = 45
    const val SEED = 0x57A4

    private const val OPAQUE = 0xFF shl 24
    private const val FLOOR = 40.0
    private const val SPAN = 200.0
    private const val GREEN = .97
    private const val BLUE = .9
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /** The ARGB pixels of tile [index], row by row. */
    fun tile(
        index: Int,
        seed: Int = SEED,
    ): IntArray {
        val random = Random(seed + index)
        return IntArray(TILE_WIDTH * TILE_HEIGHT) {
            val v = FLOOR + random.nextDouble() * SPAN
            OPAQUE or (v.roundToInt() shl RED_SHIFT) or ((v * GREEN).roundToInt() shl GREEN_SHIFT) or
                (v * BLUE).roundToInt()
        }
    }
}
