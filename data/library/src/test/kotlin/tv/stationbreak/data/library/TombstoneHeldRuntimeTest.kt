// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 09 task 2.9 (Vimes H2, H4): a deleted item reaches the scheduler with its frozen runtime and `deletedAt` until
 * `deletedAt < cutoff`, and not after. A runtime edit just before the deletion follows the cut-off rule, and an item
 * deleted before the cut-off and revived after it is not in day n and is in day n + 1.
 */
class TombstoneHeldRuntimeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val t0 = clock.nowMs
    private val hour = 1.hours.inWholeMilliseconds
    private val a = ItemId(hexId(1))

    @After
    fun close() = repository.close()

    private suspend fun read(cutoffMs: Long): List<ScheduleItem> =
        repository.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoffMs))

    private suspend fun only(cutoffMs: Long): ScheduleItem? = read(cutoffMs).singleOrNull()

    @Test
    fun aDeletedItemKeepsItsRuntimeAndDeletedAtUntilTheCutoffPassesTheDeletion() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(2 * hour)
            repository.tombstone(ALICE, listOf(a))
            val deletedAt = clock.nowMs

            val before = only(deletedAt - 1)
            val exactly = only(deletedAt)
            val after = only(deletedAt + 1)

            assertEquals(90.minutes.inWholeMilliseconds, before?.runtimeMs)
            assertEquals(deletedAt, before?.deletedAtMs)
            assertEquals(deletedAt, exactly?.deletedAtMs)
            assertNull("deletedAt < cutoff: gone", after)
        }

    @Test
    fun aRuntimeEditJustBeforeTheDeletionFollowsTheCutoffRule() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(hour)
            val editedAt = clock.nowMs
            repository.put(ALICE, MOVIES, sitem(1, runtime = 100.minutes))
            clock.stepMs(hour)
            repository.tombstone(ALICE, listOf(a))
            val deletedAt = clock.nowMs

            // Cut-off before the edit: the old runtime, not yet deleted as far as that version knew.
            val early = only(editedAt - 1)
            assertEquals(90.minutes.inWholeMilliseconds, early?.runtimeMs)
            assertNull(early?.deletedAtMs)
            // Cut-off after the edit and before the deletion: the edited runtime, with the deletion that came later.
            val late = only(editedAt + 1)
            assertEquals(100.minutes.inWholeMilliseconds, late?.runtimeMs)
            assertEquals(deletedAt, late?.deletedAtMs)
        }

    @Test
    fun anItemDeletedBeforeTheCutoffAndRevivedAfterItIsNotInDayNAndIsInDayNPlusOne() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(hour)
            repository.tombstone(ALICE, listOf(a))
            val deletedAt = clock.nowMs
            clock.stepMs(2 * hour)
            repository.revive(ALICE, listOf(a))
            val revivedAt = clock.nowMs
            val dayN = revivedAt - 10 * 60_000
            val dayN1 = revivedAt + 10 * 60_000

            assertNull("deleted before day n's cut-off, back only after it", only(dayN))
            assertEquals(90.minutes.inWholeMilliseconds, only(dayN1)?.runtimeMs)
            assertNull(only(dayN1)?.deletedAtMs)
            // At the very cut-off of the revival, the version held before it is the deleted one.
            assertNull(only(revivedAt))
            // Before the deletion it is simply present, and its deletion is still ahead.
            assertEquals(deletedAt, only(deletedAt - 1)?.deletedAtMs)
        }

    @Test
    fun anItemRevivedByAPageThatAlsoEditsItIsHeldAsDeletedUntilTheNextCutoff() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(hour)
            repository.tombstone(ALICE, listOf(a))
            clock.stepMs(hour)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 120.minutes))
            val revivedAt = clock.nowMs

            assertNull(only(revivedAt - 1))
            assertEquals(120.minutes.inWholeMilliseconds, only(revivedAt + 1)?.runtimeMs)
        }

    @Test
    fun aDeletionThatLandsAfterTheCutoffDoesNotChangeTheDayBuiltFromIt() =
        runBlocking {
            // Day 0 of this channel starts at S = 6 h with a 6 h grace, so its cut-off S - G is the epoch (0 ms).
            val settings = SchedulerSettings(OrderingMode.Shuffle, Seed(7uL), 1, true, 30, 24, 0, 360, 6, 60_000L)
            val cutoff = 0L
            repository.select(ALICE, setOf(MOVIES))
            clock.atMs(cutoff - hour)
            val old = Instant.fromEpochMilliseconds(-1)
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes).copy(dateCreated = old))
            clock.atMs(cutoff)
            repository.tombstone(ALICE, listOf(a))

            val held = read(cutoff)

            assertEquals("deletedAt = the cut-off is still held", cutoff, held.single().deletedAtMs)
            val withDeletion = buildDay(settings, held, 0).blocks
            val withoutDeletion = buildDay(settings, held.map { it.copy(deletedAtMs = null) }, 0).blocks
            assertEquals(withoutDeletion, withDeletion)
            assertTrue("the item airs", withDeletion.any { it.content is ScheduleContent.Program })
        }

    @Test
    fun aTombstoneThatIsNeverRevivedStaysHeldForEveryCutoffUpToItsDeletion() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes))
            clock.stepMs(hour)
            repository.tombstone(ALICE, listOf(a))
            val deletedAt = clock.nowMs

            listOf(t0 - hour, t0, t0 + 1, deletedAt - 1, deletedAt).forEach {
                assertEquals("cut-off $it", 90.minutes.inWholeMilliseconds, only(it)?.runtimeMs)
            }
            assertEquals(emptyList<ScheduleItem>(), read(deletedAt + 1))
        }
}
