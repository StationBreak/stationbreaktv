// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import tv.stationbreak.data.library.SyncWorld.home
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * #495: a chosen library the server no longer lists. The reconcile tombstones its items and series (revivable) when the
 * listing is sound and a second read agrees; an empty listing, or one without any chosen library, is suspect and
 * changes nothing. The library stays chosen, so if it comes back its rows revive. A library that is not chosen is
 * never synced, whatever the server lists.
 */
class RemovedLibraryReconcileTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val server =
        SyncWorld.server(
            movies to SyncWorld.source("m", 40),
            shows to SyncWorld.source("s", 30, SHOWS),
            home to SyncWorld.source("h", 10, home.id),
        )
    private val chosen = setOf(MOVIES, SHOWS)

    init {
        server.seriesSources[SHOWS] = SeriesSource((1..5).map { series("s$it", "TV-14", listOf("g")) }.toMutableList())
        server.libraries = listOf(movies, shows)
    }

    @After
    fun close() = repository.close()

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, chosen)
        assertEquals(SyncOutcome.Completed, outcome)
        server.calls.clear()
        clock.instant += 1.hours
    }

    // What the last reconcile() did.
    private var summary = ReconcileSummary()

    private suspend fun reconcile(over: MediaServer = server): SyncOutcome {
        val result = StayCurrentEngine(repository, over, clock, pageSize = 100).reconcile(session, ALICE, chosen)
        summary = result.summary
        return result.outcome
    }

    private suspend fun tombstonedIn(library: ItemId) =
        repository.stored(ALICE).count { it.library == library && it.deletedAt != null }

    private suspend fun tombstonedSeries() = repository.currentSeries(ALICE).count { it.deletedAtMs != null }

    @Test
    fun aChosenLibraryTheServerNoLongerListsIsTombstonedWithItsSeries() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(30, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedIn(MOVIES))
            assertEquals(5, tombstonedSeries())
            assertEquals(ReconcileSummary(tombstoned = 30, seriesTombstoned = 5), summary)
            assertEquals(40, repository.snapshot(ALICE, chosen, EPOCH).size)
        }

    @Test
    fun aLibraryThatIsNotChosenIsNeverSynced() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies, shows, home)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(ReconcileSummary(), summary)
            assertTrue(server.calls.none { it.library == home })
            assertTrue(repository.stored(ALICE).none { it.library == home.id })
        }

    @Test
    fun aLibraryThatComesBackIsRevived() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            reconcile()
            server.libraries = listOf(movies, shows)
            clock.instant += 1.hours

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(ReconcileSummary(revived = 30, seriesRevived = 5), summary)
        }

    @Test
    fun anEmptyListingTombstonesNothingAndFailsAsSuspect() =
        runBlocking {
            firstSyncDone()
            server.libraries = emptyList()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertEquals(0, tombstonedIn(SHOWS) + tombstonedIn(MOVIES))
            assertEquals(0, tombstonedSeries())
        }

    @Test
    fun aListingWithNoChosenLibraryTombstonesNothingAndFailsAsSuspect() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(home)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertEquals(0, tombstonedIn(SHOWS) + tombstonedIn(MOVIES))
            assertEquals(0, tombstonedSeries())
        }

    @Test
    fun aLibraryMissingFromTheFirstListingButPresentInTheSecondIsKept() =
        runBlocking {
            firstSyncDone()
            var reads = 0
            val flaky =
                object : MediaServer by server {
                    override suspend fun libraries(session: Session): ServerResult<List<Library>> {
                        reads += 1
                        server.libraries = if (reads == 1) listOf(movies) else listOf(movies, shows)
                        return server.libraries(session)
                    }
                }

            val outcome = reconcile(flaky)

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(2, reads)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(ReconcileSummary(), summary)
        }

    @Test
    fun aLibraryStillMissingOnTheNextRunIsLeftAloneNotTombstonedAgain() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)

            reconcile()
            reconcile()

            assertEquals(30, tombstonedIn(SHOWS))
            assertEquals(ReconcileSummary(), summary)
        }
}
