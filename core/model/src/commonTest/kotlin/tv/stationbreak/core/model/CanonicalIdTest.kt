// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CanonicalIdTest {
    private val canonical = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun aLowerCaseThirtyTwoHexIdStays() {
        assertEquals(canonical, CanonicalId.parse(canonical).value)
        assertEquals("0123456789abcdef0123456789abcdef", CanonicalId.parse("0123456789abcdef0123456789abcdef").value)
    }

    @Test
    fun hyphenatedUpperCaseAndBracedFormsNormalise() {
        assertEquals(canonical, CanonicalId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").value)
        assertEquals(canonical, CanonicalId.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").value)
        assertEquals(canonical, CanonicalId.parse("{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}").value)
    }

    @Test
    fun wrongLengthNonHexAndBlankAreRejectedWithAPlainMessage() {
        val bad =
            listOf(
                "a".repeat(31),
                "a".repeat(33),
                "g".repeat(32),
                "",
                "   ",
                "{" + "a".repeat(32),
                "a".repeat(32) + "}",
                "a b" + "a".repeat(29),
                "é" + "a".repeat(31),
            )
        for (raw in bad) {
            val e = assertFailsWith<IllegalArgumentException>("accepted '$raw'") { CanonicalId.parse(raw) }
            assertEquals("An ID is 32 hexadecimal characters", e.message)
            assertNull(CanonicalId.parseOrNull(raw))
        }
    }

    @Test
    fun strictParseKeepsOnlyTheCanonicalForm() {
        assertEquals(canonical, CanonicalId.parseCanonicalOrNull(canonical)?.value)
        assertNull(CanonicalId.parseCanonicalOrNull("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(CanonicalId.parseCanonicalOrNull("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        assertNull(CanonicalId.parseCanonicalOrNull("{" + canonical + "}"))
    }

    @Test
    fun printsItsValue() {
        assertEquals(canonical, CanonicalId.parse(canonical).toString())
    }
}
