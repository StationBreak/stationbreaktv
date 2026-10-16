// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.ordering.VectorJson.array
import tv.stationbreak.core.model.ordering.VectorJson.objects
import tv.stationbreak.core.model.ordering.VectorJson.string
import tv.stationbreak.core.model.ordering.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `epoch-seed.json`, `bounded-int.json` and `key-order.json` are published for F1.1 and for ports; F1.1 owns the functions
 * and the tests that run them. This only checks that the files are well formed and safe to rely on: every 64-bit value's
 * decimal, hex and 32-bit halves agree, and the cases that catch common bugs are present.
 */
class PublishedVectorsConsistencyTest {
    private val epochSeed = VectorJson.parse(PublishedVectors.read("epoch-seed.json"))
    private val boundedInt = VectorJson.parse(PublishedVectors.read("bounded-int.json"))
    private val keyOrder = VectorJson.parse(PublishedVectors.read("key-order.json"))
    private val highBit = 1uL shl 63

    @Test
    fun everySixtyFourBitValueAgreesWithItself() {
        for (file in listOf(epochSeed, boundedInt, keyOrder)) {
            val values = VectorJson.allU64(file)
            assertTrue(values.size > 20)
            values.forEach { u64(it) }
        }
    }

    @Test
    fun epochSeedHasANegativeEpochAndAHighBitResult() {
        val rows = epochSeed.array("vectors").objects()
        assertTrue(rows.any { it.getValue("n").toString().startsWith("-") })
        assertTrue(rows.any { u64(it.getValue("seedN")) >= highBit })
        assertTrue(rows.any { u64(it.getValue("seed")) >= highBit })
    }

    @Test
    fun boundedIntHasARejectedDrawAndEachResultIsBelowItsBound() {
        val rows = boundedInt.array("vectors").objects()
        assertTrue(rows.any { it.getValue("rejected").toString().toInt() > 0 })
        for (row in rows) {
            val bound = u64(row.getValue("bound"))
            val threshold = u64(row.getValue("threshold"))
            val draws = row.array("draws").map { u64(it) }
            assertTrue(u64(row.getValue("result")) < bound)
            assertEquals(row.getValue("rejected").toString().toInt(), draws.size - 1)
            assertTrue(draws.dropLast(1).all { it < threshold }, "only the last draw is accepted")
            assertTrue(draws.last() >= threshold)
            assertEquals(draws.last() % bound, u64(row.getValue("result")))
        }
    }

    @Test
    fun keyOrderHasAHighBitKeyAndATie() {
        val cases = keyOrder.array("sortCases").objects()
        val keys = cases.flatMap { c -> c.array("entries").objects().map { u64(it.getValue("key")) } }
        assertTrue(keys.any { it >= highBit }, "a key with the high bit set")
        assertTrue(
            cases.any { c ->
                c
                    .array("entries")
                    .objects()
                    .map { u64(it.getValue("key")) }
                    .let { it.size != it.toSet().size }
            },
            "a tie",
        )
    }

    @Test
    fun keyOrderListsEachCaseInUnsignedKeyOrderThenIdBytes() {
        val groups = keyOrder.array("sortCases").objects() + keyOrder.array("derived").objects().map { it }
        for (case in groups) {
            val entries =
                if ("entries" in case) {
                    case.array("entries").objects().map { it.string("id") to u64(it.getValue("key")) }
                } else {
                    case.array("items").objects().map { it.string("id") to u64(it.getValue("key")) }
                }
            val expected = entries.sortedWith(compareBy<Pair<String, ULong>> { it.second }.thenBy { it.first }).map { it.first }
            assertEquals(expected, case.array("order").map { it.jsonPrimitive.content })
        }
    }
}
