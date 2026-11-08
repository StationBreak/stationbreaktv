// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedCall
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.domain.storeChoice
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * The stay-current rewalk (spec 26, Part 4 §4, Amendments 3 and 4): after the passes of a run that completed, each
 * chosen library marked for a rewalk (an unproven add set back) that the server lists is walked from its checkpoint
 * with no stamp, and its end clears the mark and writes its coverage. Results are per library: a failure is reported,
 * keeps the mark for the next run, and never makes the run anything but Completed. A first sync that begins stops it;
 * an unlisted library keeps its mark; a library a setup left pending and unmarked is the first sync's. The run waits
 * for the selection's resume before it takes its gate. Invented libraries and names only.
 */
class StayCurrentRewalkTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = emptyList())
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 3, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))
    private val sessions = SessionRepository(InMemorySecretStore(), server, repository)
    private val settings = InMemoryServerSettings(ServerRecord(session.address), setOf(MOVIES))

    @After
    fun close() = repository.close()

    private fun runner(ready: SelectionReady = SelectionReady.Complete) =
        StayCurrentRunner(settings, sessions, server, repository, ready, clock)

    private suspend fun row(id: ItemId): LibraryEntity {
        val rows = repository.dao.libraries("server-1", "alice")
        return rows.single { it.libraryId == id.value }
    }

    // MOVIES synced by the first sync; SHOWS then ticked, its add walk cut short (two of three episodes, no proof),
    // and set back to be read again, as the selection does: chosen, pending and marked.
    private suspend fun markedShows() {
        sessions.save(session)
        server.fake.resumeResult = ServerResult.Success(session)
        assertEquals(SyncVerdict.Success, firstSyncRunner(settings, sessions, server, repository, clock).run(0))
        repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 2L), nowMs = clock.nowMs)
        repository
            .writePage(ALICE, SHOWS, stamp = 2L, rewalk = false, episodes.items.take(2), 2, 3, clock.nowMs)
            .written()
        assertTrue(repository.revertUnproven(ALICE, SHOWS, 2L))
        assertTrue(settings.storeChoice(setOf(MOVIES, SHOWS)))
        clock.instant += 1.hours
        server.calls.clear()
    }

    // The rewalk's reads of SHOWS: item pages with no changed-since (the incremental pass asks with one).
    private fun PagedCall.isRewalkPage(): Boolean {
        val itemPage = !idsOnly && !series
        return itemPage && library.id == SHOWS && changedSince == null
    }

    private fun rewalkPages() = server.calls.filter { it.isRewalkPage() }

    @Test
    fun rewalk_walksTheMarkedLibrary_clearsTheMark_andWritesItsCoverage() =
        runBlocking {
            markedShows()

            assertEquals(SyncOutcome.Completed, runner().outcome(SyncPlan.Both))

            val shows = row(SHOWS)
            assertTrue(shows.fetched)
            assertFalse(shows.rewalkPending)
            assertNotNull("the incremental reads it from the rewalk's start", shows.coveredFromMs)
            assertEquals(3, repository.stored(ALICE).count { it.library == SHOWS })
        }

    @Test
    fun rewalk_stopsWhenFirstSyncBegins() =
        runBlocking {
            markedShows()
            // A setup starts the first sync just as the rewalk asks for its first page.
            server.respond = { call ->
                if (call.isRewalkPage()) runBlocking { repository.beginFirstSync(ALICE) }
                null
            }
            val runner = runner()

            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan.Both))

            assertEquals("one page asked for, then it stopped", 1, rewalkPages().size)
            assertTrue("the first sync takes it, marked", row(SHOWS).rewalkPending)
            assertFalse(row(SHOWS).fetched)
            assertEquals(2, repository.stored(ALICE).count { it.library == SHOWS })
            assertTrue(checkNotNull(runner.lastRewalk).stopped)
        }

    // A setup on the same account leaves its new library pending and unmarked: the first sync it starts reads it.
    @Test
    fun sameAccountSetup_rewalkLeavesNewLibrariesToFirstSync() =
        runBlocking {
            sessions.save(session)
            server.fake.resumeResult = ServerResult.Success(session)
            assertEquals(SyncVerdict.Success, firstSyncRunner(settings, sessions, server, repository, clock).run(0))
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            assertTrue(settings.storeChoice(setOf(MOVIES, SHOWS)))
            clock.instant += 1.hours
            server.calls.clear()
            val runner = runner()

            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan.Both))

            val rewalk = checkNotNull(runner.lastRewalk)
            assertEquals("not a rewalk's: it has no mark", 0, rewalk.marked)
            assertEquals(0, rewalk.walked)
            assertFalse("still pending: the first sync's", row(SHOWS).fetched)
        }

    @Test
    fun rewalk_unlistedLibrary_keepsMark_runCompletes() =
        runBlocking {
            markedShows()
            server.libraries = listOf(movies)
            val runner = runner()

            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan.Both))

            assertTrue(row(SHOWS).rewalkPending)
            assertFalse(row(SHOWS).fetched)
            assertTrue(rewalkPages().isEmpty())
            assertEquals(1, checkNotNull(runner.lastRewalk).unlisted)
        }

    @Test
    fun rewalk_failure_otherPassesComplete() =
        runBlocking {
            markedShows()
            repository.dao.setBackfillPending("server-1", "alice", true)
            val before = repository.lastSuccess(ALICE)
            server.respond = { call ->
                if (call.isRewalkPage()) MediaServerError.Timeout() else null
            }
            val runner = runner()

            assertEquals(SyncOutcome.Completed, runner.outcome(SyncPlan.Both))

            assertTrue("the incremental pass recorded its success", repository.lastSuccess(ALICE)!! > before!!)
            assertEquals(1, checkNotNull(runner.lastRewalk).failed)
            assertTrue("kept for the next run", row(SHOWS).rewalkPending)
            assertNotNull("the backfill still ran after it", runner.lastBackfill)
        }

    // Part 4 §2: the run waits for the selection's resume before it takes its gate, so a waiting run never holds it.
    @Test
    fun stayCurrent_waitsForTheResume_beforeItTakesTheGate() =
        runBlocking {
            markedShows()
            val gate = ResumeGate()
            val runner = runner(gate)

            val running = async(start = CoroutineStart.UNDISPATCHED) { runner.outcome(SyncPlan.Both) }

            assertFalse(running.isCompleted)
            assertFalse("a waiting run holds no gate", runner.isRunning)
            gate.open()
            assertEquals(SyncOutcome.Completed, running.await())
        }
}
