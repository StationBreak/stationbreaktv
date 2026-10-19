// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.ordering.splitmix64

// Copied from core/scheduler/src/commonTest/kotlin/tv/stationbreak/core/scheduler/SeededGen.kt (spec 11, task 2.0), with
// only the package changed. Both copies are due to be promoted to core:testing; keep them identical until then.

private const val GAMMA = 0x9E3779B97F4A7C15uL
private const val HEX_RADIX = 16
private const val HEX_DIGITS_64 = 16

/**
 * A deterministic generator for property tests, built on `splitmix64` so it needs no library and gives the same cases
 * on every platform. Rerun a failing case with `SeededGen(<seed from the failure message>)`.
 */
internal class SeededGen(
    val seed: ULong,
) {
    private var state = seed

    fun nextULong(): ULong {
        val out = splitmix64(state)
        state += GAMMA
        return out
    }

    /** A number in `0 until bound`. The tiny modulo bias does not matter for test data. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return (nextULong() % bound.toULong()).toInt()
    }

    /** A number in [range]. */
    fun nextInt(range: IntRange): Int = range.first + nextInt(range.last - range.first + 1)

    /** True with a chance of [percent] in 100. */
    fun chance(percent: Int): Boolean = nextInt(PERCENT) < percent

    fun <T> pick(options: List<T>): T = options[nextInt(options.size)]

    /** 32 lower-case hex digits: a canonical ID. */
    fun hexId(): String = hex64() + hex64()

    private fun hex64(): String = nextULong().toString(HEX_RADIX).padStart(HEX_DIGITS_64, '0')

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Runs [check] on [cases] generated cases. Each case gets its own [SeededGen], whose seed comes from [baseSeed] and the
 * case number. A failure prints the case and the seed, so the case can be rerun alone.
 */
internal fun forAllCases(
    name: String,
    baseSeed: ULong,
    cases: Int = 1000,
    check: (SeededGen) -> Unit,
) {
    for (index in 0 until cases) {
        val seed = splitmix64(baseSeed + index.toULong())
        try {
            check(SeededGen(seed))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            val message = "$name: case $index failed, seed $seed (SeededGen($seed)uL): ${failure.message}"
            println(message)
            throw AssertionError(message, failure)
        }
    }
}
