// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Spec 12: the current names of the collections, by ID. A name is typed by a person, so it never prints. */
class CollectionNamesTest {
    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    @Test
    fun aNameIsFoundByItsIdAndAnUnknownIdHasNone() {
        val names = CollectionNames(mapOf(id(1) to "Synthetic One", id(2) to "Synthetic Two"))

        assertEquals("Synthetic One", names[id(1)])
        assertNull(names[id(3)])
        assertEquals(setOf(id(1), id(2)), names.ids)
    }

    @Test
    fun toStringHoldsNoName() {
        val names = CollectionNames(mapOf(id(1) to "Synthetic One", id(2) to "Synthetic Two"))

        assertEquals("CollectionNames(2)", names.toString())
        assertFalse(names.toString().contains("Synthetic"))
    }

    @Test
    fun theMapIsCopiedSoLaterChangesToItAreNotSeen() {
        val source = mutableMapOf(id(1) to "Synthetic One")
        val names = CollectionNames(source)

        source[id(2)] = "Synthetic Two"

        assertEquals(setOf(id(1)), names.ids)
    }

    @Test
    fun idsAreInCanonicalIdOrderWhateverTheOrderGiven() {
        val names = CollectionNames(linkedMapOf(id(3) to "C", id(1) to "A", id(2) to "B"))

        assertEquals(listOf(id(1), id(2), id(3)), names.ids.toList())
    }
}
