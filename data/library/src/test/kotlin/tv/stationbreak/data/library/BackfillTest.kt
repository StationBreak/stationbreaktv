// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 18 section 2, the backfill, under ruling R9: the studios re-read is its own step. While
 * `sync_meta.backfillPending` is set, [StayCurrentEngine.backfill] reads every item and series from the start
 * (since = null), and only a complete backfill clears the flag. It never moves the recorded moment and never records a
 * failure for the account. The incremental pass keeps its normal window whatever the flag says. First sync ends through
 * `finishSync`, which never touches the flag, and nothing a stay-current pass writes overwrites a running first sync.
 */
class BackfillTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val chosen = setOf(MOVIES, SHOWS)
    private val films = SyncWorld.source("m", 30)
    private val episodes = SyncWorld.source("e", 20, SHOWS)
    private val seriesSource = SeriesSource(mutableListOf(series("s1", "TV-G"), series("s2", "TV-G")))
    private val server =
        PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes)).also {
            it.seriesSources[SHOWS] = seriesSource
        }

    @After
    fun close() = repository.close()

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        assertEquals(SyncOutcome.Completed, SyncWorld.engine(repository, server, clock).run(session, ALICE, chosen))
        server.calls.clear()
    }

    private suspend fun flagIsSet(set: Boolean) {
        repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)
        repository.dao.setBackfillPending(ALICE.server.value, ALICE.user.value, set)
    }

    private fun stay(maxSkipped: Int = FirstSyncEngine.MAX_SKIPPED_ITEMS) =
        StayCurrentEngine(repository, server, clock, pageSize = 100, maxSkippedItems = maxSkipped)

    /** The server now knows the studios of the first ten films and of one series; the rest are untouched. */
    private fun studiosAppear() {
        for (i in 0 until 10) films.items[i] = films.items[i].copy(studios = listOf("Invented Studio $i"))
        seriesSource.series[0] = seriesSource.series[0].copy(studios = listOf("Invented Studio"))
    }

    private suspend fun lastSuccessMs() = repository.lastSuccess(ALICE)?.toEpochMilliseconds()

    private suspend fun state() = repository.dao.state(ALICE.server.value, ALICE.user.value)

    @Test
    fun whileTheFlagIsSetTheBackfillAsksForEveryItemAndSeriesPageWithNoSince() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours

            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            val asked = server.calls.filter { !it.idsOnly }
            assertTrue(asked.any { it.series } && asked.any { !it.series })
            assertEquals(setOf(movies.id, shows.id), asked.map { it.library.id }.toSet())
            assertTrue(asked.all { it.changedSince == null })
            assertFalse(repository.backfillPending(ALICE))
            // The backfill is not a sync: the recorded moment and the account's state are as the first sync left them.
            assertEquals(EPOCH.toEpochMilliseconds(), lastSuccessMs())
            assertEquals(AccountStateEntity.STATE_IDLE, state()?.state)
        }

    @Test
    fun withTheFlagClearTheBackfillAsksForNothing() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours

            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun withTheFlagSetTheIncrementalPassStillAsksSinceTheLastPassAndLeavesTheFlag() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours

            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            val asked = server.calls.filter { !it.idsOnly }
            assertTrue(asked.isNotEmpty())
            assertTrue(asked.all { it.changedSince == EPOCH - 10.minutes })
            assertTrue(repository.backfillPending(ALICE))
            assertEquals(clock.nowMs, lastSuccessMs())
        }

    @Test
    fun aChosenLibraryTheServerDoesNotListKeepsTheFlagSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.libraries = listOf(movies)

            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(repository.backfillPending(ALICE))
            assertEquals(EPOCH.toEpochMilliseconds(), lastSuccessMs())
        }

    @Test
    fun aFailedBackfillPageKeepsTheFlagAndIsNeverTheAccountsFailure() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.respond = { call -> if (call.library.id == SHOWS) MediaServerError.Timeout() else null }

            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), result.outcome)
            assertTrue(repository.backfillPending(ALICE))
            val state = state()
            assertEquals(AccountStateEntity.STATE_IDLE, state?.state)
            assertNull(state?.failure)
            assertEquals(EPOCH.toEpochMilliseconds(), state?.lastSuccessMs)
        }

    @Test
    fun itemsThatWillNotDecodeAreCountedAndNeverFailTheBackfill() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.malformed = setOf(films.items[3].id, films.items[17].id)

            val result = stay(maxSkipped = 0).backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(2, result.summary.skipped)
            assertFalse(repository.backfillPending(ALICE))
        }

    private suspend fun assertAccountStateUntouched() {
        val state = state()
        assertEquals(AccountStateEntity.STATE_IDLE, state?.state)
        assertNull(state?.failure)
        assertEquals(EPOCH.toEpochMilliseconds(), state?.lastSuccessMs)
        assertTrue(repository.backfillPending(ALICE))
    }

    @Test
    fun moreThanAPagesWorthOfUndecodableItemsInARowEndsTheBackfillUnrecordedWithTheFlagSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.malformed = films.items.map { it.id }.toSet()

            val result = StayCurrentEngine(repository, server, clock, pageSize = 10).backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            // One page's worth is allowed; the next item in a row is the one that ends it.
            assertEquals(11, result.summary.skipped)
            assertAccountStateUntouched()
        }

    @Test
    fun moreThanAPagesWorthOfUndecodableSeriesInARowEndsTheBackfillToo() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.malformed = seriesSource.series.map { it.id }.toSet()

            val result = StayCurrentEngine(repository, server, clock, pageSize = 1).backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertEquals(2, result.summary.skipped)
            assertAccountStateUntouched()
        }

    @Test
    fun aGoodPageBetweenUndecodableRunsResetsTheCount() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.malformed = (films.items.take(10) + films.items.drop(20)).map { it.id }.toSet()

            val result = StayCurrentEngine(repository, server, clock, pageSize = 10).backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(20, result.summary.skipped)
            assertFalse(repository.backfillPending(ALICE))
        }

    @Test
    fun moreSkippedThanTheServerSaidTheListingHoldsEndsTheBackfillUnrecordedWithTheFlagSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            // The first page carries the total (30) but holds five; every index after it then fails to decode.
            server.hiddenFromItems =
                films.items
                    .drop(5)
                    .map { it.id }
                    .toSet()
            server.respond = { call -> if (call.startIndex >= 5) MediaServerError.NotJellyfin() else null }

            val result = StayCurrentEngine(repository, server, clock, pageSize = 50).backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), result.outcome)
            assertEquals(31, result.summary.skipped)
            assertAccountStateUntouched()
        }

    @Test
    fun aKillAfterTheFirstLibraryKeepsTheFlagAndTheResumeWritesEachChangeOnce() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            studiosAppear()
            clock.instant += 1.hours
            val generation = repository.contentGeneration(ALICE)
            val firstSeen = repository.currentSeries(ALICE).associate { it.seriesId to it.firstSeenMs }
            var killed = false
            server.respond = { call ->
                if (call.library.id == SHOWS && !killed) {
                    killed = true
                    throw CancellationException("the process was killed")
                }
                null
            }

            try {
                stay().backfill(session, ALICE, chosen)
                fail("the backfill should have been killed")
            } catch (_: CancellationException) {
                // The kill.
            }

            assertTrue(repository.backfillPending(ALICE))
            assertEquals(EPOCH.toEpochMilliseconds(), lastSuccessMs())
            assertEquals(10, repository.versions(ALICE).size)
            val afterKill = repository.contentGeneration(ALICE)
            assertNotEquals(generation, afterKill)

            server.respond = { null }
            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertFalse(repository.backfillPending(ALICE))
            assertEquals(EPOCH.toEpochMilliseconds(), lastSuccessMs())
            // Each film that gained studios has exactly one new version; the others have none.
            val versions = repository.versions(ALICE).groupBy { it.itemId }
            assertEquals(10, versions.size)
            assertTrue(versions.values.all { it.size == 1 })
            assertEquals(1, repository.seriesVersions(ALICE).size)
            // The series that gained studios keeps the moment it was first seen.
            assertEquals(firstSeen, repository.currentSeries(ALICE).associate { it.seriesId to it.firstSeenMs })
            // The resume re-read the movies, which changed nothing more: only the series moved the generation.
            assertEquals(afterKill + 1, repository.contentGeneration(ALICE))
        }

    @Test
    fun aFirstSyncStartedDuringTheBackfillIsLeftAloneAndTheFlagStaysSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            var started = false
            server.respond = { call ->
                if (call.library.id == SHOWS && !started) {
                    started = true
                    runBlocking { repository.beginFirstSync(ALICE) }
                }
                null
            }

            val result = stay().backfill(session, ALICE, chosen)

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            val state = state()
            assertEquals(AccountStateEntity.STATE_FIRST_RUNNING, state?.state)
            assertEquals(EPOCH.toEpochMilliseconds(), state?.lastSuccessMs)
            assertTrue(repository.backfillPending(ALICE))
        }

    @Test
    fun anIncrementalPassLeavesAFirstSyncStartedDuringItAloneAndEndsCancelled() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            server.respond = { call ->
                if (call.library.id == SHOWS) runBlocking { repository.beginFirstSync(ALICE) }
                null
            }

            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            val state = state()
            assertEquals(AccountStateEntity.STATE_FIRST_RUNNING, state?.state)
            assertEquals(EPOCH.toEpochMilliseconds(), state?.lastSuccessMs)
        }

    @Test
    fun aPageFailingAfterAFirstSyncStartedLeavesTheFirstSyncRunning() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            server.respond = { call ->
                if (call.library.id == SHOWS) {
                    runBlocking { repository.beginFirstSync(ALICE) }
                    MediaServerError.Timeout()
                } else {
                    null
                }
            }

            val result = stay().incremental(session, ALICE, chosen)

            // Nothing was written, so it is not a failure to report: the pass was stood down.
            assertEquals(SyncOutcome.Cancelled, result.outcome)
            val state = state()
            assertEquals(AccountStateEntity.STATE_FIRST_RUNNING, state?.state)
            assertNull(state?.failure)
        }

    @Test
    fun aFirstSyncThatEndsLeavesTheFlagSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours

            assertEquals(SyncOutcome.Completed, SyncWorld.engine(repository, server, clock).run(session, ALICE, chosen))
            repository.finishSync(ALICE)

            assertTrue(repository.backfillPending(ALICE))
        }

    @Test
    fun aNewlyCreatedSyncMetaRowHasTheFlagOff() =
        runBlocking {
            repository.useAccount(ALICE)
            assertNull(repository.dao.metaRow(ALICE.server.value, ALICE.user.value))

            repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)

            assertFalse(repository.dao.metaRow(ALICE.server.value, ALICE.user.value)!!.backfillPending)
            assertFalse(repository.backfillPending(ALICE))
        }
}
