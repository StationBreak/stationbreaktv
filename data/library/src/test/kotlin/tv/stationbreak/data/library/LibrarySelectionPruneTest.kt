// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.followSelection
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress

/** Spec 06 task 2.10 (owner answer 2): the cache holds only the libraries the user ticked, as `ServerSettings` has them. */
class LibrarySelectionPruneTest {
    private val repository = TestLibraries.inMemory()
    private val record = ServerRecord(ServerAddress(Scheme.Https, "jellyfin.example", 443))
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
            repository.select(ALICE, setOf(MOVIES, SHOWS))

            assertEquals(setOf(MOVIES, SHOWS), repository.pendingLibraries(ALICE))

            repository.markFetched(ALICE, MOVIES)

            assertEquals(setOf(SHOWS), repository.pendingLibraries(ALICE))
        }

    @Test
    fun untickingALibraryRemovesItsRowsAndNothingElse() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES, SHOWS))
            fill(MOVIES)
            fill(SHOWS)

            repository.select(ALICE, setOf(SHOWS))

            assertEquals(listOf("lib-shows-1"), repository.stored(ALICE).map { it.item.id.value })
            assertNull(repository.checkpoint(ALICE, MOVIES))
            assertEquals(Checkpoint(SHOWS, 1, 1, 1), repository.checkpoint(ALICE, SHOWS))
            // Genres go with their items.
            assertEquals(1, repository.rowCounts().genres)
        }

    @Test
    fun selectingAgainKeepsWhatIsSavedAndItsFetchedMark() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            fill(MOVIES)
            repository.markFetched(ALICE, MOVIES)

            repository.select(ALICE, setOf(MOVIES, extra))

            assertEquals(1, repository.stored(ALICE).size)
            assertEquals(setOf(extra), repository.pendingLibraries(ALICE))
        }

    @Test
    fun aLibraryUntickedAndTickedAgainStartsFromScratch() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES, SHOWS))
            fill(MOVIES)
            repository.markFetched(ALICE, MOVIES)
            repository.select(ALICE, setOf(SHOWS))

            repository.select(ALICE, setOf(MOVIES, SHOWS))

            assertEquals(emptyList<String>(), repository.stored(ALICE).map { it.item.id.value })
            assertEquals(setOf(MOVIES, SHOWS), repository.pendingLibraries(ALICE))
        }

    @Test
    fun anotherAccountsSelectionIsNotTouched() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.select(BOB, setOf(MOVIES))
            fill(MOVIES)
            repository.writePage(BOB, MOVIES, listOf(item("bobs")), 1, 1, run = 1)

            repository.select(ALICE, setOf(SHOWS))

            assertEquals(listOf("bobs"), repository.stored(BOB).map { it.item.id.value })
            assertEquals(setOf(MOVIES), repository.pendingLibraries(BOB))
        }

    @Test
    fun theSelectionFollowsServerSettings() =
        runBlocking {
            val settings = InMemoryServerSettings(record, initialLibraries = setOf(MOVIES, SHOWS))
            assertEquals(setOf(MOVIES, SHOWS), repository.followSelection(ALICE, settings))
            fill(MOVIES)
            fill(SHOWS)

            settings.saveLibraries(setOf(SHOWS, extra))
            val chosen = repository.followSelection(ALICE, settings)

            assertEquals(setOf(SHOWS, extra), chosen)
            assertEquals(listOf("lib-shows-1"), repository.stored(ALICE).map { it.item.id.value })
            assertEquals(setOf(SHOWS, extra), repository.pendingLibraries(ALICE))
        }

    @Test
    fun noStoredSelectionLeavesTheCacheAlone() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            fill(MOVIES)
            val settings = InMemoryServerSettings(record, initialLibraries = null)

            assertNull(repository.followSelection(ALICE, settings))

            assertEquals(1, repository.stored(ALICE).size)
        }
}
