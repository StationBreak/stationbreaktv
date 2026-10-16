// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Spec 06 task 2.9 (S0.6.3): no item without a trustworthy running time reaches the scheduler, and they are counted. */
class RuntimeExclusionTest {
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    private suspend fun saveMixed() {
        repository.select(ALICE, setOf(MOVIES, SHOWS))
        repository.writePage(
            ALICE,
            MOVIES,
            listOf(
                item("ok"),
                item("no-runtime", runtime = null),
                item("zero", runtime = Duration.ZERO),
                item("negative", runtime = (-5).minutes),
                item("one-second", runtime = 1.seconds),
            ),
            nextStartIndex = 5,
            totalCount = 5,
            run = 1,
        )
        repository.writePage(ALICE, SHOWS, listOf(item("other-library", runtime = null)), 1, 1, run = 1)
    }

    @Test
    fun nullZeroAndNegativeRuntimesStayStoredButNeverInASnapshot() =
        runBlocking {
            saveMixed()

            val inSnapshot = repository.snapshot(ALICE, setOf(MOVIES), EPOCH).map { it.id.value }
            val stored = repository.stored(ALICE).filter { it.library == MOVIES }.map { it.item.id.value }

            assertEquals(listOf("ok", "one-second"), inSnapshot)
            assertEquals(listOf("negative", "no-runtime", "ok", "one-second", "zero"), stored.sorted())
        }

    @Test
    fun theExcludedItemsAreCountedForTheChosenLibraries() =
        runBlocking {
            saveMixed()

            assertEquals(3, repository.excludedCount(ALICE, setOf(MOVIES)))
            assertEquals(1, repository.excludedCount(ALICE, setOf(SHOWS)))
            assertEquals(4, repository.excludedCount(ALICE, setOf(MOVIES, SHOWS)))
            assertEquals(0, repository.excludedCount(ALICE, emptySet()))
        }

    @Test
    fun aDeletedItemIsNotCountedAsExcluded() =
        runBlocking {
            saveMixed()
            repository.tombstone(ALICE, listOf(ItemId("zero")))

            assertEquals(2, repository.excludedCount(ALICE, setOf(MOVIES)))
        }

    @Test
    fun theCountFollowsAFixOnTheServer() =
        runBlocking {
            saveMixed()

            repository.writePage(ALICE, MOVIES, listOf(item("zero", runtime = 40.minutes)), 5, 5, run = 2)

            assertEquals(2, repository.excludedCount(ALICE, setOf(MOVIES)))
            assertEquals(listOf("ok", "one-second", "zero"), repository.snapshot(ALICE, setOf(MOVIES), EPOCH).map { it.id.value })
        }
}
