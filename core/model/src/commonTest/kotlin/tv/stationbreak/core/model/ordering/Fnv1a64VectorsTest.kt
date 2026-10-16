// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ordering.VectorJson.array
import tv.stationbreak.core.model.ordering.VectorJson.objects
import tv.stationbreak.core.model.ordering.VectorJson.string
import tv.stationbreak.core.model.ordering.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `fnv1a64` against `docs/rules-format/vectors/fnv1a64.json` (computed by an independent reference). */
class Fnv1a64VectorsTest {
    private val file = VectorJson.parse(VectorFiles.fnv1a64)

    @Test
    fun everyVectorMatches() {
        val rows = file.array("vectors").objects()
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val bytes = VectorJson.hexToBytes(row.string("bytesHex"))
            assertEquals(u64(row.getValue("output")), fnv1a64(bytes), row.string("name"))
        }
    }

    @Test
    fun thePublishedAnchorsHold() {
        assertEquals(0xCBF29CE484222325uL, fnv1a64(ByteArray(0)))
        assertEquals(0xAF63DC4C8601EC8CuL, fnv1a64("a".encodeToByteArray()))
        assertEquals(0x85944171F73967E8uL, fnv1a64("foobar".encodeToByteArray()))
    }

    @Test
    fun aCanonicalIdIsHashedAsItsThirtyTwoAsciiCharacters() {
        val id = CanonicalId.parse("0123456789abcdef0123456789abcdef")
        assertEquals(fnv1a64(id.value.encodeToByteArray()), fnv1a64(id))
        assertEquals(fnv1a64("1".repeat(32).encodeToByteArray()), fnv1a64(CanonicalId.parse("1".repeat(32))))
    }

    @Test
    fun aByteAboveSevenFIsUnsignedNotSignExtended() {
        val row = file.array("vectors").objects().first { it.string("bytesHex") == "ff" }
        assertEquals(u64(row.getValue("output")), fnv1a64(byteArrayOf(0xFF.toByte())))
    }
}
