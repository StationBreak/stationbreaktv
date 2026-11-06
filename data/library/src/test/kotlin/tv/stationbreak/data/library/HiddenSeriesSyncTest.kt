// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue 604: Jellyfin can hold one show as two series items and its series lists return only one of them, so episodes
 * of the other reference a series the sync never saw. The sync reads such a series directly by its ID. These tests pin
 * that it is stored, that it is read once per pass however many episodes name it, and that a series stored from outside
 * the list is not tombstoned and stored again on every reconcile (no flapping) while a really deleted one still is.
 */
class HiddenSeriesSyncTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val listed = ItemId(hexId(0x5001))
    private val hidden = ItemId(hexId(0x5002))
    private val episodes = ListLibrarySource(mutableListOf())
    private val listedSeries = SeriesInfo(listed, "Listed Show", "TV-14", listOf("Drama"), emptyList(), emptyList())
    private val hiddenSeries = SeriesInfo(hidden, "Hidden Show", "TV-PG", listOf("Comedy"), listOf("t"), emptyList())
    private val server = PagedMediaServer(listOf(shows), mapOf(SHOWS to episodes))

    init {
        for (n in 1..3) episodes.items += episode(0x1000 + n, listed)
        for (n in 1..40) episodes.items += episode(0x2000 + n, hidden)
        server.seriesSources[SHOWS] = SeriesSource(mutableListOf(listedSeries))
        server.hiddenSeriesSources[hidden] = hiddenSeries
    }

    @After
    fun close() = repository.close()

    private fun episode(
        n: Int,
        series: ItemId,
    ) = MediaItem(
        id = ItemId(hexId(n)),
        name = "Episode $n",
        kind = ItemKind.Episode,
        runtime = 30.minutes,
        parentId = SHOWS,
        dateCreated = EPOCH - 1.hours,
        seriesId = series,
        seasonNumber = 1,
        episodeNumber = n % 100,
    )

    private suspend fun firstSync(): SyncOutcome {
        repository.useAccount(ALICE)
        return SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(SHOWS))
    }

    private suspend fun reconcile(): StayResult<ReconcileSummary> {
        clock.instant += 1.hours
        return StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, setOf(SHOWS))
    }

    private suspend fun seriesRow(id: ItemId) = repository.currentSeries(ALICE).singleOrNull { it.seriesId == id.value }

    @Test
    fun theFirstSyncStoresASeriesOnlyTheDirectReadFinds() =
        runBlocking {
            assertEquals(SyncOutcome.Completed, firstSync())

            val row = seriesRow(hidden)
            assertEquals("Hidden Show", row?.name)
            assertEquals(listOf("Comedy"), row?.genres?.let { VersionCodec.decode(it) })
            assertEquals(SHOWS.value, row?.libraryId)
            assertNull(row?.deletedAtMs)
        }

    @Test
    fun anEpisodeOfTheHiddenSeriesNowKnowsItsSeriesNameAndTheSnapshotHasNoOrphans() =
        runBlocking {
            firstSync()

            val info = repository.programInfo(ALICE, setOf(CanonicalId.parse(hexId(0x2001))))
            assertEquals("Hidden Show", info.values.single().seriesName)
            val snapshot = repository.ruleSnapshot(ALICE, setOf(SHOWS), EPOCH + 2.hours)
            val orphans = snapshot.items.count { it.item.seriesId != null && it.item.seriesId !in snapshot.series }
            assertEquals(0, orphans)
            assertEquals(43, snapshot.items.size)
        }

    @Test
    fun manyEpisodesOfOneHiddenSeriesCostOneDirectReadPerPass() =
        runBlocking {
            firstSync()
            assertEquals(listOf(hidden), server.seriesByIdCalls)

            reconcile()
            assertEquals(listOf(hidden, hidden), server.seriesByIdCalls)
        }

    @Test
    fun reconcilingTwiceKeepsTheSeriesLiveWithNoChurn() =
        runBlocking {
            firstSync()
            val before = repository.currentSeries(ALICE)

            val first = reconcile()
            val second = reconcile()

            assertEquals(SyncOutcome.Completed, first.outcome)
            assertEquals(SyncOutcome.Completed, second.outcome)
            assertEquals(before, repository.currentSeries(ALICE))
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(0, first.summary.seriesTombstoned + second.summary.seriesTombstoned)
            assertEquals(0, first.summary.seriesRevived + second.summary.seriesRevived)
            assertEquals(0, first.summary.tombstoned + second.summary.tombstoned)
            assertTrue(repository.liveItemIds(ALICE, SHOWS).size == 43)
        }

    @Test
    fun aHiddenSeriesTheServerDeletedIsTombstonedWhileAnotherIsStillThere() =
        runBlocking {
            val doomed = ItemId(hexId(0x5009))
            server.hiddenSeriesSources[doomed] =
                SeriesInfo(doomed, "Doomed", null, emptyList(), emptyList(), emptyList())
            episodes.items += episode(0x4001, doomed)
            firstSync()
            server.hiddenSeriesSources.remove(doomed)

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(1, result.summary.seriesTombstoned)
            assertEquals(clock.nowMs, seriesRow(doomed)?.deletedAtMs)
            assertNull(seriesRow(hidden)?.deletedAtMs)
        }

    @Test
    fun whenEveryUnlistedSeriesIsAbsentNoneIsTombstonedForIt() =
        runBlocking {
            firstSync()
            server.hiddenSeriesSources.remove(hidden)

            val result = reconcile()

            // One missing series and nothing else read is more likely a server fault than a deleted show.
            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertNull(seriesRow(hidden)?.deletedAtMs)
        }

    @Test
    fun collectionMembershipsOfAHiddenSeriesSurviveTwoReconciles() =
        runBlocking {
            firstSync()
            val members = listOf(cm(hidden.value, CollectionMemberKind.Series))
            repository.writeCollection(ALICE, ItemId("box01"), "Box", members)

            reconcile()
            reconcile()

            val rows = repository.collectionMembers(ALICE).filter { it.memberId == hidden.value }
            assertEquals(1, rows.size)
            assertNull(rows.single().supersededAtMs)
        }

    @Test
    fun aSeriesNoLiveEpisodeNamesAndTheListLacksIsStillTombstoned() =
        runBlocking {
            firstSync()
            episodes.items.removeAll { it.seriesId == hidden }

            // The episodes go in this pass, and they still count as naming the series while it decides.
            val first = reconcile()
            assertEquals(40, first.summary.tombstoned)
            assertNull(seriesRow(hidden)?.deletedAtMs)
            val readsAfterFirst = server.seriesByIdCalls.size

            val second = reconcile()

            assertEquals(SyncOutcome.Completed, second.outcome)
            assertEquals(1, second.summary.seriesTombstoned)
            assertEquals(clock.nowMs, seriesRow(hidden)?.deletedAtMs)
            // Nothing names it any more, so there is nothing to ask the server about.
            assertEquals(readsAfterFirst, server.seriesByIdCalls.size)
        }

    @Test
    fun aFailedDirectReadInAReconcileTombstonesNothingAndFailsLikeAnyTransientFailure() =
        runBlocking {
            firstSync()
            server.respondToSeriesById = { MediaServerError.Timeout() }
            val before = repository.currentSeries(ALICE)

            val result = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertEquals(before, repository.currentSeries(ALICE))
            // And once the server answers again the series is simply still there.
            server.respondToSeriesById = { null }
            assertEquals(SyncOutcome.Completed, reconcile().outcome)
            assertNull(seriesRow(hidden)?.deletedAtMs)
        }

    @Test
    fun aFailedDirectReadInTheFirstSyncFailsItAndTheRetryFinishesTheJob() =
        runBlocking {
            server.respondToSeriesById = { MediaServerError.Timeout() }

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), firstSync())

            server.respondToSeriesById = { null }
            assertEquals(SyncOutcome.Completed, firstSync())
            assertEquals("Hidden Show", seriesRow(hidden)?.name)
        }

    @Test
    fun theIncrementalPassStoresAHiddenSeriesThatAppearsLater() =
        runBlocking {
            firstSync()
            val later = ItemId(hexId(0x5003))
            server.hiddenSeriesSources[later] =
                SeriesInfo(later, "Later Show", null, emptyList(), emptyList(), emptyList())
            val fresh = episode(0x3001, later).copy(dateCreated = EPOCH + 30.minutes)
            episodes.items += fresh
            clock.instant += 1.hours

            val result = StayCurrentWorld.engine(repository, server, clock).incremental(session, ALICE, setOf(SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals("Later Show", seriesRow(later)?.name)
            // The hidden series that is already stored is not read again: only the new orphan.
            assertEquals(listOf(hidden, later), server.seriesByIdCalls)
        }

    @Test
    fun theLookupsPerPassAreCapped() =
        runBlocking {
            repository.useAccount(ALICE)
            val many = HIDDEN_LOOKUPS_PER_PASS + 5
            for (n in 1..many) {
                val id = ItemId(hexId(0x6000 + n))
                server.hiddenSeriesSources[id] = SeriesInfo(id, "Show $n", null, emptyList(), emptyList(), emptyList())
                episodes.items += episode(0x7000 + n, id)
            }

            assertEquals(SyncOutcome.Completed, firstSync())

            // The one from the setup, then the rest up to the cap, in a fixed order.
            assertEquals(HIDDEN_LOOKUPS_PER_PASS, server.seriesByIdCalls.size)
            assertEquals(HIDDEN_LOOKUPS_PER_PASS, repository.currentSeries(ALICE).size - 1)
        }
}
