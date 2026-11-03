// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilmCollectionsTest {
    private fun id(digit: Char) = CanonicalId.parse(digit.toString().repeat(32))

    @Test
    fun entriesAreSortedByFilmIdWhateverTheMapOrder() {
        val ascending = linkedMapOf(id('1') to id('c'), id('2') to id('d'), id('3') to id('c'))
        val descending = linkedMapOf(id('3') to id('c'), id('2') to id('d'), id('1') to id('c'))

        val expected = listOf(id('1') to id('c'), id('2') to id('d'), id('3') to id('c'))

        assertEquals(expected, FilmCollections.of(ascending).entries)
        assertEquals(expected, FilmCollections.of(descending).entries)
    }

    @Test
    fun aFilmInTheMapHasItsCollectionAndOthersHaveNone() {
        val films = FilmCollections.of(mapOf(id('1') to id('c')))

        assertEquals(id('c'), films[id('1')])
        assertNull(films[id('2')])
        assertTrue(FilmCollections.of(emptyMap()).isEmpty)
        assertTrue(FilmCollections.of(emptyMap()).entries.isEmpty())
    }
}
