// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.CollectionCallKind
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncFailure
import kotlin.time.Instant

/**
 * Spec 10 task 2.5.3 (Vimes H4, M2): the collection pass has its own cursor, `collectionsSinceMs` in `collection_sync`.
 * It moves to the pass's start only when the whole pass completed: the changed BoxSets, every member re-read and the ID
 * listing. A failed pass leaves it as it was, whatever the item passes do, and the next pass asks from
 * `min(cursor, start) - margin`. The row is not a column of `account_state`, so an item-only sync cannot wipe it.
 */
class CollectionCursorTest {
    private val rig = CollectionRig()
    private val repository get() = rig.repository
    private val start = rig.clock.nowMs
    private val margin = FirstSyncEngine.DEFAULT_MARGIN

    @After
    fun close() = repository.close()

    private fun listingCalls() = rig.server.collectionCalls.filter { it.kind == CollectionCallKind.Listing && it.startIndex == 0 }

    private suspend fun firstPass() {
        (1..3).forEach { rig.serve(it, *rig.movies(it * 10)) }
        assertEquals(SyncOutcome.Completed, rig.run().outcome)
        rig.server.collectionCalls.clear()
    }

    @Test
    fun noRowExistsUntilAPassCompletes() =
        runBlocking {
            assertNull(repository.collectionSync(ALICE))
            assertEquals(false, repository.collectionsSynced(ALICE))
        }

    @Test
    fun theFirstCompletePassSetsTheCursorAndTheRelistToItsStart() =
        runBlocking {
            firstPass()

            assertEquals(CollectionSyncEntity("server-1", "alice", start, start), repository.collectionSync(ALICE))
            assertEquals(true, repository.collectionsSynced(ALICE))
        }

    @Test
    fun theFirstPassListsEverythingWithNoChangedSinceFilter() =
        runBlocking {
            (1..3).forEach { rig.serve(it, *rig.movies(it * 10)) }

            rig.run()

            assertNull(listingCalls().single().changedSince)
        }

    @Test
    fun aLaterPassAsksForWhatChangedSinceTheCursorLessTheMargin() =
        runBlocking {
            firstPass()
            rig.clock.stepMs(30 * 60_000)

            rig.run()

            assertEquals(Instant.fromEpochMilliseconds(start) - margin, listingCalls().single().changedSince)
        }

    @Test
    fun onlyTheChangedBoxSetsHaveTheirMembersReRead() =
        runBlocking {
            firstPass()
            rig.clock.stepMs(30 * 60_000)
            rig.source.saved[rig.box(2)] = Instant.fromEpochMilliseconds(rig.clock.nowMs)
            rig.source.members.getValue(rig.box(2)) += rig.movies(99)

            val result = rig.run()

            assertEquals(1, result.summary.changed)
            assertEquals(
                listOf(rig.box(2)),
                rig.server.collectionCalls
                    .filter { it.kind == CollectionCallKind.Members }
                    .map { it.collection },
            )
            assertEquals(listOf(rig.film(20).value, rig.film(99).value), rig.current(2))
        }

    @Test
    fun aCompletePassMovesTheCursorToItsStartAndKeepsTheRelist() =
        runBlocking {
            firstPass()
            rig.clock.stepMs(30 * 60_000)
            val second = rig.clock.nowMs

            rig.run()

            assertEquals(CollectionSyncEntity("server-1", "alice", second, start), repository.collectionSync(ALICE))
        }

    @Test
    fun aFailedMemberReadLeavesTheCursorAndTheChangeIsStillFetchedNextTime() =
        runBlocking {
            firstPass()
            rig.clock.stepMs(30 * 60_000)
            rig.source.saved[rig.box(2)] = Instant.fromEpochMilliseconds(rig.clock.nowMs)
            rig.source.members.getValue(rig.box(2)) += rig.movies(99)
            rig.server.respondToCollections = { if (it.kind == CollectionCallKind.Members) MediaServerError.Timeout() else null }

            val failed = rig.run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), failed.outcome)
            assertEquals(CollectionSyncEntity("server-1", "alice", start, start), repository.collectionSync(ALICE))

            // A good item pass in between does not move it; the next collection pass still asks from the old cursor.
            repository.finishSync(ALICE, Instant.fromEpochMilliseconds(rig.clock.nowMs))
            rig.server.respondToCollections = { null }
            rig.server.collectionCalls.clear()
            rig.clock.stepMs(30 * 60_000)

            val good = rig.run()

            assertEquals(SyncOutcome.Completed, good.outcome)
            assertEquals(Instant.fromEpochMilliseconds(start) - margin, listingCalls().single().changedSince)
            assertEquals(listOf(rig.film(20).value, rig.film(99).value), rig.current(2))
        }

    @Test
    fun aFailedPassNeverMovesTheRelist() =
        runBlocking {
            (1..3).forEach { rig.serve(it, *rig.movies(it * 10)) }
            rig.server.respondToCollections = { if (it.kind == CollectionCallKind.Ids) MediaServerError.Timeout() else null }

            rig.run()

            assertNull(repository.collectionSync(ALICE))
            assertEquals(false, repository.collectionsSynced(ALICE))
        }

    @Test
    fun aClockSetBackAsksFromNowLessTheMarginAndRecordsNow() =
        runBlocking {
            firstPass()
            rig.clock.rewindMs(2 * 60 * 60_000)
            val now = rig.clock.nowMs

            rig.run()

            assertEquals(Instant.fromEpochMilliseconds(now) - margin, listingCalls().single().changedSince)
            assertEquals(now, repository.collectionSync(ALICE)?.collectionsSinceMs)
        }

    @Test
    fun aTombstonedBoxSetThatIsListedAgainIsRevivedEvenWhenItsSaveTimeIsOld() =
        runBlocking {
            firstPass()
            val gone = rig.source.collections.single { it.id == rig.box(3) }
            rig.source.collections.remove(gone)
            rig.clock.stepMs(30 * 60_000)
            rig.run()
            rig.clock.stepMs(30 * 60_000)
            // It comes back, but the changed-since listing does not return it.
            rig.source.collections += gone

            val result = rig.run()

            assertEquals(1, result.summary.revived)
            assertNull(repository.collections(ALICE).single { it.collectionId == rig.box(3).value }.deletedAtMs)
        }

    @Test
    fun aBoxSetTheCacheLacksIsRecoveredFromTheIdListing() =
        runBlocking {
            firstPass()
            rig.clock.stepMs(30 * 60_000)
            // Added on the server with a save time before the cursor (say the earlier pass failed before it).
            rig.serve(7, *rig.movies(70))
            rig.source.saved[rig.box(7)] = Instant.fromEpochMilliseconds(start - 3_600_000)

            val result = rig.run()

            assertEquals(1, result.summary.inserted)
            assertEquals(listOf(rig.film(70).value), rig.current(7))
        }

    @Test
    fun theRowSurvivesEveryWriteOfAccountState() =
        runBlocking {
            firstPass()
            val row = repository.collectionSync(ALICE)

            // account_state is written whole with INSERT OR REPLACE by each of these; collection_sync is not a column of it.
            repository.beginFirstSync(ALICE)
            repository.setTotal(ALICE, 10)
            repository.finishSync(ALICE, Instant.fromEpochMilliseconds(start))
            repository.failSync(ALICE, SyncFailure.Network)
            repository.clearFailure(ALICE)

            assertEquals(row, repository.collectionSync(ALICE))
        }
}
