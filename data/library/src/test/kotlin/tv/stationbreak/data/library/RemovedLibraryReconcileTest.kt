// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
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
    private val showsSource = SyncWorld.source("s", 30, SHOWS)
    private val server =
        SyncWorld.server(
            movies to SyncWorld.source("m", 40),
            shows to showsSource,
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

    private suspend fun tombstonedIn(id: ItemId): Int {
        val rows = repository.stored(ALICE)
        return rows.count { it.library == id && it.deletedAt != null }
    }

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
    fun noLibraryExistingIsSuspectNotAWipe() =
        runBlocking {
            firstSyncDone()
            server.libraries = emptyList()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertEquals(0, tombstonedIn(SHOWS) + tombstonedIn(MOVIES))
            assertEquals(0, tombstonedSeries())
        }

    @Test
    fun noChosenLibraryExistingIsSuspectNotAWipe() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(home)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertEquals(0, tombstonedIn(SHOWS) + tombstonedIn(MOVIES))
            assertEquals(0, tombstonedSeries())
        }

    // Answers the removal check from [reads], one list per call; the last list repeats. Counts the calls.
    private inner class ScriptedViews(
        private val reads: List<ServerResult<Set<ItemId>>>,
    ) : MediaServer by server {
        var calls = 0

        override suspend fun existingLibraryIds(
            session: Session,
            candidates: Set<ItemId>,
        ): ServerResult<Set<ItemId>> {
            calls += 1
            return reads[minOf(calls, reads.size) - 1]
        }
    }

    private fun ids(vararg raw: String): ServerResult<Set<ItemId>> = ServerResult.Success(raw.map(::ItemId).toSet())

    @Test
    fun aLibraryMissingFromTheFirstReadButPresentInTheSecondIsKept() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            val views = ScriptedViews(listOf(ids(MOVIES.value), ids(MOVIES.value, SHOWS.value)))

            val outcome = reconcile(views)

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(2, views.calls)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(ReconcileSummary(), summary)
        }

    @Test
    fun aSecondReadThatFailsFailsThePassAndTombstonesNothing() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            val views = ScriptedViews(listOf(ids(MOVIES.value), ServerResult.Failure(MediaServerError.Timeout())))

            val outcome = reconcile(views)

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
        }

    @Test
    fun aSecondReadWithNoChosenLibraryIsSuspectAndTombstonesNothing() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            val views = ScriptedViews(listOf(ids(MOVIES.value), ids(home.id.value)))

            val outcome = reconcile(views)

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
        }

    @Test
    fun aHiddenLibraryThatIsStillOnTheServerIsNotTombstoned() =
        runBlocking {
            firstSyncDone()
            // The listing leaves out a hidden (or unrecognised) view; the removal check still sees it.
            server.libraries = listOf(movies)
            server.extraExistingLibraryIds = setOf(SHOWS)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(ReconcileSummary(), summary)
        }

    @Test
    fun anItemAnotherListedLibraryStillHoldsStaysLive() =
        runBlocking {
            // One ID in both movies (listed) and shows (removed): the row belongs to one library only.
            val shared = SyncWorld.item("m", 0, SHOWS)
            showsSource.items += shared
            firstSyncDone()
            assertEquals(SHOWS, repository.stored(ALICE).single { it.item.id == shared.id }.library)
            server.libraries = listOf(movies)

            reconcile()

            assertEquals(30, tombstonedIn(SHOWS))
            assertEquals(null, repository.stored(ALICE).single { it.item.id == shared.id }.deletedAt)
            assertEquals(ReconcileSummary(tombstoned = 30, seriesTombstoned = 5), summary)
        }

    @Test
    fun aLibraryWithSeriesButNoItemsIsTombstoned() =
        runBlocking {
            val seriesOnly = SyncWorld.source("e", 0, home.id)
            val world = SyncWorld.server(movies to SyncWorld.source("m", 40), home to seriesOnly)
            world.seriesSources[home.id] = SeriesSource(mutableListOf(series("h1", "TV-14", listOf("g"))))
            world.libraries = listOf(movies, home)
            repository.useAccount(ALICE)
            val chosenBoth = setOf(MOVIES, home.id)
            val first = SyncWorld.engine(repository, world, clock).run(session, ALICE, chosenBoth)
            assertEquals(SyncOutcome.Completed, first)
            clock.instant += 1.hours
            world.libraries = listOf(movies)

            val engine = StayCurrentEngine(repository, world, clock, pageSize = 100)
            val result = engine.reconcile(session, ALICE, chosenBoth)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(ReconcileSummary(seriesTombstoned = 1), result.summary)
            assertEquals(1, tombstonedSeries())
        }

    @Test
    fun theGenerationMovesOnceForTheRemovalAndNotAgain() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            val before = repository.contentGeneration(ALICE)

            reconcile()
            val after = repository.contentGeneration(ALICE)
            reconcile()

            assertEquals(before + 1, after)
            assertEquals(after, repository.contentGeneration(ALICE))
        }

    @Test
    fun aListedLibraryThatFailsToReconcileStopsBeforeAnyRemoval() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            server.respond = { call ->
                if (call.library == movies && call.idsOnly) MediaServerError.Timeout() else null
            }

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(0, server.existingLibraryIdsCalls.size)
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

    @Test
    fun twoRemovedLibrariesAreTombstonedInOneTransaction() =
        runBlocking {
            val everything = setOf(MOVIES, SHOWS, home.id)
            server.libraries = listOf(movies, shows, home)
            repository.useAccount(ALICE)
            val first = SyncWorld.engine(repository, server, clock).run(session, ALICE, everything)
            assertEquals(SyncOutcome.Completed, first)
            clock.instant += 1.hours
            server.libraries = listOf(movies)
            val before = repository.contentGeneration(ALICE)
            val engine = StayCurrentEngine(repository, server, clock, pageSize = 100)

            val result = engine.reconcile(session, ALICE, everything)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(ReconcileSummary(tombstoned = 40, seriesTombstoned = 5), result.summary)
            assertEquals(30, tombstonedIn(SHOWS))
            assertEquals(10, tombstonedIn(home.id))
            assertEquals(before + 1, repository.contentGeneration(ALICE))
            // Both reads ask about the chosen IDs.
            assertEquals(listOf(everything, everything), server.existingLibraryIdsCalls)
        }

    @Test
    fun aRemovedLibraryWhoseRowsAreAllHeldByAListedOneCostsNoRequest() =
        runBlocking {
            val both = setOf(MOVIES, home.id)
            // The same IDs in both libraries: the row ends up in the one synced last (home), which is then removed.
            val heldTwice = SyncWorld.source("m", 40, home.id)
            val world = SyncWorld.server(movies to SyncWorld.source("m", 40), home to heldTwice)
            world.libraries = listOf(movies, home)
            repository.useAccount(ALICE)
            assertEquals(SyncOutcome.Completed, SyncWorld.engine(repository, world, clock).run(session, ALICE, both))
            assertEquals(40, repository.stored(ALICE).count { it.library == home.id })
            clock.instant += 1.hours
            world.libraries = listOf(movies)

            val result = StayCurrentEngine(repository, world, clock, pageSize = 100).reconcile(session, ALICE, both)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(ReconcileSummary(), result.summary)
            assertEquals(0, world.existingLibraryIdsCalls.size)
            assertEquals(0, tombstonedIn(home.id))
        }

    @Test
    fun aListedLibraryWhoseIdListCouldNotBeTrustedSkipsTheRemoval() =
        runBlocking {
            firstSyncDone()
            server.libraries = listOf(movies)
            val lying =
                object : MediaServer by server {
                    override suspend fun libraryItemIds(
                        session: Session,
                        library: Library,
                        startIndex: Int,
                        limit: Int,
                    ): ServerResult<ItemIdPage> =
                        server.libraryItemIds(session, library, startIndex, limit).map { page ->
                            if (startIndex == 0) page.copy(totalCount = page.totalCount?.plus(1)) else page
                        }
                }

            val outcome = reconcile(lying)

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(0, tombstonedIn(SHOWS))
            assertEquals(0, tombstonedSeries())
            assertEquals(0, server.existingLibraryIdsCalls.size)
        }
}
