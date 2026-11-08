// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stored selection's rules (spec 26 Part 4, §3), and the in-memory settings that keep them like the file does. */
class StoredSelectionTest {
    private val alpha = ItemId("alpha")
    private val beta = ItemId("beta")
    private val gamma = ItemId("gamma")
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val stored =
        StoredSelection(setOf(alpha), seq = 5, adding = mapOf(beta to 5), missing = mapOf(beta to Missing(1, 1)))

    @Test
    fun aWellFormedSelection_mayReplaceALowerSeq() {
        assertTrue(stored.copy(seq = 6).mayReplace(stored, storedSeq = 5, storedResetSeq = 0))
        assertTrue(StoredSelection(setOf(alpha), seq = 1).mayReplace(null, storedSeq = 0, storedResetSeq = 0))
    }

    @Test
    fun theRulesOnTheirOwn() {
        assertFalse(StoredSelection(emptySet(), seq = 1).isWellFormed)
        assertFalse(stored.copy(adding = mapOf(alpha to 5)).isWellFormed)
        assertFalse(stored.copy(adding = mapOf(beta to 6)).isWellFormed)
        assertFalse(stored.copy(missing = mapOf(gamma to Missing(1, 1))).isWellFormed)
        assertFalse(stored.copy(resetSeq = 6).isWellFormed)
        assertFalse(stored.copy(seq = -1, adding = emptyMap()).isWellFormed)
        assertFalse(stored.copy(listings = -1).isWellFormed)
        assertTrue(stored.isWellFormed)
    }

    @Test
    fun anEqualSeq_isOnlyForBookkeeping() {
        assertTrue(stored.copy(missing = emptyMap(), listings = 2).mayReplace(stored, 5, 0))
        assertFalse(stored.copy(chosen = setOf(alpha, gamma)).mayReplace(stored, 5, 0))
        assertFalse(stored.copy(adding = emptyMap(), missing = emptyMap()).mayReplace(stored, 5, 0))
        assertFalse(stored.copy(gone = true).mayReplace(stored, 5, 0))
        assertFalse(stored.copy(resetSeq = 5).mayReplace(stored, 5, 0))
        // With nothing chosen, any selection is a change.
        assertFalse(stored.mayReplace(null, 5, 0))
    }

    // Fix round 4, item 2: an equal seq never moves the choice to another owner; it may name one where none was stored
    // (the resume's adoption of an older build's record).
    @Test
    fun anEqualSeq_neverChangesTheOwner_butMayNameOneWhereNoneWas() {
        val first = LibraryAccount(ServerId("server-a"), UserId("user-a"))
        val second = LibraryAccount(ServerId("server-a"), UserId("user-b"))
        val owned = stored.copy(owner = first)
        assertTrue(owned.copy(listings = 2).mayReplace(owned, 5, 0))
        assertFalse(owned.copy(owner = second).mayReplace(owned, 5, 0))
        assertFalse(owned.copy(owner = null).mayReplace(owned, 5, 0))
        assertTrue(owned.mayReplace(stored, 5, 0), "an older record adopted")
        // A new seq is a change, and may name any owner.
        assertTrue(owned.copy(seq = 6, owner = second).mayReplace(owned, 5, 0))
    }

    @Test
    fun aLowerSeqOrResetSeq_isRefused() {
        assertFalse(stored.copy(seq = 4, adding = emptyMap(), missing = emptyMap()).mayReplace(stored, 5, 0))
        assertFalse(stored.copy(seq = 6, resetSeq = 1).mayReplace(stored.copy(resetSeq = 2), 5, 2))
    }

    @Test
    fun inMemory_keepsTheSameRules() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(address))
            assertNull(settings.loadSelection())
            assertEquals(0L, settings.loadSelectionSeq())
            assertFalse(settings.saveSelection(StoredSelection(emptySet(), seq = 1)))
            assertTrue(settings.saveSelection(stored.copy(gone = true, listings = 3, resetSeq = 2)))
            assertNull(settings.loadPlayableLibraries())
            assertEquals(setOf(alpha), settings.loadLibraries())
            assertFalse(settings.saveSelection(stored.copy(seq = 4, adding = emptyMap(), missing = emptyMap())))

            // Setup: the next seq, which becomes the reset seq; adding, gone and the misses go; the count stays.
            assertTrue(settings.storeChoice(setOf(gamma)))
            assertEquals(StoredSelection(setOf(gamma), seq = 6, listings = 3, resetSeq = 6), settings.loadSelection())
            assertEquals(setOf(gamma), settings.loadPlayableLibraries())

            // A clear and another address keep the seq.
            assertTrue(settings.clearLibraries())
            assertNull(settings.loadSelection())
            assertEquals(6L, settings.loadSelectionSeq())
            assertTrue(settings.save(ServerRecord(ServerAddress(Scheme.Https, "other.example.test", 443))))
            assertEquals(6L, settings.loadSelectionSeq())
            assertTrue(settings.storeChoice(setOf(alpha)))
            assertEquals(7L, settings.loadSelection()?.seq)
        }

    @Test
    fun inMemory_withoutARecord_hasNoSeqAndStoresNothing() =
        runBlocking {
            val settings = InMemoryServerSettings()
            assertNull(settings.loadSelectionSeq())
            assertFalse(settings.saveSelection(StoredSelection(setOf(alpha), seq = 1)))
        }

    @Test
    fun inMemory_initialLibraries_areASelectionAtSeqZero() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(address), setOf(alpha))
            assertEquals(StoredSelection(setOf(alpha)), settings.loadSelection())
            assertEquals(setOf(alpha), settings.loadPlayableLibraries())
        }
}
