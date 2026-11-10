// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Fills.prime` (#684: the early current-row read): the answers of one read taken before the tail pools ask, in a map
 * of their own that only `live` consults.
 */
internal class FillsPrimeTest {
    // Ids 1..20; the even ones have a current row. Every read is recorded.
    private class Rows : LibraryRepository by InMemoryLibraryRepository() {
        val live = (1..20 step 2).map { id(it + 1) }.toSet()
        val reads = mutableListOf<Set<CanonicalId>>()

        override suspend fun currentIds(
            account: LibraryAccount,
            ids: Set<CanonicalId>,
        ): Set<CanonicalId> {
            reads += ids
            return ids.filterTo(HashSet()) { it in live }
        }
    }

    private fun ids(range: IntRange): Set<CanonicalId> = range.mapTo(LinkedHashSet()) { id(it) }

    private fun primed(
        rows: Rows,
        asked: Set<CanonicalId>,
    ): Fills {
        val fills = Fills(rows, ACCOUNT)
        fills.prime(asked, asked.filterTo(HashSet()) { it in rows.live })
        rows.reads.clear()
        return fills
    }

    private fun alone(ids: Set<CanonicalId>): Set<CanonicalId> = runBlocking { Fills(Rows(), ACCOUNT).live(ids) }

    @Test
    fun primeThenLive_equalsLiveAlone_forASubset() {
        val rows = Rows()
        val fills = primed(rows, ids(1..20))
        val ask = ids(5..12)

        assertEquals(alone(ask), runBlocking { fills.live(ask) })
        assertTrue(rows.reads.isEmpty(), "everything asked was primed: no read")
    }

    @Test
    fun primeThenLive_equalsLiveAlone_forASuperset_readingOnlyWhatWasNotPrimed() {
        val rows = Rows()
        val fills = primed(rows, ids(5..12))
        val ask = ids(1..20)

        assertEquals(alone(ask), runBlocking { fills.live(ask) })
        assertEquals(listOf(ids(1..4) + ids(13..20)), rows.reads)
        assertEquals(ids(1..4).size + ids(13..20).size, fills.primeMisses)
    }

    @Test
    fun primeThenLive_equalsLiveAlone_forADisjointSet() {
        val rows = Rows()
        val fills = primed(rows, ids(1..8))
        val ask = ids(9..20)

        assertEquals(alone(ask), runBlocking { fills.live(ask) })
    }

    @Test
    fun primedAnswers_stayOutOfWhatLiveHasAsked() {
        val rows = Rows()
        val fills = primed(rows, ids(1..20))

        assertFalse(fills.isLive(id(2)), "isLive answers only from the reads live made, as before")
        assertEquals(setOf(id(2)), runBlocking { fills.live(setOf(id(2))) })
        assertFalse(fills.isLive(id(2)), "a primed answer is not an asked one")
    }

    @Test
    fun anAnswerLiveAlreadyHas_winsOverAPrimedOne() {
        val rows = Rows()
        val fills = Fills(rows, ACCOUNT)
        // Asked first, so known: id(2) has a current row. A later prime saying otherwise does not change it.
        assertEquals(setOf(id(2)), runBlocking { fills.live(setOf(id(2))) })

        fills.prime(setOf(id(2)), emptySet())

        assertEquals(setOf(id(2)), runBlocking { fills.live(setOf(id(2))) })
    }

    @Test
    fun anIdPrimedTwice_keepsItsFirstAnswer() {
        val fills = Fills(Rows(), ACCOUNT)
        fills.prime(setOf(id(2)), setOf(id(2)))

        fills.prime(setOf(id(2)), emptySet())

        assertEquals(setOf(id(2)), runBlocking { fills.live(setOf(id(2))) })
    }

    @Test
    fun droppedPrimedAnswers_areReadAgain() {
        val rows = Rows()
        val fills = Fills(rows, ACCOUNT)
        // A stale answer: id(3) has no current row now, the primed read said it did.
        fills.prime(setOf(id(3)), setOf(id(3)))
        assertEquals(setOf(id(3)), runBlocking { fills.live(setOf(id(3))) })

        fills.dropPrimed()

        assertEquals(emptySet(), runBlocking { fills.live(setOf(id(3))) })
        assertEquals(listOf(setOf(id(3))), rows.reads)
    }
}
