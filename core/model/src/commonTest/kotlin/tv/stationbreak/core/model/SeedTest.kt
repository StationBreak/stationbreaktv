// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SeedTest {
    @Test
    fun acceptsZeroAndTheMaximum() {
        assertEquals(0uL, Seed.parse("0").value)
        assertEquals(ULong.MAX_VALUE, Seed.parse("18446744073709551615").value)
        assertEquals(13835058055282163712uL, Seed.parse("13835058055282163712").value)
    }

    @Test
    fun formatsBackExactlyAsRead() {
        for (text in listOf("0", "7", "42", "13835058055282163712", "18446744073709551615")) {
            assertEquals(text, Seed.parse(text).format())
            assertEquals(text, Seed.parse(text).toString())
        }
    }

    @Test
    fun rejectsEverythingButTheCanonicalDecimalForm() {
        val bad =
            listOf(
                "007",
                "00",
                "-1",
                "+1",
                "abc",
                "",
                " 1",
                "1 ",
                "1.0",
                "1e3",
                "0x10",
                "١٢٣",
                "18446744073709551616",
                "99999999999999999999",
                "100000000000000000000",
                "1" + "0".repeat(40),
            )
        for (text in bad) {
            val e = assertFailsWith<IllegalArgumentException>("accepted '$text'") { Seed.parse(text) }
            assertEquals("A seed is a whole number from 0 to 18446744073709551615 with no leading zeros", e.message)
            assertNull(Seed.parseOrNull(text))
        }
    }

    @Test
    fun keepsTheHighBit() {
        assertEquals("9223372036854775808", Seed(Long.MAX_VALUE.toULong() + 1uL).format())
    }
}
