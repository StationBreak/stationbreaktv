// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 18 section 2: studios are part of the change test, for items and for series. The re-read after the upgrade
 * gives an item or a series that gained studios, and nothing else, one new version holding the old (empty) studios;
 * the side table or the column fills and the content generation goes up by one. A second identical re-read changes
 * nothing.
 */
class StudioChangeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private val movie = sitem(1).copy(genres = listOf("Drama"), tags = listOf("x"))
    private val show = series("s1", rating = "TV-G", genres = listOf("Animation"))
    private val studios = listOf("Invented Studio", "Another Invented Studio")

    @Test
    fun anItemThatGainsOnlyStudiosGetsOneVersionHoldingTheOldEmptyStudios() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, movie)
            clock.stepMs(1_000)
            val before = repository.contentGeneration(ALICE)

            val write = repository.put(ALICE, MOVIES, movie.copy(studios = studios))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.versions(ALICE).single()
            assertEquals(emptyList<String>(), VersionCodec.decode(version.studios))
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertEquals(
                studios,
                repository
                    .stored(ALICE)
                    .single()
                    .item.studios,
            )
            assertEquals(2, repository.rowCounts().studios)
            assertEquals(before + 1, repository.contentGeneration(ALICE))
            repository.assertIntervalsContiguous(ALICE)

            clock.stepMs(1_000)
            val again = repository.put(ALICE, MOVIES, movie.copy(studios = studios))

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 1), again)
            assertEquals(1, repository.versions(ALICE).size)
            assertEquals(before + 1, repository.contentGeneration(ALICE))
        }

    @Test
    fun aSeriesThatGainsOnlyStudiosGetsOneVersionHoldingTheOldEmptyStudios() =
        runBlocking {
            repository.select(ALICE, setOf(SHOWS))
            repository.putSeries(ALICE, SHOWS, show)
            clock.stepMs(1_000)
            val before = repository.contentGeneration(ALICE)

            val write = repository.putSeries(ALICE, SHOWS, show.copy(studios = studios))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.seriesVersions(ALICE).single()
            assertEquals(emptyList<String>(), VersionCodec.decode(version.studios))
            assertEquals(listOf("Animation"), VersionCodec.decode(version.genres))
            assertEquals(studios, VersionCodec.decode(repository.currentSeries(ALICE).single().studios))
            assertEquals(before + 1, repository.contentGeneration(ALICE))
            repository.assertSeriesIntervalsContiguous(ALICE)

            clock.stepMs(1_000)
            val again = repository.putSeries(ALICE, SHOWS, show.copy(studios = studios))

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 1), again)
            assertEquals(1, repository.seriesVersions(ALICE).size)
            assertEquals(before + 1, repository.contentGeneration(ALICE))
        }

    @Test
    fun reorderingStudiosIsAChange() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, movie.copy(studios = studios))
            clock.stepMs(1_000)

            repository.put(ALICE, MOVIES, movie.copy(studios = studios.reversed()))

            assertEquals(studios, VersionCodec.decode(repository.versions(ALICE).single().studios))
        }
}
