// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import kotlin.time.Duration.Companion.hours

/**
 * Spec 10 task 1.6.3: series rows are not items. `scheduleItems`, `snapshot` and the drop counts are the same with and
 * without them, whether the series are live, edited or tombstoned. A regression guard: it arrives green, because no
 * read of the items looks at the series table, and it keeps it so.
 */
class SeriesRowsIgnoredByScheduleItemsTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private suspend fun readEverything() =
        Triple(
            repository.scheduleInputs(ALICE, setOf(SHOWS), EPOCH + 1.hours),
            repository.snapshot(ALICE, setOf(SHOWS), EPOCH + 1.hours),
            repository.excludedCount(ALICE, setOf(SHOWS)),
        )

    @Test
    fun scheduleItemsSnapshotAndDropCountsAreTheSameWithSeriesRows() =
        runBlocking {
            repository.select(ALICE, setOf(SHOWS))
            val episodes = (1..5).map { sitem(it, library = SHOWS).copy(kind = ItemKind.Episode) }
            repository.upsertItems(ALICE, SHOWS, episodes + sitem(6, runtime = null, library = SHOWS))
            val without = readEverything()

            val many = (1..8).map { series(hexId(100 + it), "TV-14", listOf("x"), listOf("y")) }
            repository.upsertSeries(ALICE, SHOWS, many)
            clock.stepMs(10_000)
            repository.upsertSeries(ALICE, SHOWS, listOf(series(hexId(101), "TV-MA")))
            repository.tombstoneSeries(ALICE, listOf(ItemId(hexId(102))))

            assertEquals(without.first.items, readEverything().first.items)
            assertEquals(without.first.dropped, readEverything().first.dropped)
            assertEquals(without.second, readEverything().second)
            assertEquals(without.third, readEverything().third)
        }
}
