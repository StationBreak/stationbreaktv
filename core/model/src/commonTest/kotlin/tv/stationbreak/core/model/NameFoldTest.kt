// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NameFoldTest {
    @Test
    fun foldsOnlyAsciiCapitals() {
        assertEquals("abc-xyz 09", foldNameAscii("ABC-XYZ 09"))
        assertEquals("\u00C9\u0130\u212A", foldNameAscii("\u00C9\u0130\u212A"))
    }

    @Test
    fun aTextWithNoCapitalIsReturnedAsItIs() {
        val text = "comedy"
        assertSame(text, foldNameAscii(text))
    }

    @Test
    fun foldedNamesKeepOrderAndTheListWhenNothingChanges() {
        assertEquals(listOf("comedy", "sci-fi", "x"), foldNames(listOf("comedy", "Sci-Fi", "X")))
        val plain = listOf("a", "b")
        assertSame(plain, foldNames(plain))
        assertEquals(emptyList(), foldNames(emptyList()))
    }
}
