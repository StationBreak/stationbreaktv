// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 06 task 4.4: the ID reconciliation. It reads the complete ID list of each ticked library and only then acts: an
 * ID the server no longer has gets a tombstone, one that comes back is revived, and one the cache never got (the gap
 * from the first sync: an item that shifted across a page boundary while the walk ran) is fetched. A failed, partial or
 * empty-for-a-full-library answer changes nothing.
 */
class ReconciliationTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val library = SyncWorld.source("m", 300)
    private val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to library))

    @After
    fun close() = repository.close()

    private suspend fun firstSyncDone() {
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
        assertEquals(SyncOutcome.Completed, outcome)
        server.calls.clear()
        clock.instant += 1.hours
    }

    private fun engine() = StayCurrentWorld.engine(repository, server, clock)

    private suspend fun reconcile(engine: StayCurrentEngine = engine()): SyncOutcome = engine.reconcile(session, ALICE, setOf(MOVIES))

    private suspend fun stored() = repository.stored(ALICE).associateBy { it.item.id.value }

    private suspend fun tombstones() = stored().filterValues { it.deletedAt != null }.keys

    @Test
    fun aMissingIdGetsATombstoneAndLeavesSnapshots() =
        runBlocking {
            firstSyncDone()
            val gone = library.items.removeAt(5)

            val engine = engine()
            val outcome = reconcile(engine)

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(setOf(gone.id.value), tombstones())
            assertEquals(clock.now(), stored().getValue(gone.id.value).deletedAt)
            assertEquals(299, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
            assertEquals(ReconcileSummary(tombstoned = 1, revived = 0, fetched = 0), engine.reconciled)
            assertTrue("only IDs were read", server.calls.all { it.idsOnly })
        }

    @Test
    fun aReturningIdIsRevived() =
        runBlocking {
            firstSyncDone()
            val gone = library.items.removeAt(5)
            reconcile()
            library.items.add(5, gone)
            clock.instant += 1.hours

            val engine = engine()
            reconcile(engine)

            assertTrue(tombstones().isEmpty())
            assertEquals(300, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
            assertEquals(ReconcileSummary(tombstoned = 0, revived = 1, fetched = 0), engine.reconciled)
        }

    @Test
    fun anItemThatStaysGoneKeepsItsFirstTombstoneTime() =
        runBlocking {
            firstSyncDone()
            val gone = library.items.removeAt(5)
            reconcile()
            val first = clock.now()
            clock.instant += 2.hours

            reconcile()

            assertEquals(first, stored().getValue(gone.id.value).deletedAt)
        }

    @Test
    fun aListThatIsCutShortChangesNothingAndRecordsTheFailure() =
        runBlocking {
            firstSyncDone()
            repeat(20) { library.items.removeAt(it) }
            server.respond = { call -> if (call.startIndex >= 100) MediaServerError.Timeout() else null }

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertTrue(tombstones().isEmpty())
            val status = repository.activeStatus().first()!!
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Network), status.state)
            assertEquals(EPOCH, status.lastSuccess)
        }

    @Test
    fun aFailedLibrariesCallChangesNothing() =
        runBlocking {
            firstSyncDone()
            library.items.clear()
            server.librariesFailure = MediaServerError.Timeout()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertTrue(tombstones().isEmpty())
            assertEquals(300, stored().size)
        }

    @Test
    fun anEmptyAnswerForALibraryThatHadItemsIsSuspectAndLeavesTheDataAlone() =
        runBlocking {
            firstSyncDone()
            library.items.clear()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertTrue(tombstones().isEmpty())
            assertEquals(300, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
            val status = repository.activeStatus().first()!!
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Suspect), status.state)
            assertEquals(EPOCH, status.lastSuccess)
        }

    @Test
    fun aLibraryThatWasAlwaysEmptyIsNotSuspect() =
        runBlocking {
            library.items.clear()
            firstSyncDone()

            val outcome = reconcile()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
        }

    @Test
    fun aLaterLibraryThatIsSuspectDoesNotUndoAnEarlierOnesCompleteList() =
        runBlocking {
            val shows = SyncWorld.source("s", 40, SHOWS)
            val both = SyncWorld.server(movies to library, SyncWorld.shows to shows)
            repository.useAccount(ALICE)
            SyncWorld.engine(repository, both, clock).run(session, ALICE, setOf(MOVIES, SHOWS))
            library.items.removeAt(0)
            shows.items.clear()

            val engine = StayCurrentWorld.engine(repository, both, clock)
            val outcome = engine.reconcile(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            // The movies list was complete and sound, so its deletion stands; the shows are untouched.
            assertEquals(setOf("m-00000"), tombstones())
        }

    @Test
    fun aLibraryThatChangedWhileItsListWasReadDoesNotTombstoneAnything() =
        runBlocking {
            firstSyncDone()
            var changed = false
            server.respond = { call ->
                if (call.startIndex == 100 && !changed) {
                    changed = true
                    library.items.removeAt(10)
                }
                null
            }

            val outcome = reconcile()

            // The list shifted under the read, so what it lacks cannot be trusted; the next open reads it again.
            assertEquals(SyncOutcome.Completed, outcome)
            assertTrue(tombstones().isEmpty())
            server.respond = { null }
            reconcile()
            assertEquals(setOf("m-00010"), tombstones())
        }

    @Test
    fun anItemThatShiftedPastAPageBoundaryDuringTheFirstWalkIsFetched() =
        runBlocking {
            repository.useAccount(ALICE)
            var removed = false
            server.respond = { call ->
                // After page one was written, an item that page holds is deleted: everything behind it moves up one.
                if (call.startIndex == 100 && call.changedSince == null && !removed) {
                    removed = true
                    library.items.removeAt(50)
                }
                null
            }
            SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))
            server.respond = { null }
            server.calls.clear()
            clock.instant += 1.hours
            // The neighbour that moved from position 100 to 99 was never asked for, and nothing about it changed.
            assertNull(stored()["m-00100"])
            assertEquals(299, stored().size)

            val engine = engine()
            reconcile(engine)

            assertEquals(setOf("m-00050"), tombstones())
            val neighbour = stored().getValue("m-00100")
            assertNull(neighbour.deletedAt)
            assertEquals(ItemId("m-00100"), neighbour.item.id)
            assertEquals(ReconcileSummary(tombstoned = 1, revived = 0, fetched = 1), engine.reconciled)
            assertEquals(299, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
        }

    @Test
    fun aSuccessfulReconciliationClearsAnEarlierFailureButKeepsTheLastSuccess() =
        runBlocking {
            firstSyncDone()
            server.respond = { MediaServerError.Timeout() }
            reconcile()
            server.respond = { null }
            clock.instant += 5.minutes

            reconcile()

            val status = repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
            assertEquals(EPOCH, status.lastSuccess)
        }

    @Test
    fun onlyTheTickedLibrariesAreRead() =
        runBlocking {
            val shows = SyncWorld.source("s", 20, SHOWS)
            val both = SyncWorld.server(movies to library, SyncWorld.shows to shows)
            repository.useAccount(ALICE)
            SyncWorld.engine(repository, both, clock).run(session, ALICE, setOf(MOVIES))
            both.calls.clear()

            StayCurrentWorld.engine(repository, both, clock).reconcile(session, ALICE, setOf(MOVIES))

            assertEquals(setOf(MOVIES), both.calls.map { it.library.id }.toSet())
        }

    @Test
    fun anAccountThatSignedOutMidWayIsLeftAlone() =
        runBlocking {
            firstSyncDone()
            library.items.removeAt(0)
            server.respond = {
                runBlocking { repository.forget(ALICE) }
                null
            }

            val outcome = reconcile()

            assertEquals(SyncOutcome.Cancelled, outcome)
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }
}
