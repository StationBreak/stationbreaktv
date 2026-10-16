// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Spec 06 task 2.9: snapshots hold the usable items created before an instant, of the chosen libraries only. */
class LibraryRepositorySnapshotTest {
    private val repository = TestLibraries.inMemory()
    private val chosen = mutableSetOf<ItemId>()

    @After
    fun close() = repository.close()

    private suspend fun save(
        library: ItemId,
        vararg items: MediaItem,
    ) {
        chosen += library
        repository.select(ALICE, chosen.toSet())
        repository.writePage(ALICE, library, items.toList(), items.size, items.size, run = 1)
    }

    private suspend fun ids(
        libraries: Set<ItemId>,
        before: Instant = EPOCH,
    ) = repository.snapshot(ALICE, libraries, before).map { it.id.value }

    @Test
    fun onlyItemsCreatedBeforeTheInstantAreInTheSnapshot() =
        runBlocking {
            save(
                MOVIES,
                item("old", created = EPOCH - 10.minutes),
                item("just-before", created = EPOCH - 1.milliseconds),
                item("exactly-at", created = EPOCH),
                item("later", created = EPOCH + 1.minutes),
            )

            assertEquals(listOf("just-before", "old"), ids(setOf(MOVIES)))
        }

    @Test
    fun anInstantBetweenTwoMillisecondsKeepsTheItemCreatedInTheMillisecondBefore() =
        runBlocking {
            save(MOVIES, item("in-ms", created = EPOCH))

            // EPOCH + half a millisecond is after the item's millisecond, so the item was created before it.
            val between = EPOCH + 0.5.milliseconds

            assertEquals(listOf("in-ms"), ids(setOf(MOVIES), before = between))
        }

    @Test
    fun anItemWithNoCreationDateIsNeverInASnapshot() =
        runBlocking {
            save(MOVIES, item("undated", created = null), item("dated"))

            assertEquals(listOf("dated"), ids(setOf(MOVIES)))
        }

    @Test
    fun onlyTheChosenLibrariesAreRead() =
        runBlocking {
            save(MOVIES, item("movie-1"))
            save(SHOWS, item("show-1"))

            assertEquals(listOf("movie-1"), ids(setOf(MOVIES)))
            assertEquals(listOf("show-1"), ids(setOf(SHOWS)))
            assertEquals(listOf("movie-1", "show-1"), ids(setOf(MOVIES, SHOWS)))
            assertEquals(emptyList<String>(), ids(emptySet()))
        }

    @Test
    fun tombstonesAreHidden() =
        runBlocking {
            save(MOVIES, item("kept"), item("deleted"))
            repository.tombstone(ALICE, listOf(ItemId("deleted")))

            assertEquals(listOf("kept"), ids(setOf(MOVIES)))
        }

    @Test
    fun aRevivedItemIsBackInTheSnapshot() =
        runBlocking {
            save(MOVIES, item("back"))
            repository.tombstone(ALICE, listOf(ItemId("back")))
            repository.revive(ALICE, listOf(ItemId("back")))

            assertEquals(listOf("back"), ids(setOf(MOVIES)))
        }

    @Test
    fun snapshotsAreOrderedByItemId() =
        runBlocking {
            save(MOVIES, item("c"), item("a"), item("b"))

            assertEquals(listOf("a", "b", "c"), ids(setOf(MOVIES)))
        }

    @Test
    fun aBigLibraryComesBackWhole() =
        runBlocking {
            val generated = SyntheticLibrary(2_500, seed = 3).items.toList()
            repository.select(ALICE, setOf(MOVIES))
            generated.chunked(200).forEachIndexed { index, page ->
                repository.writePage(ALICE, MOVIES, page, (index + 1) * 200, 2_500, run = 1)
            }
            val farFuture = Instant.fromEpochMilliseconds(4_000_000_000_000)

            val snapshot = repository.snapshot(ALICE, setOf(MOVIES), farFuture)

            val usable = generated.filter { (it.runtime ?: Duration.ZERO) > Duration.ZERO }
            assertEquals(usable.sortedBy { it.id.value }, snapshot)
        }
}
