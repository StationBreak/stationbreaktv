// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.savedSelectionOf
import tv.stationbreak.core.domain.selection.LibraryPasses
import tv.stationbreak.core.domain.selection.LibrarySelection
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.domain.selection.SetupResult
import tv.stationbreak.core.domain.storeChoice
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Part 4 (Amendment 4, binding item 5): a setup on the same account, whose first sync finished long ago, must
 * start the first sync itself. The progress screen finishes at once while the account has a last success, so nothing
 * else would read the libraries the new choice adds. Over the real cache and the real first sync; invented libraries.
 */
class SameAccountSetupTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val server = SyncWorld.server(movies to SyncWorld.source("m", 3), shows to SyncWorld.source("s", 2, SHOWS))
    private val settings = InMemoryServerSettings(ServerRecord(session.address))

    // The first sync as the app runs it: asked for, then run by its worker over the chosen libraries.
    private val firstSync =
        object : FirstSync {
            var asked = 0

            override fun status(): Flow<LibraryStatus?> = flowOf(null)

            override fun ensureRunning() = Unit

            // A setup asks with a replacing request (Task 4.4b, R1).
            override fun retry() {
                asked++
            }
        }

    private val selection: LibrarySelection =
        run {
            val service = LineupService(InMemoryLineupStore(), repository, clock, { 0 }, {})
            val source = BuiltInScheduleSource(service, repository, saved = savedSelectionOf(settings))
            val repairer = TodayRepairer(service, source, repository) { settings.loadSelectionSeq() }
            LibrarySelection(
                settings,
                repository,
                server,
                { session },
                repairer,
                service,
                source,
                LibraryPasses.None,
                firstSync,
                StayCurrentRequests {},
                ResumeGate(),
                clock = clock,
            )
        }

    @After
    fun close() = repository.close()

    // What the first sync's worker does once asked: syncs the selection's library set.
    private suspend fun runAskedFirstSync() {
        repeat(firstSync.asked) { SyncWorld.engine(repository, server, clock).run(session, ALICE, selection) }
    }

    @Test
    fun sameAccountSetup_withLastSuccess_startsFirstSync_walksNewLibraries() =
        runBlocking {
            // The account's first sync finished over the movies alone.
            assertTrue(settings.storeChoice(setOf(MOVIES)))
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
            assertNotNull("finished", repository.lastSuccess(ALICE))
            selection.resumeAtStart()
            // The same user signs in again and ticks the shows too.
            assertTrue(selection.clear())

            assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES, SHOWS)))
            runAskedFirstSync()

            assertEquals(1, firstSync.asked)
            assertTrue("the new library is read", repository.pendingLibraries(ALICE).isEmpty())
            val shows = repository.dao.libraries("server-1", "alice").single { it.libraryId == SHOWS.value }
            assertTrue(shows.fetched)
            assertNotNull("the guide stays reachable", repository.lastSuccess(ALICE))
        }

    // Part 4A final fix D1: the setup brings the cache in line with the new choice itself. A stay-current pass that
    // runs before the first sync it asked for (the guide stays reachable) meets the new library's row and completes,
    // where it used to meet no row and stop.
    @Test
    fun sameAccountSetup_thenStayCurrentBeforeTheFirstSync_completes() =
        runBlocking {
            val sessions = SessionRepository(InMemorySecretStore(), server, repository)
            sessions.save(session)
            server.fake.resumeResult = ServerResult.Success(session)
            assertTrue(settings.storeChoice(setOf(MOVIES)))
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
            val before = checkNotNull(repository.lastSuccess(ALICE))
            selection.resumeAtStart()
            assertTrue(selection.clear())
            assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES, SHOWS)))
            clock.instant += 1.hours
            val runner = StayCurrentRunner(settings, sessions, server, repository, SelectionReady.Complete, clock)

            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan.Both))

            assertTrue("the pass recorded its success", checkNotNull(repository.lastSuccess(ALICE)) > before)
            val shows = repository.storedLibraries(ALICE).firstOrNull { it.id == SHOWS }
            assertNotNull("the new library has its row", shows)
        }
}
