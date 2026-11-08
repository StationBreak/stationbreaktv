// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 26, Part 4 (schema 11): `library.coveredFromMs` is the earliest moment a library's next incremental pass must
 * read from. A hold placed sets it to the earlier of the one it has and now, and a hold already in place keeps it; the
 * add walk's end and a rewalk's end write the walk's run. The incremental pass reads each library from the earlier of
 * the account's window and its own coverage, and clears, with the recorded moment, only the coverage it read for the
 * libraries it walked, and only while unchanged. Invented libraries and names only.
 */
class CoveredFromTest {
    private val clock = FakeClock()
    private val opened = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = opened.first
    private val database = opened.second
    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("s", 3, SHOWS)
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to films, SHOWS to episodes))

    @After
    fun close() = repository.close()

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun row(library: ItemId): LibraryEntity {
        val rows = repository.dao.libraries("server-1", "alice")
        return rows.single { it.libraryId == library.value }
    }

    private suspend fun cover(
        library: ItemId,
        ms: Long,
    ) = database.useWriterConnection {
        it.execSQL("UPDATE library SET coveredFromMs = $ms WHERE libraryId = '${library.value}'")
    }

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, SHOWS))
        assertEquals(SyncOutcome.Completed, outcome)
        server.calls.clear()
    }

    private fun sinceOf(library: ItemId): Set<Instant?> =
        server.calls
            .filter { it.library.id == library && !it.idsOnly }
            .map { it.changedSince }
            .toSet()

    @Test
    fun holdPlaced_minOfExistingAndNow() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)

            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(SHOWS to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            assertEquals("no coverage before: now", clock.nowMs, row(SHOWS).coveredFromMs)

            cover(MOVIES, clock.nowMs - 5_000)
            repository.applySelection(
                ALICE,
                emptySet(),
                holds = mapOf(MOVIES to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            assertEquals("an earlier coverage stays", clock.nowMs - 5_000, row(MOVIES).coveredFromMs)
        }

    @Test
    fun heldAgain_keepsValue() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            val placed = clock.nowMs
            repository.applySelection(ALICE, setOf(MOVIES), holds = mapOf(SHOWS to placed + 60_000), nowMs = placed)

            clock.stepMs(10_000)
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(SHOWS to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            assertEquals("held again: the hold's own moment stays", placed, row(SHOWS).coveredFromMs)
            // Even with the clock set back past it: only placing a hold sets the coverage, never holding again.
            clock.atMs(placed - 30_000)
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(SHOWS to placed + 90_000),
                nowMs = clock.nowMs,
            )
            assertEquals("held again, clock set back: still the hold's own moment", placed, row(SHOWS).coveredFromMs)
            clock.atMs(placed + 10_000)

            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            assertNull(row(SHOWS).heldUntilMs)
            assertEquals(
                "re-ticked with no stamp: its next incremental reads from the hold",
                placed,
                row(SHOWS).coveredFromMs,
            )
        }

    @Test
    fun addWalkEnd_writesRun() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 4L), nowMs = clock.nowMs)

            assertTrue(repository.markAddWalked(ALICE, SHOWS, 4L, RUN))

            assertEquals(RUN, row(SHOWS).coveredFromMs)
            assertNull("only the walked library", row(MOVIES).coveredFromMs)
        }

    @Test
    fun finishRewalk_writesRun() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 4L), nowMs = clock.nowMs)
            repository.revertUnproven(ALICE, SHOWS, 4L)

            assertEquals(RewalkFinish.Finished, repository.finishRewalk(ALICE, SHOWS, RUN))

            assertEquals(RUN, row(SHOWS).coveredFromMs)
            assertTrue(row(SHOWS).fetched)
            assertFalse(row(SHOWS).rewalkPending)
        }

    @Test
    fun incremental_readsEachLibraryFromItsOwnSince() =
        runBlocking {
            firstSyncDone()
            val last = clock.now()
            clock.instant += 2.hours
            val covered = last - 1.hours
            cover(SHOWS, covered.toEpochMilliseconds())

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            val margin = LibraryPass.DEFAULT_MARGIN
            assertEquals("the account's window", setOf(last - margin), sinceOf(MOVIES))
            assertEquals("its own, earlier coverage, items and series alike", setOf(covered - margin), sinceOf(SHOWS))
            assertTrue("the series were read too", server.calls.any { it.library.id == SHOWS && it.series })
            assertNull("the walked library's coverage is cleared", row(SHOWS).coveredFromMs)
        }

    @Test
    fun incremental_coverageLaterThanTheWindow_readsTheWindow() =
        runBlocking {
            firstSyncDone()
            val last = clock.now()
            clock.instant += 2.hours
            cover(SHOWS, (last + 30.minutes).toEpochMilliseconds())

            StayCurrentWorld.engine(repository, server, clock).incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(setOf(last - LibraryPass.DEFAULT_MARGIN), sinceOf(SHOWS))
        }

    @Test
    fun finishIncremental_clearsOnlyUnchangedValue() =
        runBlocking {
            firstSyncDone()
            clock.instant += 2.hours
            cover(MOVIES, RUN)
            cover(SHOWS, RUN)
            // An add of SHOWS lands meanwhile (as if): its coverage is newer than the one the pass read.
            server.respond = { call ->
                if (call.library.id == SHOWS) runBlocking { cover(SHOWS, RUN + 1) }
                null
            }

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertNull("unchanged since the pass read it: cleared", row(MOVIES).coveredFromMs)
            assertEquals("written during the pass: kept", RUN + 1, row(SHOWS).coveredFromMs)
        }

    // Data review MED-1: a hold placed during the pass, on a library covered from earlier than now, leaves the value
    // as it was (min(E, now) is E), so the compare-and-set alone would clear it. A held library keeps its coverage.
    @Test
    fun finishIncremental_heldDuringPass_keepsCoverage() =
        runBlocking {
            firstSyncDone()
            clock.instant += 2.hours
            cover(SHOWS, RUN)
            // The series call of SHOWS comes after its last item page: SHOWS is unticked and held there.
            var held = false
            server.respond = { call ->
                if (!held && call.library.id == SHOWS && call.series) {
                    held = true
                    runBlocking {
                        val holds = mapOf(SHOWS to clock.nowMs + 60_000)
                        repository.applySelection(ALICE, setOf(MOVIES), holds = holds, nowMs = clock.nowMs)
                    }
                }
                null
            }

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertTrue("the hold was placed during the pass", held)
            assertEquals("held during the pass: its coverage stays", RUN, row(SHOWS).coveredFromMs)
        }

    @Test
    fun finishIncremental_firstSyncRunning_clearsNothing() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = clock.nowMs)
            cover(MOVIES, RUN)
            repository.beginFirstSync(ALICE)

            assertFalse(repository.finishIncremental(ALICE, clock.now(), mapOf(MOVIES to RUN)))

            assertEquals(RUN, row(MOVIES).coveredFromMs)
        }

    @Test
    fun unwalkedLibrary_keepsValue() =
        runBlocking {
            firstSyncDone()
            clock.instant += 2.hours
            cover(MOVIES, RUN)
            cover(SHOWS, RUN)
            // The server no longer lists SHOWS: the pass does not walk it.
            server.libraries = listOf(movies)

            val result = engine().incremental(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertNull(row(MOVIES).coveredFromMs)
            assertEquals("not walked, so not covered", RUN, row(SHOWS).coveredFromMs)
        }

    private companion object {
        const val RUN = 1_699_000_000_000L
    }
}
