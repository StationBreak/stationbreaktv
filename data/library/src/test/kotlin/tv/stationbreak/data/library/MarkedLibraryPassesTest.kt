// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Part 4 (schema 11, §4 step 4, LOW-15): a chosen library marked for a rewalk is left to the rewalk by the
 * stay-current passes. The incremental, the backfill and the reconcile read the marks from the stored rows at their
 * start and leave the marked library out: no page of it is asked for, and the reconcile neither tombstones its rows
 * nor takes it for a library the server removed. The backfill counts the marked library as covered (its rewalk reads
 * it whole), and keeps its flag set while an unmarked chosen library is not listed.
 * Invented libraries and names only.
 */
class MarkedLibraryPassesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 3, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))

    @After
    fun close() = repository.close()

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    // Both libraries synced, then SHOWS is marked for a rewalk (an unproven add reverted, as the selection does).
    private suspend fun markedShows() {
        repository.useAccount(ALICE)
        assertEquals(
            SyncOutcome.Completed,
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES)),
        )
        repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 2L), nowMs = clock.nowMs)
        repository
            .writePage(
                ALICE,
                SHOWS,
                stamp = 2L,
                rewalk = false,
                episodes.items,
                3,
                3,
                run = clock.nowMs,
            ).written()
        assertTrue(repository.revertUnproven(ALICE, SHOWS, 2L))
        server.calls.clear()
    }

    private fun askedFor() = server.calls.map { it.library.id }.toSet()

    @Test
    fun incremental_leavesOutAMarkedLibrary() =
        runBlocking {
            markedShows()
            clock.instant += 1.hours

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(setOf(MOVIES), askedFor())
        }

    // Binding item 4 (Task 4.4b): the marked library is covered, as its rewalk reads it from the start with studios.
    @Test
    fun backfill_leavesOutAMarkedLibrary_andCountsItCovered() =
        runBlocking {
            markedShows()
            repository.dao.setBackfillPending("server-1", "alice", true)

            val result = engine().backfill(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(setOf(MOVIES), askedFor())
            assertFalse("the rewalk covers the marked library: none owed", repository.backfillPending(ALICE))
        }

    // A chosen library the server does not list is not covered: the re-read stays owed.
    @Test
    fun backfill_anUnlistedUnmarkedLibrary_keepsTheFlag() =
        runBlocking {
            markedShows()
            repository.dao.setBackfillPending("server-1", "alice", true)
            server.libraries = listOf(shows)

            val result = engine().backfill(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(repository.backfillPending(ALICE))
        }

    @Test
    fun reconcile_leavesOutAMarkedLibrary_neverTombstonesIt() =
        runBlocking {
            markedShows()
            // The server lists SHOWS empty and does not list it as a library: neither may touch its rows while marked.
            episodes.items.clear()
            server.libraries = listOf(movies)

            val result = engine().reconcile(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(askedFor().none { it == SHOWS })
            val shows = repository.stored(ALICE).filter { it.library == SHOWS }
            assertEquals(3, shows.size)
            assertTrue("no row of the marked library is tombstoned", shows.all { it.deletedAt == null })
        }
}
