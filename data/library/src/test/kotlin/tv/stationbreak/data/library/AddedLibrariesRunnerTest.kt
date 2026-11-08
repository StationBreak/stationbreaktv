// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.selection.AddLandings
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.LandResult
import tv.stationbreak.core.domain.selection.LibraryPasses
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The add pass (spec 26, Part 4 §2 and §4, Task 4.4b): each library being added is walked under its stamp, proved in
 * the walk's own last write, and then handed to the selection to land; the pass never lands anything itself. It waits
 * for the selection's resume. A landing that may succeed later is tried again a few times per stamp, waiting longer
 * each time, and then given up for the life of the process; one that cannot is not tried again; one the cache holds
 * no proof for is walked again, which is no retry. A write the cache refused while the settings still name the stamp
 * asks the selection to put the cache in line, once. Invented libraries and names only.
 */
class AddedLibrariesRunnerTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = emptyList())
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 2, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))
    private val settings = InMemoryServerSettings(ServerRecord(session.address))
    private val sessions = SessionRepository(InMemorySecretStore(), server, repository)
    private val lines = mutableListOf<String>()
    private val slept = mutableListOf<Duration>()

    @After
    fun close() = repository.close()

    private fun runner(
        landings: AddLandings,
        ready: SelectionReady = SelectionReady.Complete,
    ): AddedLibrariesRunner =
        AddedLibrariesRunner(settings, sessions, server, repository, landings, ready, clock, report = { lines += it })
            .also { it.sleep = { d -> slept += d } }

    // Signed in, MOVIES synced by the first sync and chosen.
    private suspend fun signedInWithMovies() {
        sessions.save(session)
        server.fake.resumeResult = ServerResult.Success(session)
        assertTrue(settings.saveSelection(StoredSelection(setOf(MOVIES), seq = 1, resetSeq = 1, owner = ALICE), true))
        val synced = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
        assertEquals(SyncOutcome.Completed, synced)
        server.calls.clear()
    }

    // SHOWS being added under [stamp], as a tick that was saved leaves it: in the settings and stamped in the cache.
    private suspend fun addingShows(stamp: Long = 2L) {
        val stored = StoredSelection(setOf(MOVIES), seq = stamp, adding = mapOf(SHOWS to stamp), resetSeq = 1)
        assertTrue(settings.saveSelection(stored.copy(owner = ALICE)))
        repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to stamp), nowMs = clock.nowMs)
    }

    private suspend fun row(id: ItemId) = repository.storedLibraries(ALICE).singleOrNull { it.id == id }

    // The add pass's side of a selection that answers [land] to every landing and counts the calls. Its adds are the
    // stored ones (the tests' choices are this account's own).
    private inner class ScriptedLandings(
        var land: () -> LandResult,
    ) : AddLandings {
        var landings = 0
        var reconciles = 0
        val givenUp = mutableListOf<Pair<ItemId, Long>>()

        override suspend fun pendingAdds(account: LibraryAccount): Map<ItemId, Long> {
            val stored = settings.loadSelection()
            return stored?.adding.orEmpty()
        }

        override suspend fun passLanded(
            account: LibraryAccount,
            id: ItemId,
            stamp: Long,
        ): LandResult {
            landings++
            return land()
        }

        override suspend fun reconcile() {
            reconciles++
        }

        override suspend fun addGivenUp(
            account: LibraryAccount,
            id: ItemId,
            stamp: Long,
        ) {
            givenUp += id to stamp
        }
    }

    @Test
    fun anAdd_isWalkedUnderItsStamp_provedThenLandedByTheSelection_andTheWorkerIsNeverReplaced() =
        runBlocking {
            signedInWithMovies()
            var restarts = 0
            val selection = selectionOver(repository, settings, server, clock, passes = LibraryPasses { restarts++ })
            selection.resumeAtStart()
            selection.listing()
            assertEquals(ChangeResult.Saved, selection.change(setOf(MOVIES, SHOWS)))
            assertEquals("the tick asks for the add pass", 1, restarts)

            assertEquals(SyncOutcome.Completed, runner(selection).outcome())

            val stored = settings.loadSelection()!!
            assertEquals(setOf(MOVIES, SHOWS), stored.chosen)
            assertTrue(stored.adding.isEmpty())
            val shows = row(SHOWS)!!
            assertTrue("landed: fetched", shows.fetched)
            assertNull("landed: no stamp left", shows.addStamp)
            assertEquals(2, repository.stored(ALICE).count { it.library == SHOWS })
            assertEquals("nothing the pass called replaced its worker", 1, restarts)
        }

    @Test
    fun theRun_waitsForTheResume_beforeItReadsTheAdds() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            val landings = ScriptedLandings { LandResult.Landed }
            val gate = ResumeGate()
            var reads = 0
            val counted =
                object : ServerSettings by settings {
                    override suspend fun load(): ServerRecord? {
                        reads++
                        return settings.load()
                    }
                }
            val runner = AddedLibrariesRunner(counted, sessions, server, repository, landings, gate, clock)
            val running = async(start = CoroutineStart.UNDISPATCHED) { runner.outcome() }

            assertFalse(running.isCompleted)
            assertEquals("nothing read before the resume", 0, reads)
            gate.open()

            assertEquals(SyncOutcome.Completed, running.await())
            assertEquals(1, landings.landings)
        }

    @Test
    fun aLandingToRetry_waitsLongerEachTime_andIsGivenUpAfterFiveRetries() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            val landings = ScriptedLandings { LandResult.Retry }
            val runner = runner(landings)

            assertEquals(SyncOutcome.Completed, runner.outcome())

            assertEquals("the first try and five retries", 6, landings.landings)
            assertEquals(listOf(2, 4, 8, 16, 32).map { it.seconds }, slept)
            assertTrue("AddLandingStuck" in lines)
            assertTrue("still being added, its walk proved", stampedAndProved())
            assertEquals("the selection is told: shown stalled", listOf(SHOWS to 2L), landings.givenUp)

            // The stamp is given up for the life of the process: a later run does not try it again.
            assertEquals(SyncOutcome.Completed, runner.outcome())
            assertEquals(6, landings.landings)
        }

    private suspend fun stampedAndProved(): Boolean {
        val shows = row(SHOWS) ?: return false
        return shows.addStamp == 2L && shows.walkedStamp == 2L
    }

    @Test
    fun aLandingThatCannotSucceed_isNotRetried() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            val landings = ScriptedLandings { LandResult.Failed }
            val runner = runner(landings)

            assertEquals(SyncOutcome.Completed, runner.outcome())
            assertEquals(SyncOutcome.Completed, runner.outcome())

            assertEquals(1, landings.landings)
            assertTrue(slept.isEmpty())
            assertTrue("AddLandingFailed" in lines)
            assertEquals("the selection is told once", listOf(SHOWS to 2L), landings.givenUp)
        }

    @Test
    fun anUnprovenLanding_isWalkedAgain_andIsNoRetry() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            val answers = ArrayDeque(listOf(LandResult.Unproven, LandResult.Retry, LandResult.Landed))
            val landings = ScriptedLandings { answers.removeFirst() }

            assertEquals(SyncOutcome.Completed, runner(landings).outcome())

            assertEquals(3, landings.landings)
            // Each walk ends with its catch-up: one read of what changed since the walk began.
            val catchUps = server.itemCalls.count { it.library.id == SHOWS && it.changedSince != null }
            assertEquals("walked, then walked again after Unproven", 2, catchUps)
            assertEquals("only the Retry waited, as the first retry", listOf(2.seconds), slept)
        }

    @Test
    fun aRefusedWrite_whileTheSettingsStillNameTheStamp_asksForAReconcile_once() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            // The cache holds SHOWS (an untick it saw that the settings never did): every add write is refused.
            val until = clock.nowMs + 60_000
            repository.applySelection(ALICE, setOf(MOVIES), holds = mapOf(SHOWS to until), nowMs = clock.nowMs)
            val landings = ScriptedLandings { LandResult.Landed }

            assertEquals(SyncOutcome.Completed, runner(landings).outcome())

            assertEquals(1, landings.reconciles)
            assertEquals("never landed without a proof", 0, landings.landings)
            assertTrue(repository.stored(ALICE).none { it.library == SHOWS })
        }

    // A3: the work list is the selection's own, owner-checked: another account's choice gives no work at all.
    @Test
    fun anotherAccountsChoice_givesNoWork() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            val stored = settings.loadSelection()!!
            val foreign = stored.copy(seq = stored.seq + 1, adding = mapOf(SHOWS to stored.seq + 1), owner = BOB)
            assertTrue(settings.saveSelection(foreign))
            // The cache carries the same stamp, so only the owner check keeps the pass from walking it.
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to foreign.seq), nowMs = clock.nowMs)
            val selection = selectionOver(repository, settings, server, clock)
            selection.resumeAtStart()

            assertEquals(SyncOutcome.Completed, runner(selection).outcome())

            assertTrue("nothing asked of the server", server.calls.isEmpty())
            assertEquals(foreign.seq, row(SHOWS)?.addStamp)
            assertNull("nothing walked", row(SHOWS)?.walkedStamp)
        }

    // D-LOW: a walk proved before a restart (the landing's save had not committed) is landed at once: not read again.
    @Test
    fun aProvedAdd_onARestart_isLandedWithNoReadOfTheServer() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            assertTrue(repository.markAddWalked(ALICE, SHOWS, 2L, clock.nowMs))
            val landings = ScriptedLandings { LandResult.Landed }

            assertEquals(SyncOutcome.Completed, runner(landings).outcome())

            assertEquals(1, landings.landings)
            assertTrue("no page of it asked for", server.calls.none { it.library.id == SHOWS })
        }

    // A7: with no count of its own, the add walk's checkpoints keep the server's first-page count on later pages.
    @Test
    fun theAddWalksCheckpoints_keepTheFirstPagesCount() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            server.maxItemsPerResponse = 1
            val landings = ScriptedLandings { LandResult.Landed }

            assertEquals(SyncOutcome.Completed, runner(landings).outcome())

            assertEquals(2, repository.checkpoint(ALICE, SHOWS)?.totalCount)
        }

    @Test
    fun aServerThatCannotBeReached_endsTheRun_forTheWorkToRetry() =
        runBlocking {
            signedInWithMovies()
            addingShows()
            server.librariesFailure = MediaServerError.Timeout()
            val runner = runner(ScriptedLandings { LandResult.Landed })

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), runner.outcome())
            assertEquals(SyncVerdict.Retry, runner.run(attempt = 0))
        }

    @Test
    fun theRestart_replacesTheUniqueAddWork() {
        val enqueuer = RecordingEnqueuer()

        WorkManagerLibraryPasses(enqueuer).restart()

        val (name, policy, _) = enqueuer.enqueued.single()
        assertEquals(AddedLibrariesWork.UNIQUE_NAME, name)
        assertEquals(ExistingWorkPolicy.REPLACE, policy)
    }
}
