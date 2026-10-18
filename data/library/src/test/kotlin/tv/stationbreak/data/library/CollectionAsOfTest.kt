// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Spec 10 task 2.3 (Vimes H2): the members of a collection as this TV held them before a cut-off. The lifecycle
 * version is chosen as for an item (a: the current row, b: the version current just before, c: the oldest, with the
 * cut-off `firstSeenMs + 1`); a member is held when `seenFromMs < c'` and `supersededAtMs` is null or at least `c'`;
 * the collection is dropped when its chosen lifecycle was deleted before the cut-off.
 */
class CollectionAsOfTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs
    private val box = ItemId("box01")
    private val other = ItemId("box02")

    @After
    fun close() = repository.close()

    // The collections held before [cutoffMs], as collection to its sorted `member:kind` list.
    private suspend fun heldBefore(cutoffMs: Long): Map<String, List<String>> =
        repository
            .heldCollections(ALICE, Instant.fromEpochMilliseconds(cutoffMs))
            .associate { held -> held.collectionId to held.members.map { "${it.memberId}:${it.kind}" } }

    private suspend fun write(
        id: ItemId,
        vararg ids: String,
    ) = repository.writeCollection(ALICE, id, "Name of ${id.value}", movies(*ids))

    @Test
    fun aMemberAddedAfterTheCutoffIsAbsentThatDayAndPresentTheNext() =
        runBlocking {
            write(box, "a")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            write(box, "a", "b")

            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(cutoff))
            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(cutoff + 1))
        }

    @Test
    fun aMemberRemovedAfterTheCutoffIsPresentThatDayAndGoneTheNext() =
        runBlocking {
            write(box, "a", "b")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            write(box, "a")

            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(cutoff))
            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(cutoff + 1))
        }

    @Test
    fun aKindChangeAfterTheCutoffKeepsTheOldKindThatDay() =
        runBlocking {
            repository.writeCollection(ALICE, box, "x", listOf(cm("a", CollectionMemberKind.Movie)))
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            repository.writeCollection(ALICE, box, "x", listOf(cm("a", CollectionMemberKind.Episode)))

            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(cutoff))
            assertEquals(mapOf("box01" to listOf("a:episode")), heldBefore(cutoff + 1))
        }

    @Test
    fun aCollectionFirstSeenAfterTheCutoffHoldsItsOldestSetAndNeverAnEmptyOne() =
        runBlocking {
            val cutoff = clock.nowMs - 60_000
            write(box, "a", "b")
            clock.stepMs(10_000)
            write(box, "b", "c")
            clock.stepMs(10_000)
            write(box, "c", "d")

            // Case (c): the cut-off is before the collection was first seen, so it holds the first complete set.
            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(cutoff))
            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(start))
            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(start + 1).also { assertTrue(it.isNotEmpty()) })
        }

    @Test
    fun aNewCollectionWithOneVersionOnlyStillHoldsItsFirstSet() =
        runBlocking {
            write(box, "a")

            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(start - 1))
        }

    @Test
    fun theSameCutoffReadsEachCollectionByItsOwnHistory() =
        runBlocking {
            write(box, "a")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            write(other, "x")
            clock.stepMs(10_000)
            write(box, "a", "b")
            write(other, "x", "y")

            val held = heldBefore(cutoff + 5_000)

            assertEquals(listOf("a:movie"), held["box01"])
            assertEquals(listOf("x:movie"), held["box02"])
            assertEquals(listOf("box01", "box02"), held.keys.toList())
        }

    @Test
    fun aCollectionDeletedBeforeTheCutoffIsAbsent() =
        runBlocking {
            write(box, "a")
            clock.stepMs(10_000)
            repository.tombstoneCollections(ALICE, listOf(box))
            val deletedAt = clock.nowMs

            assertEquals(emptyMap<String, List<String>>(), heldBefore(deletedAt + 1))
            // Deleted at the cut-off itself is not "before" it: the day built from before still sees it.
            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(deletedAt))
        }

    @Test
    fun aCollectionDeletedBeforeAndRevivedAfterTheCutoffIsAbsentThatDay() =
        runBlocking {
            write(box, "a")
            clock.stepMs(10_000)
            repository.tombstoneCollections(ALICE, listOf(box))
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            write(box, "a", "b")

            assertEquals(emptyMap<String, List<String>>(), heldBefore(cutoff))
            assertEquals(mapOf("box01" to listOf("a:movie", "b:movie")), heldBefore(clock.nowMs + 1))
        }

    @Test
    fun aCollectionDeletedAfterTheCutoffIsStillPresentThatDay() =
        runBlocking {
            write(box, "a")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            repository.tombstoneCollections(ALICE, listOf(box))

            assertEquals(mapOf("box01" to listOf("a:movie")), heldBefore(cutoff))
        }

    @Test
    fun aMembersWholeHistoryIsReadFromItsRowsForAnyCutoff() =
        runBlocking {
            // a is a member over [0, 20), b over [10, 30), and a is back from 40.
            write(box, "a")
            clock.stepMs(10_000)
            write(box, "a", "b")
            clock.stepMs(10_000)
            write(box, "b")
            clock.stepMs(10_000)
            write(box)
            clock.stepMs(10_000)
            write(box, "a")

            val at = { offset: Long -> runBlocking { heldBefore(start + offset)["box01"] } }

            assertEquals(listOf("a:movie"), at(5_000))
            assertEquals(listOf("a:movie", "b:movie"), at(15_000))
            assertEquals(listOf("b:movie"), at(25_000))
            assertEquals(emptyList<String>(), at(35_000))
            assertEquals(listOf("a:movie"), at(45_000))
        }

    @Test
    fun manyCollectionsAreReadInChunks() =
        runBlocking {
            val ids = (0 until 120).map { ItemId(hexId(it)) }
            ids.forEach { write(it, "m${it.value}") }

            val held = repository.heldCollections(ALICE, Instant.fromEpochMilliseconds(start + 1))

            assertEquals(ids.map { it.value }, held.map { it.collectionId })
            assertTrue(held.all { it.members.size == 1 })
        }
}
