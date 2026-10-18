// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 10 task 1.7.1 (Vimes M3): the series reconcile is a separate pass over `librarySeriesIds`, with its own live,
 * tombstoned and known sets and the item reconcile's guards: a complete read only, an empty list for a library that had
 * series is suspect, a count that does not match tombstones nothing, and a second read before anything is tombstoned.
 */
class SeriesReconcileTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val episodes = SyncWorld.source("e", 20, SHOWS)
    private val seriesSource = SeriesSource((1..250).map(::seriesNumber).toMutableList())
    private val server = PagedMediaServer(listOf(shows), mapOf(SHOWS to episodes))

    init {
        server.seriesSources[SHOWS] = seriesSource
    }

    @After
    fun close() = repository.close()

    private fun seriesNumber(n: Int): SeriesInfo {
        val id = "s%03d".format(java.util.Locale.ROOT, n)
        return series(id, "TV-14", listOf("g${n % 3}"))
    }

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(SHOWS))
        assertEquals(SyncOutcome.Completed, outcome)
        server.calls.clear()
        clock.instant += 1.hours
    }

    private var summary = ReconcileSummary()

    private suspend fun reconcile(): SyncOutcome {
        val result = StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, setOf(SHOWS))
        summary = result.summary
        return result.outcome
    }

    private suspend fun tombstoned() =
        repository
            .currentSeries(ALICE)
            .filter { it.deletedAtMs != null }
            .map { it.seriesId }
            .toSet()

    @Test
    fun anUnchangedLibraryTombstonesNothing() =
        runBlocking {
            firstSyncDone()
            val before = repository.currentSeries(ALICE)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(before, repository.currentSeries(ALICE))
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(ReconcileSummary(), summary)
            // The series IDs were listed once (100 a page, then the empty page): nothing was missing, no second read.
            assertEquals(4, server.calls.count { it.series && it.idsOnly })
            // And nothing was missing, so no series page was walked.
            assertEquals(0, server.calls.count { it.series && !it.idsOnly })
        }

    @Test
    fun aGoneSeriesIsTombstonedAfterASecondRead() =
        runBlocking {
            firstSyncDone()
            val gone = seriesSource.series.removeAt(5)

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(setOf(gone.id.value), tombstoned())
            assertEquals(1, summary.seriesTombstoned)
            // Two full reads of the 249 left, four calls each.
            assertEquals(8, server.calls.count { it.series && it.idsOnly })
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
        }

    @Test
    fun aSeriesThatStaysGoneKeepsItsFirstTombstoneTime() =
        runBlocking {
            firstSyncDone()
            val gone = seriesSource.series.removeAt(5)
            reconcile()
            val first = clock.nowMs
            clock.instant += 2.hours

            reconcile()

            assertEquals(first, repository.currentSeries(ALICE).single { it.seriesId == gone.id.value }.deletedAtMs)
        }

    @Test
    fun aReturnedSeriesIsRevivedWithAVersion() =
        runBlocking {
            firstSyncDone()
            val gone = seriesSource.series.removeAt(5)
            reconcile()
            val tombstonedAt = clock.nowMs
            seriesSource.series.add(5, gone)
            clock.instant += 1.hours

            reconcile()

            assertTrue(tombstoned().isEmpty())
            assertEquals(1, summary.seriesRevived)
            val version = repository.seriesVersions(ALICE).single()
            assertEquals(tombstonedAt, version.deletedAtMs)
            assertEquals(clock.nowMs, version.supersededAtMs)
            assertNull(repository.currentSeries(ALICE).single { it.seriesId == gone.id.value }.deletedAtMs)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun anEmptyListForALibraryThatHadSeriesIsSuspectAndChangesNothing() =
        runBlocking {
            firstSyncDone()
            seriesSource.series.clear()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertTrue(tombstoned().isEmpty())
        }

    @Test
    fun aLibraryThatNeverHadSeriesIsNotSuspect() =
        runBlocking {
            seriesSource.series.clear()
            firstSyncDone()

            assertEquals(SyncOutcome.Completed, reconcile())
        }

    @Test
    fun aListThatChangedWhileItWasReadHasTheWrongCountAndTombstonesNothing() =
        runBlocking {
            firstSyncDone()
            var changed = false
            server.respond = { call ->
                val secondPageOfIds = call.series && call.idsOnly && call.startIndex == 100
                if (secondPageOfIds && !changed) {
                    changed = true
                    seriesSource.series.removeAt(10)
                }
                null
            }

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertTrue(tombstoned().isEmpty())
            server.respond = { null }
            reconcile()
            assertEquals(setOf("s011"), tombstoned())
        }

    @Test
    fun aFailedListChangesNothingAndRecordsTheFailure() =
        runBlocking {
            firstSyncDone()
            seriesSource.series.removeAt(5)
            server.respond = { call ->
                if (call.series && call.idsOnly && call.startIndex == 100) MediaServerError.Timeout() else null
            }

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertTrue(tombstoned().isEmpty())
        }

    @Test
    fun theItemsAndTheSeriesTombstoneInOneTransaction() =
        runBlocking {
            firstSyncDone()
            episodes.items.removeAt(3)
            seriesSource.series.removeAt(5)
            repository.faultBetweenReconcileTombstonesAndRevives = { error("the process died here") }

            val failed = runCatching { reconcile() }

            assertTrue(failed.isFailure)
            assertTrue(tombstoned().isEmpty())
            assertTrue(repository.stored(ALICE).none { it.deletedAt != null })
            repository.faultBetweenReconcileTombstonesAndRevives = null
            assertEquals(SyncOutcome.Completed, reconcile())
            assertEquals(1, tombstoned().size)
            assertEquals(1, repository.stored(ALICE).count { it.deletedAt != null })
        }

    // Vimes M-1: a series the cache lacks (a page skipped in the first walk, a malformed page) comes back.
    @Test
    fun aSeriesMissingFromTheCacheComesBackAfterOneReconcile() =
        runBlocking {
            firstSyncDone()
            val before = repository.currentSeries(ALICE)
            seriesSource.series += series("s999", "G", listOf("New"))

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            val after = repository.currentSeries(ALICE)
            assertEquals(251, after.size)
            assertEquals("G", after.single { it.seriesId == "s999" }.officialRating)
            // Only the missing one was written: the others are untouched, and nothing was versioned.
            assertEquals(before, after.filter { it.seriesId != "s999" })
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertTrue(server.calls.any { it.series && !it.idsOnly })
        }

    @Test
    fun aMissingSeriesTheServerNeverReturnsCostsABoundedWalkAndNothingElse() =
        runBlocking {
            firstSyncDone()
            seriesSource.series += series("s999")
            server.malformed =
                setOf(
                    tv.stationbreak.core.model
                        .ItemId("s999"),
                )

            val outcome = reconcile()

            // A malformed page is skipped item by item, as in the first sync; the pass still completes.
            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(250, repository.currentSeries(ALICE).size)
        }
}
