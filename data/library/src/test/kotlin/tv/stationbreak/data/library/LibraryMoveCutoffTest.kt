// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Spec 09 task 2.9 (Vimes H4): version selection runs over every item of the account, never by library, and the
 * libraries filter applies to the **selected** version. An item moved from library A to B after the cut-off is returned
 * for A and not for B for day n, in both reconcile orders.
 */
class LibraryMoveCutoffTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val t0 = clock.nowMs
    private val hour = 1.hours.inWholeMilliseconds
    private val a = ItemId(hexId(1))

    @After
    fun close() = repository.close()

    private suspend fun ids(
        library: ItemId,
        cutoffMs: Long,
    ) = repository
        .scheduleItems(ALICE, setOf(library), Instant.fromEpochMilliseconds(cutoffMs))
        .map { it.id.value }

    private suspend fun both() = repository.select(ALICE, setOf(MOVIES, SHOWS))

    @Test
    fun anItemMovedAfterTheCutoffIsInTheOldLibraryAndNotTheNewOneForDayN() =
        runBlocking {
            both()
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(hour)
            val movedAt = clock.nowMs
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            val dayN = movedAt - 10 * 60_000
            val dayN1 = movedAt + 10 * 60_000

            assertEquals(listOf(hexId(1)), ids(MOVIES, dayN))
            assertEquals(emptyList<String>(), ids(SHOWS, dayN))
            assertEquals(emptyList<String>(), ids(MOVIES, dayN1))
            assertEquals(listOf(hexId(1)), ids(SHOWS, dayN1))
        }

    @Test
    fun theReconcileOrderWhereATombstonesTheItemBeforeBsPageRevivesIt() =
        runBlocking {
            both()
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(hour)
            // A's reconcile no longer lists the item: it is tombstoned in A.
            repository.tombstone(ALICE, listOf(a))
            val tombstonedAt = clock.nowMs
            clock.stepMs(60_000)
            // B's page then lists it: the row moves to B and the tombstone clears.
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            val movedAt = clock.nowMs

            // A cut-off before the tombstone: held in A (not yet deleted as far as that version knew), never in B.
            assertEquals(listOf(hexId(1)), ids(MOVIES, tombstonedAt - 1))
            assertEquals(emptyList<String>(), ids(SHOWS, tombstonedAt - 1))
            // Between the tombstone and the move: deleted before the cut-off, back only after it, so in neither.
            assertEquals(emptyList<String>(), ids(MOVIES, tombstonedAt + 1))
            assertEquals(emptyList<String>(), ids(SHOWS, tombstonedAt + 1))
            // After the move: in B only.
            assertEquals(emptyList<String>(), ids(MOVIES, movedAt + 1))
            assertEquals(listOf(hexId(1)), ids(SHOWS, movedAt + 1))
        }

    @Test
    fun theOtherOrderWhereBsPageComesFirstMovesItWithNoTombstone() =
        runBlocking {
            both()
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(hour)
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            val movedAt = clock.nowMs
            clock.stepMs(60_000)
            // A's reconcile runs after: the item is not in A any more, but the row is B's now, so a tombstone of A's
            // list must not be applied to it. Nothing in this repository call does so; the read must not change.
            assertEquals(listOf(hexId(1)), ids(MOVIES, movedAt - 1))
            assertEquals(listOf(hexId(1)), ids(SHOWS, movedAt + 1))
            assertEquals(emptyList<String>(), ids(MOVIES, movedAt + 1))
        }

    @Test
    fun anItemInAnUntickedLibraryIsNotReturnedButAMovedOneIsFoundThroughItsHeldVersion() =
        runBlocking {
            both()
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            clock.stepMs(hour)
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            val cutoff = clock.nowMs - 1

            val movies = ids(MOVIES, cutoff)
            val shows = ids(SHOWS, cutoff)

            assertEquals(listOf(hexId(1), hexId(2)), movies)
            assertEquals(emptyList<String>(), shows)
            assertEquals(listOf(hexId(1), hexId(2)), repository.scheduleItems(ALICE, setOf(MOVIES, SHOWS), Instant.fromEpochMilliseconds(cutoff)).map { it.id.value })
        }

    @Test
    fun movingBackAndForthKeepsEachCutoffOnItsOwnLibrary() =
        runBlocking {
            both()
            val moves = mutableListOf<Pair<Long, ItemId>>()
            repository.put(ALICE, MOVIES, sitem(1))
            moves += clock.nowMs to MOVIES
            var inMovies = true
            repeat(6) {
                clock.stepMs(hour)
                inMovies = !inMovies
                val library = if (inMovies) MOVIES else SHOWS
                repository.put(ALICE, library, sitem(1, library = library))
                moves += clock.nowMs to library
            }

            // For a cut-off just after each move, the item is in the library it moved to, and only there.
            moves.forEach { (movedAt, library) ->
                val other = if (library == MOVIES) SHOWS else MOVIES
                assertEquals("after the move at $movedAt", listOf(hexId(1)), ids(library, movedAt + 1))
                assertEquals("after the move at $movedAt", emptyList<String>(), ids(other, movedAt + 1))
            }
            repository.assertIntervalsContiguous(ALICE)
            assertEquals(t0, moves.first().first)
        }
}
