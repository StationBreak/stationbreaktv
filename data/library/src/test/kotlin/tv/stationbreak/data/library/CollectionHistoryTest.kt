// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 2.3 (Vimes H1, H2): membership history as member rows with intervals, stamped once per collection write.
 * A diff by member ID gives one stamp `max(now, collection.seenFromMs + 1)` to every addition and removal of a write; an
 * unchanged read takes none; a collection is inserted only with its first complete member list. After EVERY write, at
 * most one current row exists per (collection, member), and the intervals stay sound.
 */
class CollectionHistoryTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs
    private val box = ItemId("box01")

    @After
    fun close() = repository.close()

    // Every write in this class goes through here, so the invariants are asserted after each one (Vimes H1).
    private suspend fun write(
        vararg members: CollectionMember,
        name: String = "Night films",
    ): CollectionWrite {
        val write = repository.writeCollection(ALICE, box, name, members.toList())
        repository.assertMemberIntervalsSound(ALICE)
        repository.assertCollectionIntervalsContiguous(ALICE)
        return write
    }

    private suspend fun current() = repository.collections(ALICE).single()

    private suspend fun rowsOf(member: String) = repository.collectionMembers(ALICE).filter { it.memberId == member }

    @Test
    fun aNewCollectionIsInsertedWithItsFirstMembersAtOneStamp() =
        runBlocking {
            val write = write(cm("a"), cm("b", CollectionMemberKind.Series))

            assertEquals(CollectionWrite(stamp = start, inserted = true, revived = false, added = 2, removed = 0), write)
            val row = current()
            assertEquals(start, row.seenFromMs)
            assertEquals(start, row.firstSeenMs)
            assertEquals("Night films", row.name)
            assertNull(row.deletedAtMs)
            val members = repository.collectionMembers(ALICE)
            assertEquals(listOf(start, start), members.map { it.seenFromMs })
            assertEquals(listOf(null, null), members.map { it.supersededAtMs })
            assertEquals(listOf("a:movie", "b:series"), repository.currentMembers(ALICE, box.value))
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun anEmptyCollectionIsInsertedWithNoMembersAndItsFirstMemberLaterIsAChange() =
        runBlocking {
            write()
            assertEquals(start, current().firstSeenMs)
            clock.stepMs(1_000)

            val write = write(cm("a"))

            assertEquals(start + 1_000, write.stamp)
            assertEquals(start + 1_000, rowsOf("a").single().seenFromMs)
            assertEquals(1, repository.collectionVersions(ALICE).size)
        }

    @Test
    fun anAddedMemberIsStampedAndTheCollectionKeepsItsOldLifecycleAsAVersion() =
        runBlocking {
            write(cm("a"))
            clock.stepMs(5_000)

            val write = write(cm("a"), cm("b"))

            assertEquals(CollectionWrite(start + 5_000, inserted = false, revived = false, added = 1, removed = 0), write)
            assertEquals(start, rowsOf("a").single().seenFromMs)
            assertEquals(start + 5_000, rowsOf("b").single().seenFromMs)
            assertEquals(start + 5_000, current().seenFromMs)
            assertEquals(start, current().firstSeenMs)
            val version = repository.collectionVersions(ALICE).single()
            assertEquals(CollectionVersionEntity("server-1", "alice", "box01", start, start + 5_000, null), version)
        }

    @Test
    fun aRemovedMemberEndsItsIntervalAtTheStamp() =
        runBlocking {
            write(cm("a"), cm("b"))
            clock.stepMs(5_000)

            val write = write(cm("a"))

            assertEquals(CollectionWrite(start + 5_000, inserted = false, revived = false, added = 0, removed = 1), write)
            assertEquals(start + 5_000, rowsOf("b").single().supersededAtMs)
            assertNull(rowsOf("a").single().supersededAtMs)
            assertEquals(listOf("a:movie"), repository.currentMembers(ALICE, box.value))
        }

    @Test
    fun anAdditionAndARemovalInOneWriteShareOneStampAndOneVersion() =
        runBlocking {
            write(cm("a"), cm("b"))
            clock.stepMs(5_000)

            val write = write(cm("b"), cm("c"))

            assertEquals(CollectionWrite(start + 5_000, inserted = false, revived = false, added = 1, removed = 1), write)
            assertEquals(start + 5_000, rowsOf("a").single().supersededAtMs)
            assertEquals(start + 5_000, rowsOf("c").single().seenFromMs)
            assertEquals(1, repository.collectionVersions(ALICE).size)
        }

    @Test
    fun anUnchangedReReadTakesNoStampAndWritesNothing() =
        runBlocking {
            write(cm("a"), cm("b"))
            val before = repository.collectionMembers(ALICE)
            clock.stepMs(5_000)

            val write = write(cm("b"), cm("a"))

            assertEquals(CollectionWrite(stamp = null, inserted = false, revived = false, added = 0, removed = 0), write)
            assertEquals(before, repository.collectionMembers(ALICE))
            assertEquals(start, current().seenFromMs)
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun aRenameAloneIsStoredAndTakesNoStamp() =
        runBlocking {
            write(cm("a"))
            clock.stepMs(5_000)

            val write = write(cm("a"), name = "Better name")

            assertNull(write.stamp)
            assertEquals("Better name", current().name)
            assertEquals(start, current().seenFromMs)
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun aKindChangeIsARemovalAndAnAdditionAtOneStamp() =
        runBlocking {
            write(cm("a", CollectionMemberKind.Movie))
            clock.stepMs(5_000)

            val write = write(cm("a", CollectionMemberKind.Episode))

            assertEquals(CollectionWrite(start + 5_000, inserted = false, revived = false, added = 1, removed = 1), write)
            val rows = rowsOf("a").sortedBy { it.seenFromMs }
            assertEquals(listOf("movie", "episode"), rows.map { it.memberKind })
            assertEquals(start + 5_000, rows[0].supersededAtMs)
            assertEquals(start + 5_000, rows[1].seenFromMs)
            assertNull(rows[1].supersededAtMs)
        }

    @Test
    fun aMemberListedTwiceInOneReadIsStoredOnce() =
        runBlocking {
            val write = write(cm("a"), cm("a"), cm("b"))

            assertEquals(2, write.added)
            assertEquals(2, repository.collectionMembers(ALICE).size)
            clock.stepMs(1_000)
            assertNull(write(cm("a"), cm("b"), cm("b"), cm("a")).stamp)
        }

    @Test
    fun aMemberThatLeavesAndReturnsHasTwoRowsAndNeverTwoCurrentOnes() =
        runBlocking {
            write(cm("a"))
            clock.stepMs(1_000)
            write()
            clock.stepMs(1_000)

            write(cm("a"))

            val rows = rowsOf("a").sortedBy { it.seenFromMs }
            assertEquals(listOf(start, start + 2_000), rows.map { it.seenFromMs })
            assertEquals(listOf(start + 1_000, null), rows.map { it.supersededAtMs })
        }

    @Test
    fun twoChangesInTheSameMillisecondGetIncreasingStamps() =
        runBlocking {
            write(cm("a"))

            val second = write(cm("a"), cm("b"))
            val third = write(cm("a"), cm("b"), cm("c"))

            assertEquals(start + 1, second.stamp)
            assertEquals(start + 2, third.stamp)
            assertEquals(listOf(start, start + 1), repository.collectionVersions(ALICE).map { it.seenFromMs })
        }

    @Test
    fun aClockStepBackwardsStillGivesAStrictlyLaterStamp() =
        runBlocking {
            write(cm("a"))
            clock.stepMs(10_000)
            write(cm("a"), cm("b"))

            clock.rewindMs(60_000)
            val write = write(cm("a"))

            assertEquals(start + 10_001, write.stamp)
            assertEquals(start + 10_001, rowsOf("b").single().supersededAtMs)
        }

    @Test
    fun aReplayedReadChangesNothing() =
        runBlocking {
            write(cm("a"), cm("b"))
            clock.stepMs(5_000)
            write(cm("b"), cm("c"))
            val members = repository.collectionMembers(ALICE)
            val versions = repository.collectionVersions(ALICE)

            clock.stepMs(5_000)
            val replay = write(cm("b"), cm("c"))

            assertNull(replay.stamp)
            assertEquals(members, repository.collectionMembers(ALICE))
            assertEquals(versions, repository.collectionVersions(ALICE))
        }

    @Test
    fun aCollectionIsNeverStoredWithoutItsFirstMembers() =
        runBlocking {
            repository.faultBetweenCollectionAndMembers = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) { runBlocking { write(cm("a"), cm("b")) } }

            assertEquals(emptyList<CollectionEntity>(), repository.collections(ALICE))
            assertEquals(emptyList<CollectionMemberEntity>(), repository.collectionMembers(ALICE))
        }

    @Test
    fun aFaultDuringAChangeKeepsTheCollectionAsItWas() =
        runBlocking {
            write(cm("a"))
            clock.stepMs(1_000)
            val collection = current()
            val members = repository.collectionMembers(ALICE)
            repository.faultBetweenCollectionAndMembers = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) { runBlocking { write(cm("b")) } }

            assertEquals(collection, current())
            assertEquals(members, repository.collectionMembers(ALICE))
            assertEquals(emptyList<CollectionVersionEntity>(), repository.collectionVersions(ALICE))
        }

    @Test
    fun aBigRemovalIsChunkedUnderTheVariableLimit() =
        runBlocking {
            val ids = (0 until 1_300).map { hexId(it) }
            write(*ids.map { cm(it) }.toTypedArray())
            clock.stepMs(1_000)

            val write = write(cm(ids.first()))

            assertEquals(1_299, write.removed)
            assertEquals(listOf("${ids.first()}:movie"), repository.currentMembers(ALICE, box.value))
        }

    @Test
    fun aSignedOutAccountCannotWrite() =
        runBlocking {
            assertThrows(AccountNotActiveException::class.java) {
                runBlocking { repository.writeCollection(BOB, box, "x", movies("a")) }
            }
            Unit
        }

    @Test
    fun theCollectionsOfTwoAccountsDoNotMix() =
        runBlocking {
            val both = TestLibraries.inMemory(clock, signedIn = listOf(ALICE, BOB))
            both.writeCollection(ALICE, box, "A", movies("a"))
            both.writeCollection(BOB, box, "B", movies("b"))

            assertEquals(listOf("a:movie"), both.currentMembers(ALICE, box.value))
            assertEquals(listOf("b:movie"), both.currentMembers(BOB, box.value))
            both.close()
        }
}
