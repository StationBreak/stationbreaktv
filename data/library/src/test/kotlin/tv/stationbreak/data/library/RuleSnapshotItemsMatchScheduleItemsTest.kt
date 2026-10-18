// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItemKind

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
                    val label = "items, cut-off $cutoff, libraries $selected"
                    assertEquals(label, expected, snapshot.items.map { it.item })
                    assertEquals(cutoff.toEpochMilliseconds(), snapshot.cutoffMs)
                    assertEquals(wanted.map { CanonicalId.parse(it.value) }.toSet(), snapshot.libraries)
                    assertEquals(
                        "canonical-ID order",
                        snapshot.items.map { it.item.id.value }.sorted(),
                        snapshot.items.map { it.item.id.value },
                    )
                    assertOrderedAndComplete(snapshot)
                    reads += expected.size
                }
            }
            reads
        }

    // The lookups carry no order meaning, but are built in canonical-ID order, with only series an episode points to.
    private fun assertOrderedAndComplete(snapshot: RuleSnapshot) {
        val pointedTo =
            snapshot.items
                .filter { it.item.kind == ScheduleItemKind.Episode }
                .mapNotNull { it.item.seriesId }
                .toSet()
        assertTrue("series not pointed to", pointedTo.containsAll(snapshot.series.keys))
        assertEquals(
            snapshot.series.keys
                .map { it.value }
                .sorted(),
            snapshot.series.keys.map { it.value },
        )
        snapshot.series.forEach { (id, series) -> assertEquals(id, series.id) }
        assertEquals(
            snapshot.collections.keys
                .map { it.value }
                .sorted(),
            snapshot.collections.keys.map { it.value },
        )
        snapshot.collections.values.forEach { box ->
            listOf(box.movies, box.episodes, box.series, box.seasons).forEach { members ->
                assertEquals(members.map { it.value }.sorted(), members.map { it.value })
            }
        }
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
                val message = "$NAME: case $index failed, seed $seed: ${failure.message}"
                println(message)
                throw AssertionError(message, failure)
            }
        }
        println("RuleSnapshotItemsMatchScheduleItemsTest: $CASES libraries, $itemsCompared items compared")
        // Not vacuous: the corpus really does put items in front of the read.
        assertTrue("only $itemsCompared items compared", itemsCompared > CASES * 5)
    }

    private companion object {
        const val NAME = "RuleSnapshotItemsMatchScheduleItemsTest"
        const val CASES = 1000
        const val BASE_SEED = 0x5EED_1003uL
    }
}
