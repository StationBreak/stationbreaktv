// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.data.library.SyncWorld.home
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows

/** Spec 06 task 3.3 (owner answer 2): the first sync fetches the libraries the user ticked, in order, and no others. */
class SelectedLibrariesOnlyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val server =
        SyncWorld.server(
            movies to SyncWorld.source("m", 120),
            shows to SyncWorld.source("s", 60, SHOWS),
            home to SyncWorld.source("h", 30, home.id),
        )

    @After
    fun close() = repository.close()

    private fun engine() = SyncWorld.engine(repository, server, clock, pageSize = 50)

    @Test
    fun onlyTheTickedLibrariesAreAskedFor() =
        runBlocking {
            repository.useAccount(ALICE)

            val outcome = engine().run(session, ALICE, setOf(movies.id, shows.id))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(emptyList<Any>(), server.calls.filter { it.library == home })
            assertEquals(setOf(movies, shows), server.calls.map { it.library }.toSet())
        }

    @Test
    fun theyAreFetchedOneAfterAnotherInTheServersOrderNotTheOrderTheyWereTicked() =
        runBlocking {
            repository.useAccount(ALICE)

            // Ticked shows first, then movies: the sync still walks the server's listing order.
            engine().run(session, ALICE, linkedSetOf(shows.id, movies.id))

            val walked = server.itemCalls.filter { it.changedSince == null }.map { it.library }
            // Each library is read to the empty page that ends it: 120 movies in pages of 50 are four calls.
            assertEquals(listOf(movies, movies, movies, movies, shows, shows, shows), walked)
        }

    @Test
    fun onlyTheTickedLibrariesItemsAreSaved() =
        runBlocking {
            repository.useAccount(ALICE)

            engine().run(session, ALICE, setOf(movies.id))

            assertEquals(120, repository.stored(ALICE).size)
            assertEquals(setOf(MOVIES), repository.stored(ALICE).map { it.library }.toSet())
            assertEquals(emptySet<tv.stationbreak.core.model.ItemId>(), repository.pendingLibraries(ALICE))
        }

    @Test
    fun aLibraryTheUserUntickedIsGoneAndNotFetched() =
        runBlocking {
            repository.useAccount(ALICE)
            engine().run(session, ALICE, setOf(movies.id, shows.id))
            server.calls.clear()

            engine().run(session, ALICE, setOf(movies.id))

            assertEquals(setOf(MOVIES), repository.stored(ALICE).map { it.library }.toSet())
            assertTrue(server.calls.none { it.library == shows })
        }

    @Test
    fun theTotalIsEveryTickedLibrariesCountAndTheCountRisesToIt() =
        runBlocking {
            repository.useAccount(ALICE)
            val seen = mutableListOf<LibraryStatus>()
            server.respond = { call ->
                if (!call.idsOnly) runBlocking { seen += repository.activeStatus().first()!! }
                null
            }

            engine().run(session, ALICE, setOf(movies.id, shows.id))

            // The total was known before the first page was asked for: 120 movies and 60 shows.
            assertEquals(SyncState.FirstSyncRunning(copied = 0, total = 180), seen.first().state)
            val copied = seen.take(7).map { (it.state as SyncState.FirstSyncRunning).copied }
            // Movies: three pages and the empty one, then its changed-since pass (still 120); then the shows begin.
            assertEquals(listOf(0, 50, 100, 120, 120, 120, 170), copied)
            assertTrue(seen.all { (it.state as SyncState.FirstSyncRunning).total == 180 })
            val done = repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, done.state)
            assertEquals(clock.now(), done.lastSuccess)
        }
}
