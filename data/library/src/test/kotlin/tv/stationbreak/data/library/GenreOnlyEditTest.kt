// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.MediaItem

/**
 * Spec 09 task 2.4 (Vimes H3): the change test compares the schedule fields **and** the ordered genre and tag lists. An
 * edit to genres or tags alone is a change and keeps a version; a name-only edit or an identical rewrite is not.
 */
class GenreOnlyEditTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private suspend fun start(first: MediaItem): Unit =
        repository.select(ALICE, setOf(MOVIES)).also { repository.put(ALICE, MOVIES, first) }

    private suspend fun edit(next: MediaItem): PageWrite {
        clock.stepMs(1_000)
        return repository.put(ALICE, MOVIES, next)
    }

    private val base = item("a").copy(genres = listOf("Drama", "Comedy"), tags = listOf("x", "y"))

    @Test
    fun aGenreOnlyEditCreatesOneVersionHoldingTheOldGenres() =
        runBlocking {
            start(base)

            val write = edit(base.copy(genres = listOf("Drama", "Horror")))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.versions(ALICE).single()
            assertEquals(listOf("Drama", "Comedy"), VersionCodec.decode(version.genres))
            assertEquals(listOf("x", "y"), VersionCodec.decode(version.tags))
            assertEquals(
                listOf("Drama", "Horror"),
                repository
                    .stored(ALICE)
                    .single()
                    .item.genres,
            )
        }

    @Test
    fun aTagOnlyEditCreatesOneVersionHoldingTheOldTags() =
        runBlocking {
            start(base)

            edit(base.copy(tags = listOf("x", "z")))

            val version = repository.versions(ALICE).single()
            assertEquals(listOf("x", "y"), VersionCodec.decode(version.tags))
            assertEquals(listOf("Drama", "Comedy"), VersionCodec.decode(version.genres))
        }

    @Test
    fun reorderingGenresIsAChange() =
        runBlocking {
            start(base)

            edit(base.copy(genres = listOf("Comedy", "Drama")))

            assertEquals(1, repository.versions(ALICE).size)
        }

    @Test
    fun emptyingTheGenresIsAChange() =
        runBlocking {
            start(base)

            edit(base.copy(genres = emptyList()))

            assertEquals(listOf("Drama", "Comedy"), VersionCodec.decode(repository.versions(ALICE).single().genres))
        }

    @Test
    fun aNameOnlyEditCreatesNoVersionButIsStored() =
        runBlocking {
            start(base)

            val write = edit(base.copy(name = "A new name"))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(
                "A new name",
                repository
                    .stored(ALICE)
                    .single()
                    .item.name,
            )
            val row = repository.currentRows(ALICE).single()
            assertEquals(row.firstSeenMs, row.seenFromMs)
        }

    @Test
    fun anIdenticalRewriteCreatesNothing() =
        runBlocking {
            start(base)

            val write = edit(base)

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 1), write)
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
        }

    @Test
    fun theGenresAndTagsInTheItemRowsStayInTheSideTablesToo() =
        runBlocking {
            start(base)

            edit(base.copy(genres = listOf("Horror"), tags = emptyList()))

            val stored = repository.stored(ALICE).single().item
            assertEquals(listOf("Horror"), stored.genres)
            assertEquals(emptyList<String>(), stored.tags)
            assertEquals(1, repository.rowCounts().genres)
            assertEquals(0, repository.rowCounts().tags)
        }

    @Test
    fun aVersionHoldsEveryScheduleFieldOfTheRowItReplaced() =
        runBlocking {
            val full =
                base.copy(
                    seriesId =
                        tv.stationbreak.core.model
                            .ItemId("series-1"),
                    seasonNumber = 2,
                    episodeNumber = 3,
                    premiereDate = "2001-02-03",
                    productionYear = 2001,
                    officialRating = "PG",
                )
            start(full)

            edit(full.copy(officialRating = "R"))

            val version = repository.versions(ALICE).single()
            assertEquals("series-1", version.seriesId)
            assertEquals(2, version.seasonNumber)
            assertEquals(3, version.episodeNumber)
            assertEquals("2001-02-03", version.premiereDate)
            assertEquals(2001, version.productionYear)
            assertEquals("PG", version.officialRating)
            assertEquals(MOVIES.value, version.libraryId)
            assertEquals("Movie", version.kind)
            assertEquals(null, version.deletedAtMs)
        }
}
