// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 09 task 2.6 (Vimes H2): a revival is a versioned change on both paths, `reviveAll` and a `writePage` row that
 * clears the tombstone. The tombstoned row is copied as a version ending at the new stamp. Setting a tombstone adds no
 * version, because its time is its own seen-time.
 */
class RevivalVersionTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs
    private val a = item("a").copy(genres = listOf("Drama"))

    @After
    fun close() = repository.close()

    private suspend fun startWithTombstone() {
        repository.select(ALICE, setOf(MOVIES))
        repository.put(ALICE, MOVIES, a)
        clock.stepMs(10_000)
        repository.tombstone(ALICE, listOf(ItemId("a")))
        clock.stepMs(10_000)
    }

    @Test
    fun settingATombstoneAddsNoVersionAndKeepsTheStamp() =
        runBlocking {
            startWithTombstone()

            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            val row = repository.currentRows(ALICE).single()
            assertEquals(start + 10_000, row.deletedAtMs)
            assertEquals(start, row.seenFromMs)
        }

    @Test
    fun reviveAllCopiesTheTombstonedRowAsAVersion() =
        runBlocking {
            startWithTombstone()

            repository.revive(ALICE, listOf(ItemId("a")))

            val version = repository.versions(ALICE).single()
            assertEquals(start, version.seenFromMs)
            assertEquals(start + 20_000, version.supersededAtMs)
            assertEquals(start + 10_000, version.deletedAtMs)
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            val row = repository.currentRows(ALICE).single()
            assertNull(row.deletedAtMs)
            assertEquals(start + 20_000, row.seenFromMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun aPageRowThatClearsTheTombstoneCopiesItAsAVersionEvenIfNothingElseChanged() =
        runBlocking {
            startWithTombstone()

            val write = repository.put(ALICE, MOVIES, a)

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.versions(ALICE).single()
            assertEquals(start + 10_000, version.deletedAtMs)
            assertEquals(start + 20_000, version.supersededAtMs)
            assertNull(repository.currentRows(ALICE).single().deletedAtMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun aRevivalInTheSameMillisecondAsTheStampStillGetsALaterStamp() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, a)
            repository.tombstone(ALICE, listOf(ItemId("a")))

            repository.revive(ALICE, listOf(ItemId("a")))

            assertEquals(start + 1, repository.currentRows(ALICE).single().seenFromMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun reviveingALiveItemOrAnUnknownOneChangesNothing() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, a)
            clock.stepMs(1_000)

            repository.revive(ALICE, listOf(ItemId("a"), ItemId("nobody")))

            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(start, repository.currentRows(ALICE).single().seenFromMs)
        }

    @Test
    fun aRevivalWithAnEditKeepsOneVersionHoldingTheTombstonedRow() =
        runBlocking {
            startWithTombstone()

            repository.put(ALICE, MOVIES, a.copy(genres = listOf("Horror")))

            val version = repository.versions(ALICE).single()
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertEquals(start + 10_000, version.deletedAtMs)
            assertEquals(listOf("Horror"), repository.stored(ALICE).single().item.genres)
        }

    @Test
    fun aTombstoneAndRevivalCycleKeepsEveryRoundContiguous() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, a)
            repeat(5) {
                clock.stepMs(1_000)
                repository.tombstone(ALICE, listOf(ItemId("a")))
                clock.stepMs(1_000)
                repository.revive(ALICE, listOf(ItemId("a")))
            }

            assertEquals(5, repository.versions(ALICE).size)
            repository.assertIntervalsContiguous(ALICE)
        }
}
