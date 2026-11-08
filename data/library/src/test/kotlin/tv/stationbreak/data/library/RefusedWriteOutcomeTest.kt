// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedCall
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SeriesSource
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours

/**
 * Spec 26, Part 4 (schema 11, MED-8): what a pass does when a library row refuses one of its writes (the library
 * was held, dropped or stamped while the pass ran). A stay-current pass stops as Cancelled with nothing recorded: no
 * failure, no new last success, the backfill flag kept. The first sync stops that library's walk without marking it
 * fetched and goes on with the next one; one no longer chosen is then left as it is (FirstSyncEngineTest has the
 * outcome of one still chosen). Invented libraries and names only.
 */
class RefusedWriteOutcomeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 3, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))

    @After
    fun close() = repository.close()

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        assertEquals(
            SyncOutcome.Completed,
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, SHOWS)),
        )
        server.calls.clear()
    }

    // Unticks and holds SHOWS: every later write to it is refused.
    private fun holdShows() =
        runBlocking {
            val until = clock.nowMs + 60_000
            repository.applySelection(ALICE, setOf(MOVIES), holds = mapOf(SHOWS to until), nowMs = clock.nowMs)
        }

    // The server call before which SHOWS was held, once it was.
    @Volatile private var heldAt: PagedCall? = null

    // Holds SHOWS before the first call of SHOWS that [at] picks is served (once).
    private fun holdShowsAt(at: (PagedCall) -> Boolean) {
        server.respond = { call ->
            if (heldAt == null && call.library.id == SHOWS && at(call)) {
                heldAt = call
                holdShows()
            }
            null
        }
    }

    // Before the first item page of SHOWS is served, SHOWS is unticked and held: the page's write is refused.
    private fun holdShowsOnItsFirstPage() = holdShowsAt { !it.idsOnly && !it.series }

    // Holds SHOWS on the first read of a series by ID: the hidden-series write that follows is refused.
    private fun holdShowsOnTheFirstSeriesById() {
        var done = false
        server.respondToSeriesById = {
            if (!done) {
                done = true
                holdShows()
            }
            null
        }
    }

    // A stay-current pass that stopped for a refused write recorded nothing: no failure, no new last success.
    private suspend fun assertNothingRecorded(before: tv.stationbreak.core.domain.LibraryStatus) {
        val after = repository.status(ALICE).first()
        assertEquals("no failure recorded", SyncState.Idle, after.state)
        assertEquals("finishIncremental was not reached: no new last success", before.lastSuccess, after.lastSuccess)
    }

    @Test
    fun stayCurrent_incremental_mismatch_cancelled_nothingRecorded() =
        runBlocking {
            firstSyncDone()
            val before = repository.status(ALICE).first()
            clock.instant += 1.hours
            episodes.items += SyncWorld.item("s", 10, SHOWS).copy(dateCreated = clock.now())
            holdShowsOnItsFirstPage()

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            val after = repository.status(ALICE).first()
            assertEquals("no failure recorded", SyncState.Idle, after.state)
            assertEquals("the recorded moment did not move", before.lastSuccess, after.lastSuccess)
            assertFalse(
                "nothing written to the held library",
                repository.stored(ALICE).any { it.item.id.value == "s-00010" },
            )
        }

    @Test
    fun stayCurrent_backfill_mismatch_cancelled_flagKept() =
        runBlocking {
            firstSyncDone()
            repository.dao.setBackfillPending("server-1", "alice", true)
            holdShowsOnItsFirstPage()

            val result = engine().backfill(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertTrue("the flag stays set", repository.backfillPending(ALICE))
            assertEquals(SyncState.Idle, repository.status(ALICE).first().state)
        }

    @Test
    fun firstSync_mismatch_stopsThatLibrary_withoutMarkingIt_andGoesOn() =
        runBlocking {
            repository.useAccount(ALICE)
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            // MOVIES is walked first, then SHOWS; SHOWS is unticked and held before its first page is written, so the
            // library set read again after the refusal no longer names it.
            var chosen = setOf(MOVIES, SHOWS)
            server.respond = { call ->
                val itemPage = !call.idsOnly && !call.series
                if (heldAt == null && call.library.id == SHOWS && itemPage) {
                    heldAt = call
                    holdShows()
                    chosen = setOf(MOVIES)
                }
                null
            }

            val choice = StoredChoice(repository, clock) { chosen }
            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, choice)

            assertEquals(SyncOutcome.Completed, outcome)
            val rows = repository.dao.libraries("server-1", "alice").associateBy { it.libraryId }
            assertTrue(rows.getValue(MOVIES.value).fetched)
            assertFalse("the refused library is left pending", rows.getValue(SHOWS.value).fetched)
            assertTrue(repository.stored(ALICE).none { it.library == SHOWS })
            assertEquals(3, repository.stored(ALICE).count { it.library == MOVIES })
        }

    // LOW-1: each stay-current write path, refused, ends the pass as Cancelled with nothing recorded.

    @Test
    fun incremental_walkSeries_refused_cancelled() =
        runBlocking {
            server.seriesSources[SHOWS] = SeriesSource()
            firstSyncDone()
            val before = repository.status(ALICE).first()
            clock.instant += 1.hours
            val source = server.seriesSources.getValue(SHOWS)
            source.series += series(hexId(950))
            source.saved[ItemId(hexId(950))] = clock.now()
            // The series page comes after the last item page of SHOWS.
            holdShowsAt { it.series && !it.idsOnly }

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertNothingRecorded(before)
            assertTrue("the series was not written", repository.currentSeries(ALICE).isEmpty())
            assertEquals("the walk stopped at the refused page: no call after it", heldAt, server.calls.last())
        }

    @Test
    fun incremental_readNewHiddenSeries_refused_cancelled() =
        runBlocking {
            firstSyncDone()
            val before = repository.status(ALICE).first()
            clock.instant += 1.hours
            // A new episode names a series the series list leaves out; the server has it by ID.
            episodes.items += episodeOf(900, 950, SHOWS).copy(dateCreated = clock.now())
            server.hiddenSeriesSources[ItemId(hexId(950))] = series(hexId(950))
            holdShowsOnTheFirstSeriesById()

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertNothingRecorded(before)
            assertTrue("the hidden series was not written", repository.currentSeries(ALICE).isEmpty())
        }

    @Test
    fun reconcile_fetchMissing_refused_cancelled() =
        runBlocking {
            firstSyncDone()
            val before = repository.status(ALICE).first()
            // SHOWS lists an item the cache lacks: the reconcile fetches it with an item walk.
            episodes.items += SyncWorld.item("s", 20, SHOWS)
            holdShowsOnItsFirstPage()

            val result = engine().reconcile(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertNothingRecorded(before)
            val written = repository.stored(ALICE).any { it.item.id.value == "s-00020" }
            assertFalse("the missing item was not written", written)
        }

    @Test
    fun reconcile_fetchMissingSeries_refused_cancelled() =
        runBlocking {
            server.seriesSources[SHOWS] = SeriesSource()
            firstSyncDone()
            val before = repository.status(ALICE).first()
            // SHOWS lists a series the cache lacks: the reconcile fetches it with a series walk.
            server.seriesSources.getValue(SHOWS).series += series(hexId(951))
            holdShowsAt { it.series && !it.idsOnly }

            val result = engine().reconcile(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertNothingRecorded(before)
            assertTrue("the missing series was not written", repository.currentSeries(ALICE).isEmpty())
        }

    @Test
    fun reconcile_confirmHidden_refused_cancelledBeforeApplyReconcile() =
        runBlocking {
            firstSyncDone()
            val before = repository.status(ALICE).first()
            // A stored episode names a series the list leaves out, which the reconcile confirms by ID.
            val episode = episodeOf(900, 950, SHOWS)
            repository.put(ALICE, SHOWS, episode)
            episodes.items += episode
            server.hiddenSeriesSources[ItemId(hexId(950))] = series(hexId(950))
            // And an item the server no longer lists: applyReconcile would tombstone it.
            val gone = episodes.items.removeAt(0)
            holdShowsOnTheFirstSeriesById()

            val result = engine().reconcile(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Cancelled, result.outcome)
            assertNothingRecorded(before)
            val row = repository.stored(ALICE).single { it.item.id == gone.id }
            assertEquals("applyReconcile was not reached: nothing tombstoned", null, row.deletedAt)
            assertTrue("the hidden series was not written", repository.currentSeries(ALICE).isEmpty())
        }
}
