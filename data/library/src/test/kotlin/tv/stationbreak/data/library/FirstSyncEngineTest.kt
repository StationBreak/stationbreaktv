// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows

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

            override fun ensureRunning() {
                asked++
            }

            override fun retry() = Unit
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

    // Binding item 6 (D7): a setup keeps the old cache rows, an add's stamped row among them; the first sync's library
    // set is the new choice's, so the old add the new choice does not name goes.
    @Test
    fun firstSync_afterSetup_clearsAnOldAddTheNewChoiceDoesNotName() =
        runBlocking {
            repository.useAccount(ALICE)
            // The old choice: MOVIES chosen, extra being added. The resume puts the cache in line with it, so nothing
            // is left for a catch-up: what clears the old add below is the first sync's library set itself.
            val old = StoredSelection(setOf(MOVIES), seq = 1, adding = mapOf(extra to 1L), resetSeq = 1, owner = ALICE)
            assertTrue(settings.saveSelection(old, true))
            selection.resumeAtStart()
            assertEquals(1L, row(extra)?.addStamp)
            assertTrue(selection.clear())
            assertEquals(SetupResult.Saved, selection.choose(setOf(MOVIES)))
            assertEquals("the setup itself leaves the old row", 1L, row(extra)?.addStamp)

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
    // and the run fails as a server failure would (retried a few times, then the screen's Retry).
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

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), outcome)
            assertEquals(FirstSyncEngine.LIBRARY_SET_READS, reads)
            assertNull("no success recorded", repository.lastSuccess(ALICE))
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Server), repository.status(ALICE).first().state)
            assertTrue(row(MOVIES)!!.fetched)
            assertFalse(row(SHOWS)!!.fetched)
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
}
