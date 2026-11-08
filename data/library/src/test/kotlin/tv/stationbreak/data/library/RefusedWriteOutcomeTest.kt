// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Part 4 (schema 11, MED-8): what a pass does when a library row refuses one of its writes (the library
 * was held, dropped or stamped while the pass ran). A stay-current pass stops as Cancelled with nothing recorded: no
 * failure, no new last success, the backfill flag kept. The first sync stops that library's walk without marking it
 * fetched and goes on with the next one (task 4.4b gives that its own outcome). Invented libraries and names only.
 */
class RefusedWriteOutcomeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 3, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))

    @After
    fun close() = repository.close()

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        assertEquals(
            SyncOutcome.Completed,
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, SHOWS)),
        )
        server.calls.clear()
    }

    // Before the first item page of SHOWS is served, SHOWS is unticked and held: the page's write is refused.
    private fun holdShowsOnItsFirstPage() {
        var done = false
        server.respond = { call ->
            val itemPage = !call.idsOnly && !call.series
            if (!done && call.library.id == SHOWS && itemPage) {
                done = true
                runBlocking {
                    repository.applySelection(
                        ALICE,
                        setOf(MOVIES),
                        holds = mapOf(SHOWS to clock.nowMs + 60_000),
                        nowMs = clock.nowMs,
                    )
                }
            }
            null
        }
    }

    @Test
    fun stayCurrent_incremental_mismatch_cancelled_nothingRecorded() =
        runBlocking {
            firstSyncDone()
            val before = repository.status(ALICE).first()
            clock.instant += 1.hours
            episodes.items += SyncWorld.item("s", 10, SHOWS).copy(dateCreated = clock.now())
            holdShowsOnItsFirstPage()

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            val after = repository.status(ALICE).first()
            assertEquals("no failure recorded", SyncState.Idle, after.state)
            assertEquals("the recorded moment did not move", before.lastSuccess, after.lastSuccess)
            assertFalse(
                "nothing written to the held library",
                repository.stored(ALICE).any { it.item.id.value == "s-00010" },
            )
        }

    @Test
    fun stayCurrent_backfill_mismatch_cancelled_flagKept() =
        runBlocking {
            firstSyncDone()
            repository.dao.setBackfillPending("server-1", "alice", true)
            holdShowsOnItsFirstPage()

            val result = engine().backfill(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertTrue("the flag stays set", repository.backfillPending(ALICE))
            assertEquals(SyncState.Idle, repository.status(ALICE).first().state)
        }

    @Test
    fun firstSync_mismatch_stopsThatLibrary_withoutMarkingIt_andGoesOn() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            // MOVIES is walked first, then SHOWS; SHOWS is held before its first page is written.
            holdShowsOnItsFirstPage()

            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, outcome)
            val rows = repository.dao.libraries("server-1", "alice").associateBy { it.libraryId }
            assertTrue(rows.getValue(MOVIES.value).fetched)
            assertFalse("the refused library is left pending", rows.getValue(SHOWS.value).fetched)
            assertTrue(repository.stored(ALICE).none { it.library == SHOWS })
            assertEquals(3, repository.stored(ALICE).count { it.library == MOVIES })
        }
}
