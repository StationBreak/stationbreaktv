// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 2.3 (Vimes M4): a collection that was tombstoned and is listed again has its members re-read in the same
 * pass, and the revival, its `collection_version` row and the member diff take ONE stamp in one transaction. A tombstone
 * adds no version. At the DAO level the write API takes the revival and the member list together.
 */
class CollectionReviveTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs
    private val box = ItemId("box01")

    @After
    fun close() = repository.close()

    private suspend fun current() = repository.collections(ALICE).single()

    private suspend fun tombstoned(): Long {
        repository.writeCollection(ALICE, box, "x", movies("a", "b"))
        clock.stepMs(10_000)
        repository.tombstoneCollections(ALICE, listOf(box))
        return clock.nowMs
    }

    @Test
    fun aTombstoneSetsTheTimeAndAddsNoVersionAndKeepsTheMembers() =
        runBlocking {
            val deletedAt = tombstoned()

            assertEquals(deletedAt, current().deletedAtMs)
            assertEquals(start, current().seenFromMs)
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
            assertEquals(listOf("a:movie", "b:movie"), repository.currentMembers(ALICE, box.value))
        }

    @Test
    fun anExistingTombstoneKeepsItsFirstTime() =
        runBlocking {
            val deletedAt = tombstoned()
            clock.stepMs(10_000)

            repository.tombstoneCollections(ALICE, listOf(box))

            assertEquals(deletedAt, current().deletedAtMs)
        }

    @Test
    fun theRevivalTheVersionAndTheMemberDiffShareOneStamp() =
        runBlocking {
            val deletedAt = tombstoned()
            clock.stepMs(10_000)
            val revivedAt = clock.nowMs

            val write = repository.writeCollection(ALICE, box, "x", movies("b", "c"))

            assertEquals(CollectionWrite(revivedAt, inserted = false, revived = true, added = 1, removed = 1), write)
            assertNull(current().deletedAtMs)
            assertEquals(revivedAt, current().seenFromMs)
            assertEquals(start, current().firstSeenMs)
            // The version keeps the tombstoned lifecycle, ending at the stamp.
            assertEquals(
                listOf(CollectionVersionEntity("server-1", "alice", "box01", start, revivedAt, deletedAt)),
                repository.collectionVersions(ALICE),
            )
            val members = repository.collectionMembers(ALICE).associateBy { it.memberId }
            assertEquals(revivedAt, members.getValue("a").supersededAtMs)
            assertEquals(revivedAt, members.getValue("c").seenFromMs)
            assertNull(members.getValue("b").supersededAtMs)
            repository.assertMemberIntervalsSound(ALICE)
            repository.assertCollectionIntervalsContiguous(ALICE)
        }

    @Test
    fun aRevivalWithTheSameMembersIsStillAVersionedChange() =
        runBlocking {
            tombstoned()
            clock.stepMs(10_000)

            val write = repository.writeCollection(ALICE, box, "x", movies("a", "b"))

            assertEquals(true, write.revived)
            assertEquals(clock.nowMs, write.stamp)
            assertEquals(1, repository.collectionVersions(ALICE).size)
            assertNull(current().deletedAtMs)
            repository.assertCollectionIntervalsContiguous(ALICE)
        }

    @Test
    fun aClockSetBackBeforeTheTombstoneStillRevivesAtALaterStamp() =
        runBlocking {
            tombstoned()
            clock.rewindMs(1_000_000)

            val write = repository.writeCollection(ALICE, box, "x", movies("a"))

            assertEquals(start + 1, write.stamp)
            repository.assertMemberIntervalsSound(ALICE)
            repository.assertCollectionIntervalsContiguous(ALICE)
        }

    @Test
    fun aSecondTombstoneAndRevivalAddsASecondVersion() =
        runBlocking {
            tombstoned()
            clock.stepMs(10_000)
            repository.writeCollection(ALICE, box, "x", movies("a", "b"))
            clock.stepMs(10_000)
            repository.tombstoneCollections(ALICE, listOf(box))
            clock.stepMs(10_000)

            repository.writeCollection(ALICE, box, "x", movies("a"))

            assertEquals(2, repository.collectionVersions(ALICE).size)
            repository.assertCollectionIntervalsContiguous(ALICE)
            repository.assertMemberIntervalsSound(ALICE)
        }
}
