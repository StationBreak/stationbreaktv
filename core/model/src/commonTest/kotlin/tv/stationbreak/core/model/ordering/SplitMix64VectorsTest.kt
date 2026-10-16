// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import tv.stationbreak.core.model.ordering.VectorJson.array
import tv.stationbreak.core.model.ordering.VectorJson.obj
import tv.stationbreak.core.model.ordering.VectorJson.objects
import tv.stationbreak.core.model.ordering.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `splitmix64` against `docs/rules-format/vectors/splitmix64.json` (computed by an independent reference). */
class SplitMix64VectorsTest {
    private val file = VectorJson.parse(VectorFiles.splitmix64)

    @Test
    fun everyVectorMatches() {
        val rows = file.array("vectors").objects()
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val input = u64(row.getValue("input"))
            assertEquals(u64(row.getValue("output")), splitmix64(input), "splitmix64($input)")
        }
    }

    @Test
    fun feedingTheOutputBackInFollowsTheChain() {
        val chain = file.obj("chain")
        var state = u64(chain.getValue("start"))
        for (expected in chain.array("outputs")) {
            state = splitmix64(state)
            assertEquals(u64(expected), state)
        }
    }

    @Test
    fun theFourPublishedOutputsFromStateZeroAreReproduced() {
        // Vigna's splitmix64.c seeded with 0: the state advances by the golden gamma before each mix.
        val gamma = 0x9E3779B97F4A7C15uL
        val published = listOf(0xE220A8397B1DCDAFuL, 0x6E789E6AA1B965F4uL, 0x06C45D188009454FuL, 0xF88BB8A8724C81ECuL)
        published.forEachIndexed { k, expected -> assertEquals(expected, splitmix64(gamma * k.toULong())) }
    }

    @Test
    fun theDocumentedAnchorsHold() {
        assertEquals(0xE220A8397B1DCDAFuL, splitmix64(0uL))
        assertEquals(0xA706DD2F4D197E6FuL, splitmix64(0xE220A8397B1DCDAFuL))
    }

    @Test
    fun resultsWithTheHighBitSetAreExactUnsignedNumbers() {
        val highs =
            file
                .array("vectors")
                .objects()
                .map { u64(it.getValue("output")) }
                .filter { it >= (1uL shl 63) }
        assertTrue(highs.isNotEmpty(), "the file needs an output with the high bit set")
        assertEquals("16294208416658607535", splitmix64(0uL).toString())
    }
}
