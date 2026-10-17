// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 09 task 2.3 (Vimes H1): each item's versions form strictly increasing, contiguous, non-overlapping intervals
 * whatever the clock does. Two edits in one millisecond, a clock that steps back and a page written twice never fail on
 * the key `(server, user, item, seenFromMs)`.
 */
class VersionMonotonicTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs

    @After
    fun close() = repository.close()

    private suspend fun open() = repository.select(ALICE, setOf(MOVIES))

    private suspend fun current() = repository.currentRows(ALICE).single()

    @Test
    fun aNewItemIsFirstSeenNowAndHasNoVersion() =
        runBlocking {
            open()
            repository.put(ALICE, MOVIES, item("a"))

            val row = current()

            assertEquals(start, row.seenFromMs)
            assertEquals(start, row.firstSeenMs)
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
        }

    @Test
    fun anEditLaterKeepsTheOldRowAsAVersionEndingWhereTheNewOneBegins() =
        runBlocking {
            open()
            repository.put(ALICE, MOVIES, item("a", runtime = 90.minutes))
            clock.stepMs(5_000)

            repository.put(ALICE, MOVIES, item("a", runtime = 100.minutes))

            val version = repository.versions(ALICE).single()
            assertEquals(start, version.seenFromMs)
            assertEquals(start + 5_000, version.supersededAtMs)
            assertEquals(90.minutes.inWholeMilliseconds, version.runtimeMs)
            assertEquals(start + 5_000, current().seenFromMs)
            assertEquals(start, current().firstSeenMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun twoEditsInTheSameMillisecondGetDistinctIncreasingStamps() =
        runBlocking {
            open()
            repository.put(ALICE, MOVIES, item("a", runtime = 90.minutes))

            repository.put(ALICE, MOVIES, item("a", runtime = 91.minutes))
            repository.put(ALICE, MOVIES, item("a", runtime = 92.minutes))
            repository.put(ALICE, MOVIES, item("a", runtime = 93.minutes))

            assertEquals(listOf(start, start + 1, start + 2), repository.versions(ALICE).map { it.seenFromMs })
            assertEquals(start + 3, current().seenFromMs)
            assertEquals(93.minutes.inWholeMilliseconds, current().runtimeMs)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun aClockThatStepsBackStillGivesIncreasingStamps() =
        runBlocking {
            open()
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, item("a", runtime = 90.minutes))
            val first = clock.nowMs
            clock.rewindMs(60_000)

            repository.put(ALICE, MOVIES, item("a", runtime = 91.minutes))
            repository.put(ALICE, MOVIES, item("a", runtime = 92.minutes))

            assertEquals(first + 2, current().seenFromMs)
            assertEquals(listOf(first, first + 1), repository.versions(ALICE).map { it.seenFromMs })
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun aPageWrittenTwiceChangesNothingTheSecondTime() =
        runBlocking {
            open()
            val page = arrayOf(item("a"), item("b"))
            repository.put(ALICE, MOVIES, *page)
            clock.stepMs(1_000)
            val before = repository.currentRows(ALICE)

            val again = repository.put(ALICE, MOVIES, *page)

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 2), again)
            assertEquals(before, repository.currentRows(ALICE))
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
        }

    @Test
    fun anEditedPageReplayedAfterTheEditAddsNoSecondVersion() =
        runBlocking {
            open()
            repository.put(ALICE, MOVIES, item("a", runtime = 90.minutes))
            clock.stepMs(1_000)
            val edited = item("a", runtime = 100.minutes)
            repository.put(ALICE, MOVIES, edited)
            clock.stepMs(1_000)

            repository.put(ALICE, MOVIES, edited)

            assertEquals(1, repository.versions(ALICE).size)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun manyItemsInOnePageEachKeepTheirOwnContiguousIntervals() =
        runBlocking {
            open()
            val ids = (1..50).map { "item-$it" }
            repository.put(ALICE, MOVIES, *ids.map { item(it, runtime = 90.minutes) }.toTypedArray())
            repeat(4) { round ->
                if (round == 2) clock.rewindMs(5_000)
                repository.put(ALICE, MOVIES, *ids.map { item(it, runtime = (91 + round).minutes) }.toTypedArray())
            }

            assertEquals(50 * 4, repository.versions(ALICE).size)
            repository.assertIntervalsContiguous(ALICE)
            assertEquals(ids.map(::ItemId).size, repository.currentRows(ALICE).size)
        }
}
