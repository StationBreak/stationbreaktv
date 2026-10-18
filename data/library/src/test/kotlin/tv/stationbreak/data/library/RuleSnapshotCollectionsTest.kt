// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleCollection

/**
 * Spec 10 task 3.3: the collections of a `RuleSnapshot` are those not deleted before the cut-off, with the members
 * held before it, grouped by kind, whatever the selected libraries are. `collectionsSynced` is false until a full
 * collection pass has succeeded.
 */
class RuleSnapshotCollectionsTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs

    @After
    fun close() = repository.close()

    private suspend fun write(
        n: Int,
        vararg members: Pair<Int, CollectionMemberKind>,
    ): CollectionWrite {
        val list = members.map { cm(hexId(it.first), it.second) }
        return repository.writeCollection(ALICE, ItemId(hexId(n)), "Name of $n", list)
    }

    private suspend fun snapshotAt(
        cutoffMs: Long,
        libraries: Set<ItemId> = setOf(RULE_LIBRARY),
    ) = repository.ruleSnapshot(ALICE, libraries, at(cutoffMs))

    @Test
    fun membersAreGroupedByKindIntoTheFourSets() =
        runBlocking {
            write(
                1000,
                1 to CollectionMemberKind.Movie,
                2 to CollectionMemberKind.Movie,
                3 to CollectionMemberKind.Episode,
                4 to CollectionMemberKind.Series,
                5 to CollectionMemberKind.Season,
            )

            val collection = snapshotAt(start + 1).collections

            val expected =
                RuleCollection(
                    canon(1000),
                    setOf(canon(1), canon(2)),
                    setOf(canon(3)),
                    setOf(canon(4)),
                    setOf(canon(5)),
                )
            assertEquals(mapOf(canon(1000) to expected), collection)
        }

    @Test
    fun aMemberOutsideTheSelectedLibrariesIsStillAMember() =
        runBlocking {
            repository.select(ALICE, setOf(RULE_LIBRARY))
            repository.put(ALICE, RULE_LIBRARY, episodeOf(1, null))
            write(1000, 1 to CollectionMemberKind.Episode, 77 to CollectionMemberKind.Movie)

            val snapshot = snapshotAt(start + 1)

            // Item 77 is in no library the TV holds, and item 1 is: both are members, because members are IDs.
            assertEquals(setOf(canon(1)), snapshot.collections.getValue(canon(1000)).episodes)
            assertEquals(setOf(canon(77)), snapshot.collections.getValue(canon(1000)).movies)
            assertEquals(listOf(canon(1)), snapshot.items.map { it.item.id })
        }

    @Test
    fun collectionsAreReturnedWhateverTheLibrariesAreEvenNone() =
        runBlocking {
            write(1000, 1 to CollectionMemberKind.Movie)

            assertEquals(setOf(canon(1000)), snapshotAt(start + 1, emptySet()).collections.keys)
        }

    @Test
    fun membersAreAsHeldBeforeTheCutoff() =
        runBlocking {
            write(1000, 1 to CollectionMemberKind.Movie)
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            write(1000, 1 to CollectionMemberKind.Movie, 2 to CollectionMemberKind.Movie)

            assertEquals(setOf(canon(1)), snapshotAt(cutoff).collections.getValue(canon(1000)).movies)
            val later = snapshotAt(clock.nowMs + 1).collections.getValue(canon(1000))
            assertEquals(setOf(canon(1), canon(2)), later.movies)
        }

    @Test
    fun aCollectionDeletedBeforeTheCutoffIsAbsentAndOneDeletedAtOrAfterItIsPresent() =
        runBlocking {
            write(1000, 1 to CollectionMemberKind.Movie)
            write(1001, 2 to CollectionMemberKind.Movie)
            clock.stepMs(10_000)
            val deletedAt = clock.nowMs
            repository.tombstoneCollections(ALICE, listOf(ItemId(hexId(1000))))

            assertEquals(setOf(canon(1000), canon(1001)), snapshotAt(deletedAt).collections.keys)
            assertEquals(setOf(canon(1001)), snapshotAt(deletedAt + 1).collections.keys)
        }

    @Test
    fun theCollectionsAreInCanonicalIdOrder() =
        runBlocking {
            listOf(1003, 1001, 1002).forEach { write(it) }

            assertEquals(listOf(canon(1001), canon(1002), canon(1003)), snapshotAt(start + 1).collections.keys.toList())
        }

    @Test
    fun collectionsSyncedIsFalseUntilTheFirstFullPassHasSucceeded() =
        runBlocking {
            assertFalse(snapshotAt(start + 1).collectionsSynced)

            // A changed-since pass is not a full one.
            repository.recordCollectionPass(ALICE, at(start), relisted = false)
            assertFalse(snapshotAt(start + 1).collectionsSynced)

            repository.recordCollectionPass(ALICE, at(start), relisted = true)
            assertTrue(snapshotAt(start + 1).collectionsSynced)
        }
}
