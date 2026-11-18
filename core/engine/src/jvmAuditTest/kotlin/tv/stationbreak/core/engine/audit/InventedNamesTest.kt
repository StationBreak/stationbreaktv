// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the counts are the libraries'.

package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventedNamesTest {
    // #790: a stress library has more titles than one round of the word pairs, and LibraryBuilder waits for an unused
    // name, so a repeat would never end. Past the first round a name carries its round's number.
    @Test
    fun namesStayDistinctPastOneRoundOfWordPairs() {
        val names = InventedNames()
        val made = List(30_000) { names.next() }
        assertEquals(made.size, made.toSet().size)
    }

    @Test
    fun theFirstRoundIsUnchangedSoTheGoldensStay() {
        val names = InventedNames()
        val first = List(4_096) { names.next() }
        assertTrue(first.none { it.last().isDigit() })
        assertTrue(names.next().endsWith(" 2"))
    }

    @Test
    fun aRoundNumberIsStillAnInventedName() {
        val names = InventedNames()
        assertTrue(List(9_000) { names.next() }.all { InventedNames.isInvented(it) })
        assertFalse(InventedNames.isInvented("Bravelmi Torsenda 1"), "the first round has no number")
        assertFalse(InventedNames.isInvented("Bravelmi Torsenda 02"))
        assertFalse(InventedNames.isInvented("Bravelmi 2"))
    }
}
