// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The only place a Double appears: the true logarithm is the oracle. The integer result truncates, so it sits below the
 * true value by under one unit plus a few millionths of a unit of accumulated rounding.
 */
private const val ONE_UNIT = 1.0001

class Log2AccuracyTest {
    @Test
    fun log2IsWithinOneUnitOfTheTrueValue() {
        val big = listOf(1L shl 20, (1L shl 20) + 1, 1L shl 31, (1L shl 31) + 12_345, 1L shl 40, 10_000_000_007L)
        val xs = (1L..20_000L) + big
        for (x in xs) {
            val exact = log2(x.toDouble()) * 65_536.0
            assertTrue(abs(log2Q16(x) - exact) <= ONE_UNIT, "log2Q16($x) = ${log2Q16(x)}, true $exact")
        }
    }
}
