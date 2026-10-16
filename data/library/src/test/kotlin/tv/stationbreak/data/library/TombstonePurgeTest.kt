// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** Spec 06 task 2.9 (T11): tombstones older than 30 days are purged, younger ones stay. Fake clock. */
class TombstonePurgeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private suspend fun saveAndTombstone(vararg ids: String) {
        repository.select(ALICE, setOf(MOVIES))
        val items = listOf("live", "gone", "gone-too", "recent").map { item(it).copy(genres = listOf("g-$it")) }
        repository.writePage(ALICE, MOVIES, items, nextStartIndex = items.size, totalCount = items.size, run = 1)
        repository.tombstone(ALICE, ids.map(::ItemId))
    }

    private suspend fun rowIds() = repository.stored(ALICE).map { it.item.id.value }.sorted()

    @Test
    fun aTombstoneRecordsWhenTheItemWasMarkedDeleted() =
        runBlocking {
            saveAndTombstone("gone")

            val gone = repository.stored(ALICE).first { it.item.id.value == "gone" }

            assertEquals(clock.instant, gone.deletedAt)
        }

    @Test
    fun tombstonesOlderThanThirtyDaysArePurgedAndYoungerOnesStay() =
        runBlocking {
            saveAndTombstone("gone", "gone-too")
            clock.instant += 20.days
            repository.tombstone(ALICE, listOf(ItemId("recent")))
            clock.instant += 10.days + 1.milliseconds

            val purged = repository.purgeTombstones()

            // "gone" and "gone-too" are 30 days and 1 ms old; "recent" is 10 days and 1 ms old; "live" is not deleted.
            assertEquals(2, purged)
            assertEquals(listOf("live", "recent"), rowIds())
        }

    @Test
    fun aTombstoneExactlyThirtyDaysOldStays() =
        runBlocking {
            saveAndTombstone("gone")
            clock.instant += 30.days

            assertEquals(0, repository.purgeTombstones())
            assertEquals(true, "gone" in rowIds())
        }

    @Test
    fun aTombstoneJustUnderThirtyDaysStays() =
        runBlocking {
            saveAndTombstone("gone")
            clock.instant += 29.days + 23.hours

            assertEquals(0, repository.purgeTombstones())
        }

    @Test
    fun purgingTakesTheItemsGenresAndTagsWithIt() =
        runBlocking {
            saveAndTombstone("gone", "gone-too", "recent")
            clock.instant += 31.days

            repository.purgeTombstones()

            assertEquals(listOf("live"), rowIds())
            assertEquals(1, repository.rowCounts().genres)
        }

    @Test
    fun purgingNeverTouchesLiveItems() =
        runBlocking {
            saveAndTombstone()
            clock.instant += 400.days

            assertEquals(0, repository.purgeTombstones())
            assertEquals(4, rowIds().size)
        }

    @Test
    fun purgingCoversEveryAccount() =
        runBlocking {
            saveAndTombstone("gone")
            repository.select(BOB, setOf(MOVIES))
            repository.writePage(BOB, MOVIES, listOf(item("bobs")), 1, 1, run = 1)
            repository.tombstone(BOB, listOf(ItemId("bobs")))
            clock.instant += 31.days

            assertEquals(2, repository.purgeTombstones())
        }
}
