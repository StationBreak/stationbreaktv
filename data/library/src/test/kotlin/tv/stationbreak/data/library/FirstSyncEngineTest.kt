// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.PagedCall
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.savedSelectionOf
import tv.stationbreak.core.domain.selection.FirstSyncLibraries
import tv.stationbreak.core.domain.selection.LibraryPasses
import tv.stationbreak.core.domain.selection.LibrarySelection
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.domain.selection.SetupResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

/**
 * The first sync's library set (spec 26, Part 4 §2, Task 4.4b): it comes from the selection's `firstSyncLibraries`,
 * read under the selection's lock, never from the engine's own write. An empty set (no choice of this account's) is
 * not a finished first sync. A library the cache refused while still chosen is read again, and a run that cannot
 * read it records no success. A setup's choice clears the old adds the new choice does not name (D7), and a marked
 * library the server does not list keeps its mark (D18). Invented libraries and names only.
 */
class FirstSyncEngineTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 2, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))
    private val extra = ItemId(hexId(0xB00E))

    @After
    fun close() = repository.close()

    private fun engine() = SyncWorld.engine(repository, server, clock)

    private suspend fun row(library: ItemId): LibraryEntity? =
        repository.dao.libraries("server-1", "alice").singleOrNull { it.libraryId == library.value }

    // --- With the real selection ----------------------------------------------------------------------------------

    private val settings = InMemoryServerSettings(ServerRecord(session.address))

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

    // Binding item 7: another account's choice gives an empty set, and an empty set is not a finished first sync, so
    // the setup that replaces the choice asks for a first sync that reads the new libraries.
    @Test
    fun firstSync_foreignChoice_recordsNothing_thenTheSetupsLibrariesAreWalked() =
        runBlocking {
            repository.useAccount(ALICE)
            // A sign-in whose clear failed: the stored choice is still another account's.
            assertTrue(settings.saveSelection(StoredSelection(setOf(MOVIES), seq = 1, resetSeq = 1, owner = BOB), true))
            selection.resumeAtStart()

            assertEquals(SyncOutcome.Cancelled, engine().run(session, ALICE, selection))

            assertNull("no success recorded", repository.lastSuccess(ALICE))
            assertFalse("no first sync begun", repository.isFirstSyncRunning(ALICE))
            assertTrue("nothing written for the other account's choice", repository.storedLibraries(ALICE).isEmpty())

            assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES, SHOWS)))
            assertEquals(1, firstSync.asked)
            assertEquals(SyncOutcome.Completed, engine().run(session, ALICE, selection))

            assertTrue(row(MOVIES)!!.fetched)
            assertTrue(row(SHOWS)!!.fetched)
            assertEquals(5, repository.stored(ALICE).size)
        }

    // Binding item 6 (D7), with the Part 4A final fix D1: an old add's stamped row the new choice does not name goes
    // with the setup's own cache write (no longer only with the first sync's library set), and stays gone.
    @Test
    fun setup_clearsAnOldAddTheNewChoiceDoesNotName_andTheFirstSyncKeepsItGone() =
        runBlocking {
            repository.useAccount(ALICE)
            // The old choice: MOVIES chosen, extra being added. The resume puts the cache in line with it, so nothing
            // is left for a catch-up: what clears the old add below is the setup's own write.
            val old = StoredSelection(setOf(MOVIES), seq = 1, adding = mapOf(extra to 1L), resetSeq = 1, owner = ALICE)
            assertTrue(settings.saveSelection(old, true))
            selection.resumeAtStart()
            assertEquals(1L, row(extra)?.addStamp)
            assertTrue(selection.clear())
            assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES)))
            assertNull("the setup's own cache write dropped it", row(extra))

            assertEquals(SyncOutcome.Completed, engine().run(session, ALICE, selection))

            assertNull("the old add went with the old choice", row(extra))
            assertTrue(row(MOVIES)!!.fetched)
        }

    // --- With a stand-in for the selection ------------------------------------------------------------------------

    // Before the first item page of SHOWS is served, SHOWS is unticked and held: that page's write is refused.
    private fun holdShowsOnItsFirstPage(then: () -> Unit = {}) {
        var held = false
        server.respond = { call ->
            val itemPage = !call.idsOnly && !call.series
            if (!held && call.library.id == SHOWS && itemPage) {
                held = true
                runBlocking {
                    val until = clock.nowMs + 60_000
                    repository.applySelection(ALICE, setOf(MOVIES), holds = mapOf(SHOWS to until), nowMs = clock.nowMs)
                }
                then()
            }
            null
        }
    }

    // Binding item 3: a library refused once and still chosen is read again in the same run, from a fresh library set.
    @Test
    fun firstSync_refusedLibraryStillChosen_readsTheSetAgain_andWalksIt() =
        runBlocking {
            repository.useAccount(ALICE)
            val choice = StoredChoice(repository, clock) { setOf(MOVIES, SHOWS) }
            holdShowsOnItsFirstPage()

            assertEquals(SyncOutcome.Completed, engine().run(session, ALICE, choice))

            assertEquals("the set was read again once", 2, choice.reads)
            assertTrue(row(SHOWS)!!.fetched)
            assertEquals(2, repository.stored(ALICE).count { it.library == SHOWS })
        }

    // Binding item 3: a library refused and unticked meanwhile is not this run's to read: the run completes.
    @Test
    fun firstSync_refusedLibraryUntickedMeanwhile_completes() =
        runBlocking {
            repository.useAccount(ALICE)
            var chosen = setOf(MOVIES, SHOWS)
            val choice = StoredChoice(repository, clock) { chosen }
            holdShowsOnItsFirstPage { chosen = setOf(MOVIES) }

            assertEquals(SyncOutcome.Completed, engine().run(session, ALICE, choice))

            assertTrue(row(MOVIES)!!.fetched)
            assertFalse("held, and no longer chosen: left as it is", row(SHOWS)!!.fetched)
            assertTrue(repository.stored(ALICE).none { it.library == SHOWS })
        }

    // Binding item 3: a library the cache keeps refusing while the selection still names it: no success is recorded,
    // and the run fails with its own reason, retried like a server failure (a few times, then the screen's Retry).
    @Test
    fun firstSync_refusedLibraryStillPending_isNotASuccess() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            val until = clock.nowMs + 60_000
            repository.applySelection(ALICE, setOf(MOVIES), holds = mapOf(SHOWS to until), nowMs = clock.nowMs)
            var reads = 0
            // A selection that names SHOWS while the cache holds it (a cache write it never made).
            val stuck =
                FirstSyncLibraries {
                    reads++
                    setOf(MOVIES, SHOWS)
                }

            val outcome = engine().run(session, ALICE, stuck)

            assertEquals(SyncOutcome.Failed(SyncFailure.CacheRace), outcome)
            assertEquals(FirstSyncEngine.LIBRARY_SET_READS, reads)
            assertNull("no success recorded", repository.lastSuccess(ALICE))
            assertEquals(SyncState.LastSyncFailed(SyncFailure.CacheRace), repository.status(ALICE).first().state)
            assertTrue(row(MOVIES)!!.fetched)
            assertFalse(row(SHOWS)!!.fetched)
        }

    // A choice cleared while the run reads its set again (a refusal, then a sign-in's clear): the first sync it began
    // is not left running with nothing to read; it ends as a failure the worker retries, which reads the next choice.
    @Test
    fun firstSync_choiceClearedWhileReadingTheSetAgain_isNotLeftRunning() =
        runBlocking {
            repository.useAccount(ALICE)
            var chosen = setOf(MOVIES, SHOWS)
            val choice = StoredChoice(repository, clock) { chosen }
            holdShowsOnItsFirstPage { chosen = emptySet() }

            val outcome = engine().run(session, ALICE, choice)

            assertEquals(SyncOutcome.Failed(SyncFailure.CacheRace), outcome)
            assertFalse("no first sync left running", repository.isFirstSyncRunning(ALICE))
            assertNull(repository.lastSuccess(ALICE))
        }

    // --- Fix round 1 ----------------------------------------------------------------------------------------------

    // An item page of [id] (not an ID listing, not a series page).
    private fun PagedCall.isItemPageOf(id: ItemId): Boolean {
        val itemPage = !idsOnly && !series
        return itemPage && library.id == id
    }

    private val extras = SyncWorld.source("x", 2, extra)
    private val extraLibrary = Library(extra, "Invented Extra", LibraryKind.Movies)

    // A first sync shaped like its unique work: ensureRunning keeps a run that is going; retry replaces it, and the new
    // run starts once the old one has unwound. Each run is the engine over the selection's library set.
    private class UniqueWork(
        private val scope: CoroutineScope,
        private val run: suspend () -> SyncOutcome,
    ) : FirstSync {
        @Volatile var job: Job? = null

        override fun status(): Flow<LibraryStatus?> = flowOf(null)

        override fun ensureRunning() {
            if (job?.isActive != true) start(null)
        }

        override fun retry() {
            val old = job
            old?.cancel()
            start(old)
        }

        // A worker thread of its own, as WorkManager's: the test's thread stays free to set up meanwhile.
        @Suppress("InjectDispatcher")
        private fun start(after: Job?) {
            job =
                scope.launch(Dispatchers.IO) {
                    after?.join()
                    run()
                }
        }
    }

    // R1: a setup on the same account while a first sync of the old choice is still walking replaces that run. The
    // new libraries are walked, the dropped library's rows go, and nothing of the old choice is left once it ends.
    @Test
    fun sameAccountSetup_whileAnOldFirstSyncRuns_replacesIt_andWalksTheNewChoice() =
        runBlocking {
            val withExtra =
                PagedMediaServer(
                    listOf(movies, shows, extraLibrary),
                    mapOf(MOVIES to films, SHOWS to episodes, extra to extras),
                )
            val stored = InMemoryServerSettings(ServerRecord(session.address))
            val old = StoredSelection(setOf(MOVIES, SHOWS), seq = 1, resetSeq = 1, owner = ALICE)
            assertTrue(stored.saveSelection(old, true))
            // The old run is held on its first page of SHOWS, as a slow server would hold it.
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            withExtra.respond = { call ->
                if (call.isItemPageOf(SHOWS) && reached.count > 0) {
                    reached.countDown()
                    release.await(WAIT_S, TimeUnit.SECONDS)
                }
                null
            }
            val scope = CoroutineScope(SupervisorJob())
            lateinit var selection: LibrarySelection
            val engine = SyncWorld.engine(repository, withExtra, clock)
            val work = UniqueWork(scope) { engine.run(session, ALICE, selection) }
            selection = selectionOver(repository, stored, withExtra, clock, firstSync = work)
            try {
                // The start finds the chosen libraries unread and asks for the first sync: the old run.
                selection.resumeAtStart()
                assertTrue("the old run is walking SHOWS", reached.await(WAIT_S, TimeUnit.SECONDS))

                // The same user signs in again and keeps MOVIES, drops SHOWS and adds extra.
                assertTrue(selection.clear())
                assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES, extra)))
                val replacing = checkNotNull(work.job)
                release.countDown()
                withTimeout(WAIT_S * MS) { replacing.join() }

                assertTrue("the new library is walked", row(extra)!!.fetched)
                assertEquals(2, repository.stored(ALICE).count { it.library == extra })
                assertNull("the dropped library's row went", row(SHOWS))
                assertTrue("nothing of it is served", repository.stored(ALICE).none { it.library == SHOWS })
                assertTrue(row(MOVIES)!!.fetched)
                assertFalse(repository.isFirstSyncRunning(ALICE))
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    // C5: the old run gets to its end (finishSync and all) before the setup replaces it: the replacing run starts
    // at once, with nothing to wait for, and the end state is the same. The recorded success is not the new run's
    // start: the first sync records the earliest walk start across the chosen libraries (their checkpoints), so a
    // walk of a kept library ahead of now does not hide what changed since. MOVIES, kept, was walked by the old run,
    // so the success is the old run's start, and the test asserts that.
    @Test
    fun sameAccountSetup_afterTheOldFirstSyncEnded_walksTheNewChoice_andKeepsTheEarliestWalk() =
        runBlocking {
            val withExtra =
                PagedMediaServer(
                    listOf(movies, shows, extraLibrary),
                    mapOf(MOVIES to films, SHOWS to episodes, extra to extras),
                )
            val stored = InMemoryServerSettings(ServerRecord(session.address))
            val old = StoredSelection(setOf(MOVIES, SHOWS), seq = 1, resetSeq = 1, owner = ALICE)
            assertTrue(stored.saveSelection(old, true))
            val scope = CoroutineScope(SupervisorJob())
            lateinit var selection: LibrarySelection
            val engine = SyncWorld.engine(repository, withExtra, clock)
            val work = UniqueWork(scope) { engine.run(session, ALICE, selection) }
            selection = selectionOver(repository, stored, withExtra, clock, firstSync = work)
            try {
                val oldStart = clock.now()
                selection.resumeAtStart()
                withTimeout(WAIT_S * MS) { checkNotNull(work.job).join() }
                assertEquals("the old run ended", oldStart, repository.lastSuccess(ALICE))
                clock.instant += 1.hours

                assertTrue(selection.clear())
                assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES, extra)))
                withTimeout(WAIT_S * MS) { checkNotNull(work.job).join() }

                assertTrue("the new library is walked", row(extra)!!.fetched)
                assertNull("the dropped library's row went", row(SHOWS))
                assertTrue(repository.stored(ALICE).none { it.library == SHOWS })
                assertFalse(repository.isFirstSyncRunning(ALICE))
                assertEquals("the earliest walk of a kept library", oldStart, repository.lastSuccess(ALICE))
            } finally {
                scope.cancel()
            }
        }

    // D-MED-2: a pending row another write of the set put in while this run walked is not this run's to call fetched.
    @Test
    fun firstSync_aPendingRowPutInMidRun_isNotMarkedFetched_andIsWalkedLater() =
        runBlocking {
            repository.useAccount(ALICE)
            val withExtra = PagedMediaServer(listOf(movies, extraLibrary), mapOf(MOVIES to films, extra to extras))
            var put = false
            withExtra.respond = { call ->
                if (!put && call.isItemPageOf(MOVIES)) {
                    put = true
                    runBlocking { repository.applySelection(ALICE, setOf(MOVIES, extra), nowMs = clock.nowMs) }
                }
                null
            }
            val engine = SyncWorld.engine(repository, withExtra, clock)

            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES)))
            assertFalse("never walked, so not fetched", row(extra)!!.fetched)

            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES, extra)))
            assertTrue(row(extra)!!.fetched)
            assertEquals(2, repository.stored(ALICE).count { it.library == extra })
        }

    // A7: the first sync's checkpoints keep its own count on every page after the first, as they always have; the
    // server's count goes only into the first page's.
    @Test
    fun firstSync_checkpointsAfterTheFirstPage_keepTheRunsCount() =
        runBlocking {
            repository.useAccount(ALICE)
            // Counted at 3, then an item is added before the first page: the server's first page says 4.
            var added = false
            server.respond = { call ->
                if (!added && call.isItemPageOf(MOVIES)) {
                    added = true
                    films.items += SyncWorld.item("m", 9)
                }
                null
            }

            val outcome = SyncWorld.engine(repository, server, clock, pageSize = 2).run(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(3, repository.checkpoint(ALICE, MOVIES)?.totalCount)
        }

    // Amendment 4 (D18): the first sync's unlisted step leaves a marked library pending and marked.
    @Test
    fun firstSync_unlistedMarkedLibrary_keepsMarkAndPending() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 3L), nowMs = clock.nowMs)
            assertTrue(repository.revertUnproven(ALICE, SHOWS, 3L))
            server.libraries = listOf(movies)

            val outcome = engine().run(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, outcome)
            assertTrue(row(MOVIES)!!.fetched)
            assertFalse("the server does not list it: still pending", row(SHOWS)!!.fetched)
            assertTrue("and still marked", row(SHOWS)!!.rewalkPending)
        }

    private companion object {
        const val WAIT_S = 10L
        const val MS = 1_000L
    }
}
