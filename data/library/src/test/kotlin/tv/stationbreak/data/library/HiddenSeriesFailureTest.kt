// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue 604, fix round 1: a series the server cannot give by ID must never stop the sync, two reads (not one) are
 * needed before an unlisted series is called gone, a library whose list is empty can still hold a hidden series, a
 * hidden series is not tombstoned through another library's removal, and the passes remember what they learned.
 */
class HiddenSeriesFailureTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val libA = Library(ItemId(hexId(0xB101)), "Shows A", LibraryKind.TvShows)
    private val libB = Library(ItemId(hexId(0xB102)), "Shows B", LibraryKind.TvShows)
    private val bad = ItemId(hexId(0x4000))
    private val hiddenA = ItemId(hexId(0x5002))
    private val hiddenB = ItemId(hexId(0x5102))
    private val listedA = ItemId(hexId(0x5001))
    private val quiet = ItemId(hexId(0x5000))
    private val episodesA = ListLibrarySource(mutableListOf())
    private val episodesB = ListLibrarySource(mutableListOf())
    private val server = PagedMediaServer(listOf(libA, libB), mapOf(libA.id to episodesA, libB.id to episodesB))
    private val both = setOf(libA.id, libB.id)

    init {
        episodesA.items += episode(0x1001, listedA, libA)
        episodesA.items += (1..5).map { episode(0x1100 + it, bad, libA) }
        episodesA.items += (1..3).map { episode(0x1200 + it, hiddenA, libA) }
        episodesB.items += (1..3).map { episode(0x2000 + it, hiddenB, libB) }
        episodesB.items += episode(0x2100, hiddenB, libB)
        server.seriesSources[libA.id] = SeriesSource(mutableListOf(info(listedA, "Listed"), info(quiet, "Quiet")))
        server.hiddenSeriesSources[hiddenA] = info(hiddenA, "Hidden A")
        server.hiddenSeriesSources[hiddenB] = info(hiddenB, "Hidden B")
        server.hiddenSeriesSources[bad] = info(bad, "Bad")
    }

    @After
    fun close() = repository.close()

    private fun info(
        id: ItemId,
        name: String,
    ) = SeriesInfo(id, name, null, emptyList(), emptyList(), emptyList())

    private fun episode(
        n: Int,
        series: ItemId,
        library: Library,
    ) = MediaItem(
        id = ItemId(hexId(n)),
        name = "Episode $n",
        kind = ItemKind.Episode,
        runtime = 30.minutes,
        parentId = library.id,
        dateCreated = EPOCH - 1.hours,
        seriesId = series,
        seasonNumber = 1,
        episodeNumber = n % 100,
    )

    private suspend fun firstSync(chosen: Set<ItemId> = both): SyncOutcome {
        repository.useAccount(ALICE)
        return SyncWorld.engine(repository, server, clock).run(session, ALICE, chosen)
    }

    private fun stay() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun reconcile(
        engine: StayCurrentEngine = stay(),
        chosen: Set<ItemId> = both,
    ): StayResult<ReconcileSummary> {
        clock.instant += 1.hours
        return engine.reconcile(session, ALICE, chosen)
    }

    private suspend fun row(id: ItemId) = repository.currentSeries(ALICE).singleOrNull { it.seriesId == id.value }

    private fun failBad() {
        server.respondToSeriesById = { if (it == bad) MediaServerError.ServerError() else null }
    }

    @Test
    fun aSeriesThatAlwaysFailsDoesNotStopTheFirstSyncOrTheOnesSortedAfterIt() =
        runBlocking {
            failBad()

            assertEquals(SyncOutcome.Completed, firstSync())

            assertNull(row(bad))
            assertEquals("Hidden A", row(hiddenA)?.name)
            assertEquals("Hidden B", row(hiddenB)?.name)
            assertNotNull(repository.lastSuccess(ALICE))
        }

    @Test
    fun aSeriesThatAlwaysFailsDoesNotStopTheIncrementalPass() =
        runBlocking {
            failBad()
            firstSync()
            val later = ItemId(hexId(0x5003))
            server.hiddenSeriesSources[later] = info(later, "Later")
            episodesA.items += episode(0x1300, later, libA).copy(dateCreated = EPOCH + 1.minutes)
            clock.instant += 1.hours

            val result = stay().incremental(session, ALICE, both)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals("Later", row(later)?.name)
            assertEquals(clock.nowMs, repository.lastSuccess(ALICE)?.toEpochMilliseconds())
        }

    @Test
    fun aSeriesThatAlwaysFailsDoesNotStopTheReconcileOfTheLaterLibraries() =
        runBlocking {
            failBad()
            firstSync()
            val stored = row(hiddenA)
            episodesB.items.removeAt(0)

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(1, result.summary.tombstoned)
            assertEquals(stored, row(hiddenA))
            assertNull(row(hiddenB)?.deletedAtMs)
        }

    @Test
    fun aStoredSeriesTheServerNowFailsOnIsKept() =
        runBlocking {
            firstSync()
            failBad()
            server.hiddenSeriesSources[hiddenA] = info(hiddenA, "Hidden A")
            val stored = row(bad)

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertEquals(stored, row(bad))
        }

    @Test
    fun aSessionLevelFailureStillFailsThePass() =
        runBlocking {
            server.respondToSeriesById = { MediaServerError.HttpUnauthorized() }

            assertEquals(SyncOutcome.Failed(SyncFailure.SessionExpired), firstSync())
        }

    @Test
    fun aSingleTransientNullKeepsTheSeriesBecauseTheSecondReadFindsIt() =
        runBlocking {
            firstSync()
            // The first read of the show finds nothing; the server has it again by the second.
            server.hiddenSeriesSources.remove(hiddenA)
            server.respondToSeriesById = {
                if (it == hiddenA && server.seriesByIdCalls.count { id -> id == hiddenA } == 2) {
                    server.hiddenSeriesSources[hiddenA] = info(hiddenA, "Hidden A")
                }
                null
            }

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertNull(row(hiddenA)?.deletedAtMs)
        }

    @Test
    fun aNotFoundForEveryCandidateTombstonesNothing() =
        runBlocking {
            firstSync()
            server.hiddenSeriesSources.clear()

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertTrue(repository.currentSeries(ALICE).none { it.deletedAtMs != null })
        }

    @Test
    fun aSeriesAbsentOnBothReadsWhileOthersAreFoundIsTombstoned() =
        runBlocking {
            firstSync()
            server.hiddenSeriesSources.remove(hiddenA)

            val result = reconcile()

            assertEquals(1, result.summary.seriesTombstoned)
            assertEquals(clock.nowMs, row(hiddenA)?.deletedAtMs)
            assertNull(row(hiddenB)?.deletedAtMs)
        }

    @Test
    fun aLibraryWhoseListHoldsNoSeriesButWhoseEpisodesNameAHiddenOneReconcilesFine() =
        runBlocking {
            firstSync()
            val seriesB = row(hiddenB)
            assertNotNull(seriesB)

            val first = reconcile()
            val second = reconcile()

            assertEquals(SyncOutcome.Completed, first.outcome)
            assertEquals(SyncOutcome.Completed, second.outcome)
            assertNull(row(hiddenB)?.deletedAtMs)
            assertEquals(0, first.summary.seriesTombstoned + second.summary.seriesTombstoned)
        }

    @Test
    fun aRemovedLibraryDoesNotTombstoneAHiddenSeriesAnotherLibraryStillNames() =
        runBlocking {
            // Library A reads first and stores the show; B's live episodes name it too.
            episodesB.items += episode(0x2200, hiddenA, libB)
            firstSync()
            assertEquals(libA.id.value, row(hiddenA)?.libraryId)
            server.libraries = listOf(libB)

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertNull(row(hiddenA)?.deletedAtMs)
            assertEquals(libB.id.value, row(hiddenA)?.libraryId)
        }

    @Test
    fun anIncrementalPassDoesNotAskAgainAboutASeriesTheServerSaidIsAbsent() =
        runBlocking {
            val ghost = ItemId(hexId(0x5555))
            episodesA.items += episode(0x1400, ghost, libA)
            firstSync()
            val engine = stay()
            clock.instant += 1.hours
            engine.incremental(session, ALICE, both)
            val afterFirstPass = server.seriesByIdCalls.count { it == ghost }
            clock.instant += 1.hours

            engine.incremental(session, ALICE, both)

            // The first stay-current pass learned it; the second did not ask.
            assertEquals(afterFirstPass, server.seriesByIdCalls.count { it == ghost })
            assertEquals(2, afterFirstPass)
        }

    @Test
    fun theCapStartsWhereTheLastCappedPassStoppedSoEverySeriesIsReachedInTurn() =
        runBlocking {
            server.hiddenSeriesSources.clear()
            episodesA.items.clear()
            episodesB.items.clear()
            val ids = (1..HIDDEN_LOOKUPS_PER_PASS + 5).map { ItemId(hexId(0x7000 + it)) }
            ids.forEachIndexed { n, id -> episodesA.items += episode(0x3000 + n, id, libA) }
            server.respondToSeriesById = { MediaServerError.ServerError() }
            firstSync()
            val engine = stay()
            clock.instant += 1.hours
            engine.incremental(session, ALICE, both)
            val before = server.seriesByIdCalls.size
            clock.instant += 1.hours

            engine.incremental(session, ALICE, both)

            assertEquals(ids[HIDDEN_LOOKUPS_PER_PASS], server.seriesByIdCalls[before])
        }

    @Test
    fun aSeriesThatAlwaysTimesOutDoesNotStopAnyPass() =
        runBlocking {
            server.respondToSeriesById = { if (it == bad) MediaServerError.Timeout() else null }

            assertEquals(SyncOutcome.Completed, firstSync())
            assertNotNull(repository.lastSuccess(ALICE))
            assertNull(row(bad))
            assertEquals("Hidden A", row(hiddenA)?.name)
            clock.instant += 1.hours
            assertEquals(SyncOutcome.Completed, stay().incremental(session, ALICE, both).outcome)
            assertEquals(clock.nowMs, repository.lastSuccess(ALICE)?.toEpochMilliseconds())
            assertEquals(SyncOutcome.Completed, reconcile().outcome)
        }

    @Test
    fun twoDifferentSeriesTimingOutBackToBackFailThePass() =
        runBlocking {
            server.respondToSeriesById = { MediaServerError.Timeout() }

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), firstSync())
        }

    @Test
    fun aSeriesTheServerAnswersWithUndecodableJsonIsUnknownNotFatal() =
        runBlocking {
            server.respondToSeriesById = { if (it == bad) MediaServerError.NotJellyfin() else null }

            assertEquals(SyncOutcome.Completed, firstSync())

            assertNull(row(bad))
            assertEquals("Hidden A", row(hiddenA)?.name)
        }

    @Test
    fun oneServerErrorAmongManyNotFoundsStillTombstonesNothing() =
        runBlocking {
            firstSync()
            server.hiddenSeriesSources.clear()
            server.respondToSeriesById = { if (it == bad) MediaServerError.ServerError() else null }

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertTrue(repository.currentSeries(ALICE).none { it.deletedAtMs != null })
        }

    @Test
    fun anEmptySeriesListOnceTombstonesNoSeriesAndStillReadsTheHiddenOnes() =
        runBlocking {
            firstSync()
            server.seriesSources
                .getValue(libA.id)
                .series
                .clear()
            // The list is empty, but the server still has every show by ID.
            server.hiddenSeriesSources[listedA] = info(listedA, "Listed")
            server.hiddenSeriesSources[quiet] = info(quiet, "Quiet")
            val readsBefore = server.seriesByIdCalls.size

            val result = reconcile()

            // "Quiet" is a listed series no episode names: more likely a server fault than an emptied library.
            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertTrue(repository.currentSeries(ALICE).none { it.deletedAtMs != null })
            assertTrue(server.seriesByIdCalls.size > readsBefore)
        }

    @Test
    fun anEmptySeriesListWhenEveryStoredSeriesIsNamedByEpisodesTombstonesNothing() =
        runBlocking {
            server.seriesSources
                .getValue(libA.id)
                .series
                .removeAll { it.id == quiet }
            firstSync()
            server.seriesSources
                .getValue(libA.id)
                .series
                .clear()
            server.hiddenSeriesSources[listedA] = info(listedA, "Listed")

            val result = reconcile()

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(0, result.summary.seriesTombstoned)
            assertTrue(repository.currentSeries(ALICE).none { it.deletedAtMs != null })
        }
}
