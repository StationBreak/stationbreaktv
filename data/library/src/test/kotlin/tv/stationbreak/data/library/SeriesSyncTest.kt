// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 10 tasks 1.6.1 and 1.6.2: the series pass runs after each library's item pass, through the same page loop and
 * the same cursor. A failed series page fails the sync like a failed item page, and series are not counted in the
 * first sync's progress (Granny H2).
 */
class SeriesSyncTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val episodes = SyncWorld.source("e", 250, SHOWS)
    private val seriesSource = SeriesSource((1..130).map(::seriesNumber).toMutableList())
    private val server = PagedMediaServer(listOf(shows), mapOf(SHOWS to episodes))

    init {
        server.seriesSources[SHOWS] = seriesSource
    }

    private fun seriesNumber(n: Int): SeriesInfo {
        val id = "s%03d".format(java.util.Locale.ROOT, n)
        return series(id, "TV-14", listOf("g${n % 3}"), listOf("t"))
    }

    @After
    fun close() = repository.close()

    private suspend fun firstSync(): SyncOutcome {
        repository.useAccount(ALICE)
        return SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(SHOWS))
    }

    private fun stay() = StayCurrentWorld.engine(repository, server, clock)

    @Test
    fun theFirstSyncStoresEverySeriesAfterTheItemsOfItsLibrary() =
        runBlocking {
            assertEquals(SyncOutcome.Completed, firstSync())

            val stored = repository.currentSeries(ALICE)
            assertEquals(130, stored.size)
            assertEquals("TV-14", stored.first().officialRating)
            assertEquals(listOf("g1"), VersionCodec.decode(stored.first().genres))
            assertEquals(250, repository.currentRows(ALICE).size)
            // Every item call of the library precedes every series call.
            val kinds = server.calls.filter { !it.idsOnly }.map { it.series }
            assertEquals(kinds.sorted(), kinds)
            assertTrue(kinds.last())
        }

    @Test
    fun seriesAreNotCountedInTheFirstSyncsProgress() =
        runBlocking {
            var atFirstSeriesCall: SyncState? = null
            server.respond = { call ->
                if (call.series && atFirstSeriesCall == null) {
                    atFirstSeriesCall = runBlocking { repository.activeStatus().first()?.state }
                }
                null
            }

            assertEquals(SyncOutcome.Completed, firstSync())

            // By the time the series are asked for, all 250 items are copied and the total is the items' 250, not 380.
            assertEquals(SyncState.FirstSyncRunning(copied = 250, total = 250), atFirstSeriesCall)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
        }

    @Test
    fun aFailedSeriesPageFailsTheFirstSyncAndLeavesTheLibraryPending() =
        runBlocking {
            server.respond = { if (it.series && !it.idsOnly) MediaServerError.Timeout() else null }

            val outcome = firstSync()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertEquals(setOf(SHOWS), repository.pendingLibraries(ALICE))
            assertEquals(250, repository.currentRows(ALICE).size)
        }

    @Test
    fun aFirstSyncThatFailedInTheSeriesPassResumesAndFinishesThem() =
        runBlocking {
            var fail = true
            server.respond = { if (fail && it.series && it.startIndex == 100) MediaServerError.Timeout() else null }
            assertEquals(SyncOutcome.Failed(SyncFailure.Network), firstSync())
            fail = false

            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(SHOWS))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(130, repository.currentSeries(ALICE).size)
            assertTrue(repository.pendingLibraries(ALICE).isEmpty())
        }

    @Test
    fun anIncrementalPassFetchesASeriesEditedAfterTheCursorAndOnlyThat() =
        runBlocking {
            firstSync()
            server.calls.clear()
            clock.instant += 1.hours
            val edited = series("s007", "TV-MA", listOf("Edited"), listOf("t"))
            seriesSource.series[6] = edited
            seriesSource.saved[edited.id] = clock.now() - 1.minutes

            val result = stay().incremental(session, ALICE, setOf(SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            val row = repository.currentSeries(ALICE).single { it.seriesId == "s007" }
            assertEquals("TV-MA", row.officialRating)
            assertEquals(1, repository.seriesVersions(ALICE).size)
            val seriesCalls = server.calls.filter { it.series }
            assertTrue(seriesCalls.all { it.changedSince != null })
        }

    @Test
    fun aFailedSeriesPageFailsTheIncrementalPassLikeAFailedItemPage() =
        runBlocking {
            firstSync()
            clock.instant += 1.hours
            server.respond = { if (it.series) MediaServerError.Timeout() else null }

            val result = stay().incremental(session, ALICE, setOf(SHOWS))

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertNotNull(repository.activeStatus().first())
        }

    @Test
    fun aNewSeriesOnTheServerIsStoredByTheNextIncrementalPass() =
        runBlocking {
            firstSync()
            clock.instant += 1.hours
            val added = series("s999", "G", listOf("New"), emptyList())
            seriesSource.series += added
            seriesSource.saved[added.id] = clock.now() - 1.minutes

            stay().incremental(session, ALICE, setOf(SHOWS))

            assertEquals(131, repository.currentSeries(ALICE).size)
            assertEquals(listOf(ItemId("s999")), repository.allSeriesIds(ALICE).filter { it.value == "s999" })
        }
}
