// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/** Spec 06 task 2.10 (owner answer 2): the cache holds only the libraries ticked, as `ServerSettings` has them. */
class LibrarySelectionPruneTest {
    private val repository = TestLibraries.inMemory()
    private val extra = ItemId("lib-extra")

    @After
    fun close() = repository.close()

    private suspend fun fill(library: ItemId) {
        repository.writePage(
            ALICE,
            library,
            listOf(item("${library.value}-1").copy(genres = listOf("g"))),
            nextStartIndex = 1,
            totalCount = 1,
            run = 1,
        )
    }

    @Test
    fun tickingMarksALibraryPendingAndFetched() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)

            assertEquals(setOf(MOVIES, SHOWS), repository.pendingLibraries(ALICE))

            repository.markFetched(ALICE, MOVIES)

            assertEquals(setOf(SHOWS), repository.pendingLibraries(ALICE))
        }

    @Test
    fun untickingALibraryRemovesItsRowsAndNothingElse() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)
            fill(MOVIES)
            fill(SHOWS)

            repository.applySelection(ALICE, setOf(SHOWS), nowMs = 0)

            assertEquals(listOf("${SHOWS.value}-1"), repository.stored(ALICE).map { it.item.id.value })
            assertNull(repository.checkpoint(ALICE, MOVIES))
            assertEquals(Checkpoint(SHOWS, 1, 1, 1), repository.checkpoint(ALICE, SHOWS))
            // Genres go with their items.
            assertEquals(1, repository.rowCounts().genres)
        }

    @Test
    fun selectingAgainKeepsWhatIsSavedAndItsFetchedMark() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            fill(MOVIES)
            repository.markFetched(ALICE, MOVIES)

            repository.applySelection(ALICE, setOf(MOVIES, extra), nowMs = 0)

            assertEquals(1, repository.stored(ALICE).size)
            assertEquals(setOf(extra), repository.pendingLibraries(ALICE))
        }

    @Test
    fun aLibraryUntickedAndTickedAgainStartsFromScratch() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)
            fill(MOVIES)
            repository.markFetched(ALICE, MOVIES)
            repository.applySelection(ALICE, setOf(SHOWS), nowMs = 0)

            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)

            assertEquals(emptyList<String>(), repository.stored(ALICE).map { it.item.id.value })
            assertEquals(setOf(MOVIES, SHOWS), repository.pendingLibraries(ALICE))
        }

    @Test
    fun anotherAccountsSelectionIsNotTouched() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            repository.applySelection(BOB, setOf(MOVIES), nowMs = 0)
            fill(MOVIES)
            repository.writePage(BOB, MOVIES, listOf(item("bobs")), 1, 1, run = 1)

            repository.applySelection(ALICE, setOf(SHOWS), nowMs = 0)

            assertEquals(listOf("bobs"), repository.stored(BOB).map { it.item.id.value })
            assertEquals(setOf(MOVIES), repository.pendingLibraries(BOB))
        }
}
