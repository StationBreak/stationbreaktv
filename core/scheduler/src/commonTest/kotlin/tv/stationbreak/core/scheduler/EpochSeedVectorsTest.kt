// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.scheduler.vectors.VectorFiles
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import tv.stationbreak.core.scheduler.vectors.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `seed_n = splitmix64(seed + n * 0x9E3779B97F4A7C15)` against `docs/rules-format/vectors/epoch-seed.json`, which an
 * independent reference computed from the format text.
 */
class EpochSeedVectorsTest {
    private val rows = VectorJson.parse(VectorFiles.epochSeed).array("vectors").objects()

    @Test
    fun everyVectorMatches() {
        assertTrue(rows.size >= 20, "the file has ${rows.size} vectors")
        for (row in rows) {
            val seed = u64(row.getValue("seed"))
            val n = row.string("n").toLong()
            assertEquals(u64(row.getValue("seed_n")), Epochs.seedN(Seed(seed), n), "seed=$seed n=$n")
        }
    }

    @Test
    fun theIntermediateStateAndTheUnsignedFormOfNAgree() {
        for (row in rows) {
            val seed = u64(row.getValue("seed"))
            val n = row.string("n").toLong()
            assertEquals(u64(row.getValue("nUnsigned")), n.toULong(), "n=$n")
            assertEquals(u64(row.getValue("state")), seed + n.toULong() * GAMMA, "seed=$seed n=$n")
            assertEquals(u64(row.getValue("seed_n")), splitmix64(u64(row.getValue("state"))))
        }
    }

    @Test
    fun theFileCoversNegativeAndLargeEpochNumbers() {
        val ns = rows.map { it.string("n").toLong() }
        assertTrue(ns.any { it < 0 }, "needs a negative n")
        assertTrue(0L in ns && 1L in ns && -1L in ns)
        assertTrue(ns.any { it > Int.MAX_VALUE } && ns.any { it < Int.MIN_VALUE }, "needs n beyond 32 bits")
        val seeds = rows.map { u64(it.getValue("seed")) }
        assertTrue(seeds.any { it >= (1uL shl 63) }, "needs a seed with the high bit set")
    }

    @Test
    fun epochZeroIsTheChannelSeedMixedOnce() {
        assertEquals(splitmix64(7uL), Epochs.seedN(Seed(7uL), 0L))
    }

    @Test
    fun aNegativeNWrapsAsItsTwosComplement() {
        // seed + (-1) * gamma == seed - gamma (mod 2^64)
        assertEquals(splitmix64(100uL - GAMMA), Epochs.seedN(Seed(100uL), -1L))
    }

    private companion object {
        const val GAMMA = 0x9E3779B97F4A7C15uL
    }
}
