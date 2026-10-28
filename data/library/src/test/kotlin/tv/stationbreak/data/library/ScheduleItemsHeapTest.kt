// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Spec 09 task 2.13 (Goal 4): on the generated 15,000-item library with three superseded versions per item (45,000
 * `item_version` rows), the as-of read finishes in under one second on the JVM, and its heap growth stays under the
 * spec 06 budget of 64 MB. The same read runs on the API 25 emulator (`ScheduleItemsHeapOnDeviceTest`). Every item has
 * one studio, so every version row carries one too (spec 18).
 */
class ScheduleItemsHeapTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val library = SyntheticLibrary(size = ITEMS, libraryId = MOVIES)

    @After
    fun close() = repository.close()

    private fun usedHeap(): Long {
        repeat(GCS) {
            System.gc()
            System.runFinalization()
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Writes the whole library [round] times; each round changes the rating, so every round supersedes the last. */
    private suspend fun fill() {
        repository.select(ALICE, setOf(MOVIES))
        repeat(ROUNDS) { round ->
            clock.stepMs(1.hours.inWholeMilliseconds)
            var start = 0
            while (start < ITEMS) {
                val page =
                    library.page(start, PAGE).map {
                        it.copy(officialRating = "R$round", studios = studioOf(it.id.value))
                    }
                repository.upsertItems(ALICE, MOVIES, page)
                start += PAGE
            }
        }
    }

    @Test
    fun fifteenThousandItemsWithThreeVersionsEachReadInUnderASecondWithinTheHeapBudget() =
        runBlocking {
            fill()
            assertEquals(ITEMS * (ROUNDS - 1), repository.rowCounts().versions)
            // A cut-off between the second and third round: most items are answered by case (b), a version.
            val cutoff = Instant.fromEpochMilliseconds(EPOCH.toEpochMilliseconds() + 2 * 3_600_000L + 1)
            val schedulable = setOf(ItemKind.Movie, ItemKind.Episode)
            val usable = library.items.count { it.kind in schedulable && (it.runtime?.inWholeMilliseconds ?: 0) > 0 }
            val baseline = usedHeap()

            val times = mutableListOf<Long>()
            var kept: ScheduleRead? = null
            repeat(READS) {
                val started = System.nanoTime()
                kept = repository.scheduleInputs(ALICE, setOf(MOVIES), cutoff)
                times += (System.nanoTime() - started) / NANOS_PER_MS
            }
            val read = kept!!
            val retained = usedHeap() - baseline

            val median = times.sorted()[times.size / 2]
            println(
                "SCHEDULE READ on the JVM: reads $times ms, median $median ms; ${read.items.size} items of " +
                    "${repository.rowCounts().items}; kept result grew the heap by ${retained / KB} KB",
            )
            assertEquals(usable, read.items.size)
            assertTrue("the read took $median ms (all: $times), the limit is 1000", median < LIMIT_MS)
            assertTrue("the result grew the heap by ${retained / MB} MB, limit $LIMIT_MB", retained / MB < LIMIT_MB)
        }

    private companion object {
        const val ITEMS = 15_000
        const val ROUNDS = 4
        const val PAGE = 400
        const val READS = 3
        const val GCS = 2
        const val KB = 1024L
        const val MB = KB * KB
        const val LIMIT_MB = 64L
        const val LIMIT_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
        val STUDIOS = listOf("Studio North", "Studio South", "Studio East", "Studio West", "Studio Centre")

        // One invented studio per item or series (spec 18), the same for an ID every round.
        fun studioOf(id: String) = listOf(STUDIOS[Math.floorMod(id.hashCode(), STUDIOS.size)])
    }
}
