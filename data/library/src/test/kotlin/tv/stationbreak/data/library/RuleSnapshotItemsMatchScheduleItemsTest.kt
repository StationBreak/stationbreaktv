// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ordering.splitmix64

/**
 * Spec 10 task 3.2, the second guard after the shared private read: over 1,000 generated libraries,
 * `ruleSnapshot(...).items.map { it.item }` equals `scheduleItems(...)` for the same cut-off and libraries. The
 * libraries hold what the scheduler refuses (so the drop rules must agree), items edited, moved, deleted and revived
 * (so the selection and deletion rules must), and the same ID in two spellings (so the duplicate rule must).
 *
 * A failure prints the case number and the seed: rerun it with `SeededGen(<seed>)`.
 */
class RuleSnapshotItemsMatchScheduleItemsTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private fun check(seed: ULong): Int =
        runBlocking {
            val library = RuleLibraryGen.fill(repository, clock, SeededGen(seed))
            var reads = 0
            for (cutoff in library.cutoffs) {
                for (selected in listOf(library.libraries, library.libraries.take(1), emptyList<ItemId>())) {
                    val wanted = selected.toSet()
                    val snapshot = repository.ruleSnapshot(ALICE, wanted, cutoff)
                    val expected = repository.scheduleItems(ALICE, wanted, cutoff)
                    assertEquals("items, cut-off $cutoff, libraries $selected", expected, snapshot.items.map { it.item })
                    assertEquals(cutoff.toEpochMilliseconds(), snapshot.cutoffMs)
                    assertEquals(wanted.map { CanonicalId.parse(it.value) }.toSet(), snapshot.libraries)
                    assertEquals(
                        "canonical-ID order",
                        snapshot.items.map { it.item.id.value }.sorted(),
                        snapshot.items.map { it.item.id.value },
                    )
                    reads += expected.size
                }
            }
            reads
        }

    @Test
    fun theSnapshotsItemsAreTheSchedulersItemsOverAThousandGeneratedLibraries() {
        var itemsCompared = 0
        for (index in 0 until CASES) {
            val seed = splitmix64(BASE_SEED + index.toULong())
            try {
                itemsCompared += check(seed)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                val message = "RuleSnapshotItemsMatchScheduleItemsTest: case $index failed, seed $seed: ${failure.message}"
                println(message)
                throw AssertionError(message, failure)
            }
        }
        println("RuleSnapshotItemsMatchScheduleItemsTest: $CASES libraries, $itemsCompared items compared")
        // Not vacuous: the corpus really does put items in front of the read.
        assertTrue("only $itemsCompared items compared", itemsCompared > CASES * 5)
    }

    private companion object {
        const val CASES = 1000
        const val BASE_SEED = 0x5EED_1003uL
    }
}
