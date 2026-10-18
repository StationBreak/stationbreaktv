// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 10 task 1.7.1: the item reconcile never sees series IDs. Its sets (live, tombstoned, known) come from the `item`
 * table, so a series ID is neither tombstoned as a missing item nor fetched as an item the account lacks, and
 * removing an item or a series on the server touches only its own table.
 */
class ItemReconcileIgnoresSeriesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val episodes = SyncWorld.source("e", 30, SHOWS)
    private val seriesSource = SeriesSource(mutableListOf())
    private val server = PagedMediaServer(listOf(shows), mapOf(SHOWS to episodes))

    init {
        (1..10).forEach { seriesSource.series += series("s%02d".format(java.util.Locale.ROOT, it)) }
        server.seriesSources[SHOWS] = seriesSource
    }

    @After
    fun close() = repository.close()

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(SHOWS))
        server.calls.clear()
        clock.instant += 1.hours
    }

    private suspend fun reconcile(): StayResult<ReconcileSummary> {
        val engine = StayCurrentWorld.engine(repository, server, clock)
        return engine.reconcile(session, ALICE, setOf(SHOWS))
    }

    @Test
    fun theItemSetsHoldNoSeriesIdAndNoItemIsWalkedForWhenNothingIsMissing() =
        runBlocking {
            firstSyncDone()

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(repository.allItemIds(ALICE).none { it.value.startsWith("s") })
            assertTrue(repository.liveItemIds(ALICE, SHOWS).none { it.value.startsWith("s") })
            // The item listing was read and no item page was asked for: a series ID was not taken for a missing item.
            assertEquals(0, server.calls.count { !it.idsOnly && !it.series })
            assertEquals(ReconcileSummary(), result.summary)
        }

    @Test
    fun aMissingItemTombstonesOnlyAnItemAndAMissingSeriesOnlyASeries() =
        runBlocking {
            firstSyncDone()
            episodes.items.removeAt(4)

            val first = reconcile()

            assertEquals(ReconcileSummary(tombstoned = 1), first.summary)
            assertEquals(0, repository.currentSeries(ALICE).count { it.deletedAtMs != null })
            seriesSource.series.removeAt(2)
            clock.instant += 1.hours

            val second = reconcile()

            assertEquals(ReconcileSummary(seriesTombstoned = 1), second.summary)
            assertEquals(1, repository.stored(ALICE).count { it.deletedAt != null })
        }

    // Granny M1 and Vimes L-2: a series listing that fails must not stop the item reconcile's own work.
    @Test
    fun aFailedSeriesListingStillLetsTheMissingItemsBeFetched() =
        runBlocking {
            firstSyncDone()
            episodes.items += SyncWorld.item("e", 999, SHOWS)
            server.respond = { call ->
                if (call.series && call.idsOnly) MediaServerError.Timeout() else null
            }

            val result = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertTrue(repository.stored(ALICE).any { it.item.id.value == "e-00999" })
            assertEquals(1, result.summary.fetched)
        }
}
