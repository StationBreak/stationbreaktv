// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 18 section 2, the backfill: while `sync_meta.backfillPending` is set the incremental pass reads every item and
 * series from the start (since = null), and only a complete incremental pass clears the flag. First sync ends through
 * `finishSync`, which never touches it; the incremental pass's finish does nothing while a first sync runs.
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

    private fun stay() = StayCurrentWorld.engine(repository, server, clock)

    /** The server now knows the studios of the first ten films and of one series; the rest are untouched. */
    private fun studiosAppear() {
        for (i in 0 until 10) films.items[i] = films.items[i].copy(studios = listOf("Invented Studio $i"))
        seriesSource.series[0] = seriesSource.series[0].copy(studios = listOf("Invented Studio"))
    }

    private suspend fun lastSuccessMs() = repository.lastSuccess(ALICE)?.toEpochMilliseconds()

    @Test
    fun whileTheFlagIsSetEveryItemAndSeriesPageIsAskedForWithNoSince() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            val started = clock.now()

            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            val asked = server.calls.filter { !it.idsOnly }
            assertTrue(asked.any { it.series } && asked.any { !it.series })
            assertEquals(setOf(movies.id, shows.id), asked.map { it.library.id }.toSet())
            assertTrue(asked.all { it.changedSince == null })
            assertFalse(repository.backfillPending(ALICE))
            assertEquals(started.toEpochMilliseconds(), lastSuccessMs())
        }

    @Test
    fun withTheFlagClearThePassAsksSinceTheLastPassLessTheMargin() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours

            stay().incremental(session, ALICE, chosen)

            val asked = server.calls.filter { !it.idsOnly }
            assertTrue(asked.isNotEmpty())
            assertTrue(asked.all { it.changedSince == EPOCH - 10.minutes })
            assertFalse(repository.backfillPending(ALICE))
        }

    @Test
    fun aChosenLibraryTheServerDoesNotListKeepsTheFlagSet() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            clock.instant += 1.hours
            server.libraries = listOf(movies)

            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue(repository.backfillPending(ALICE))
            assertEquals(clock.nowMs, lastSuccessMs())
        }

    @Test
    fun aKillAfterTheFirstLibraryKeepsTheFlagAndTheResumeWritesEachChangeOnce() =
        runBlocking {
            firstSyncDone()
            flagIsSet(true)
            studiosAppear()
            clock.instant += 1.hours
            val generation = repository.contentGeneration(ALICE)
            var killed = false
            server.respond = { call ->
                if (call.library.id == SHOWS && !killed) {
                    killed = true
                    throw CancellationException("the process was killed")
                }
                null
            }

            try {
                stay().incremental(session, ALICE, chosen)
                fail("the pass should have been killed")
            } catch (_: CancellationException) {
                // The kill.
            }

            assertTrue(repository.backfillPending(ALICE))
            assertEquals(EPOCH.toEpochMilliseconds(), lastSuccessMs())
            assertEquals(10, repository.versions(ALICE).size)
            val afterKill = repository.contentGeneration(ALICE)
            assertNotEquals(generation, afterKill)

            server.respond = { null }
            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertFalse(repository.backfillPending(ALICE))
            assertEquals(clock.nowMs, lastSuccessMs())
            // Each film that gained studios has exactly one new version; the others have none.
            val versions = repository.versions(ALICE).groupBy { it.itemId }
            assertEquals(10, versions.size)
            assertTrue(versions.values.all { it.size == 1 })
            assertEquals(1, repository.seriesVersions(ALICE).size)
            // The resume re-read the movies, which changed nothing more: only the series moved the generation.
            assertEquals(afterKill + 1, repository.contentGeneration(ALICE))
        }

    @Test
    fun aFirstSyncStartedDuringTheWalkIsLeftAloneAndTheFlagStaysSet() =
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

            val result = stay().incremental(session, ALICE, chosen)

            assertEquals(SyncOutcome.Completed, result.outcome)
            val state = repository.dao.state(ALICE.server.value, ALICE.user.value)
            assertEquals(AccountStateEntity.STATE_FIRST_RUNNING, state?.state)
            assertEquals(EPOCH.toEpochMilliseconds(), state?.lastSuccessMs)
            assertTrue(repository.backfillPending(ALICE))
        }

    @Test
    fun anIncrementalPassWithTheFlagClearAlsoLeavesARunningFirstSyncAlone() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            server.respond = { call ->
                if (call.library.id == SHOWS) runBlocking { repository.beginFirstSync(ALICE) }
                null
            }

            stay().incremental(session, ALICE, chosen)

            val state = repository.dao.state(ALICE.server.value, ALICE.user.value)
            assertEquals(AccountStateEntity.STATE_FIRST_RUNNING, state?.state)
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
    fun aFreshInstallNeverHasTheFlagSet() =
        runBlocking {
            firstSyncDone()
            clock.instant += 1.hours
            stay().incremental(session, ALICE, chosen)

            repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)

            assertFalse(repository.backfillPending(ALICE))
            assertFalse(repository.dao.metaRow(ALICE.server.value, ALICE.user.value)!!.backfillPending)
        }
}
