// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 10 task 1.5.1: series history follows the item history rules (spec 09, Vimes H1 and H2). A rating, genre or tag
 * edit makes a version; a name-only or library-only change makes none; a tombstone adds none and a revival adds one;
 * the stamps are strictly increasing whatever the clock does; a replayed page changes nothing.
 */
class SeriesHistoryTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs
    private val id = ItemId("s1")

    @After
    fun close() = repository.close()

    private suspend fun open() = repository.select(ALICE, setOf(MOVIES, SHOWS))

    private suspend fun current() = repository.currentSeries(ALICE).single()

    private suspend fun put(series: tv.stationbreak.core.model.SeriesInfo) = repository.putSeries(ALICE, SHOWS, series)

    @Test
    fun aNewSeriesIsFirstSeenNowAndHasNoVersion() =
        runBlocking {
            open()

            val write = put(series("s1", rating = "TV-14", genres = listOf("Drama"), tags = listOf("slow")))

            assertEquals(PageWrite(inserted = 1, updated = 0, unchanged = 0), write)
            val row = current()
            assertEquals(start, row.seenFromMs)
            assertEquals(start, row.firstSeenMs)
            assertEquals("TV-14", row.officialRating)
            assertEquals(listOf("Drama"), VersionCodec.decode(row.genres))
            assertEquals(listOf("slow"), VersionCodec.decode(row.tags))
            assertEquals(SHOWS.value, row.libraryId)
            assertNull(row.deletedAtMs)
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
        }

    @Test
    fun aGenreEditKeepsTheOldRowAsAVersionEndingWhereTheNewOneBegins() =
        runBlocking {
            open()
            put(series("s1", genres = listOf("Drama")))
            clock.stepMs(5_000)

            val write = put(series("s1", genres = listOf("Drama", "Crime")))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.seriesVersions(ALICE).single()
            assertEquals(start, version.seenFromMs)
            assertEquals(start + 5_000, version.supersededAtMs)
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertEquals(start + 5_000, current().seenFromMs)
            assertEquals(start, current().firstSeenMs)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun aTagEditMakesAVersion() =
        runBlocking {
            open()
            put(series("s1", tags = listOf("a")))
            clock.stepMs(1_000)

            put(series("s1", tags = listOf("a", "b")))

            assertEquals(listOf("a"), VersionCodec.decode(repository.seriesVersions(ALICE).single().tags))
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun aRatingEditMakesAVersion() =
        runBlocking {
            open()
            put(series("s1", rating = null))
            clock.stepMs(1_000)

            put(series("s1", rating = "TV-MA"))

            assertNull(repository.seriesVersions(ALICE).single().officialRating)
            assertEquals("TV-MA", current().officialRating)
        }

    @Test
    fun nullEmptyAndBlankRatingsAreThreeDifferentValuesSoEachChangeIsAVersion() =
        runBlocking {
            open()
            put(series("s1", rating = null))
            clock.stepMs(1_000)
            put(series("s1", rating = ""))
            clock.stepMs(1_000)
            put(series("s1", rating = " "))

            val earlier = repository.seriesVersions(ALICE).sortedBy { it.seenFromMs }
            assertEquals(listOf(null, ""), earlier.map { it.officialRating })
            assertEquals(" ", current().officialRating)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun theOrderOfGenresIsPartOfTheChange() =
        runBlocking {
            open()
            put(series("s1", genres = listOf("A", "B")))
            clock.stepMs(1_000)

            put(series("s1", genres = listOf("B", "A")))

            assertEquals(1, repository.seriesVersions(ALICE).size)
        }

    @Test
    fun aNameOnlyEditMakesNoVersionButIsStored() =
        runBlocking {
            open()
            put(series("s1", name = "Old name"))
            clock.stepMs(1_000)

            val write = put(series("s1", name = "New name"))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals("New name", current().name)
            assertEquals(start, current().seenFromMs)
        }

    @Test
    fun aLibraryOnlyChangeMakesNoVersionButIsStored() =
        runBlocking {
            open()
            put(series("s1"))
            clock.stepMs(1_000)

            repository.putSeries(ALICE, MOVIES, series("s1"))

            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(MOVIES.value, current().libraryId)
            assertEquals(start, current().seenFromMs)
        }

    @Test
    fun settingATombstoneAddsNoVersionAndKeepsTheStamp() =
        runBlocking {
            open()
            put(series("s1", genres = listOf("Drama")))
            clock.stepMs(10_000)

            repository.tombstoneSeries(ALICE, listOf(id))

            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(start + 10_000, current().deletedAtMs)
            assertEquals(start, current().seenFromMs)
        }

    @Test
    fun reviveCopiesTheTombstonedRowAsAVersionEndingAtTheNewStamp() =
        runBlocking {
            open()
            put(series("s1", genres = listOf("Drama")))
            clock.stepMs(10_000)
            repository.tombstoneSeries(ALICE, listOf(id))
            clock.stepMs(10_000)

            repository.reviveSeries(ALICE, listOf(id))

            val version = repository.seriesVersions(ALICE).single()
            assertEquals(start, version.seenFromMs)
            assertEquals(start + 20_000, version.supersededAtMs)
            assertEquals(start + 10_000, version.deletedAtMs)
            assertEquals(listOf("Drama"), VersionCodec.decode(version.genres))
            assertNull(current().deletedAtMs)
            assertEquals(start + 20_000, current().seenFromMs)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun aReviveOfASeriesThatIsNotATombstoneChangesNothing() =
        runBlocking {
            open()
            put(series("s1"))
            clock.stepMs(1_000)

            repository.reviveSeries(ALICE, listOf(id))

            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(start, current().seenFromMs)
        }

    @Test
    fun aPageRowThatClearsTheTombstoneIsAVersionEvenIfNothingElseChanged() =
        runBlocking {
            open()
            put(series("s1"))
            clock.stepMs(10_000)
            repository.tombstoneSeries(ALICE, listOf(id))
            clock.stepMs(10_000)

            val write = put(series("s1"))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            assertEquals(start + 10_000, repository.seriesVersions(ALICE).single().deletedAtMs)
            assertNull(current().deletedAtMs)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun editsInTheSameMillisecondGetDistinctIncreasingStamps() =
        runBlocking {
            open()
            put(series("s1", rating = "A"))

            put(series("s1", rating = "B"))
            put(series("s1", rating = "C"))
            put(series("s1", rating = "D"))

            assertEquals(listOf(start, start + 1, start + 2), repository.seriesVersions(ALICE).map { it.seenFromMs })
            assertEquals(start + 3, current().seenFromMs)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun aClockThatStepsBackStillGivesIncreasingStamps() =
        runBlocking {
            open()
            clock.stepMs(10_000)
            put(series("s1", rating = "A"))
            val first = clock.nowMs
            clock.rewindMs(60_000)

            put(series("s1", rating = "B"))
            put(series("s1", rating = "C"))

            assertEquals(first + 2, current().seenFromMs)
            assertEquals(listOf(first, first + 1), repository.seriesVersions(ALICE).map { it.seenFromMs })
            repository.assertSeriesIntervalsContiguous(ALICE)
        }

    @Test
    fun aPageReplayedChangesNothingTheSecondTime() =
        runBlocking {
            open()
            val page = arrayOf(series("s1", rating = "A", genres = listOf("x")), series("s2"))
            repository.putSeries(ALICE, SHOWS, *page)
            clock.stepMs(1_000)
            val before = repository.currentSeries(ALICE)

            val again = repository.putSeries(ALICE, SHOWS, *page)

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 2), again)
            assertEquals(before, repository.currentSeries(ALICE))
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
        }

    @Test
    fun anEditedPageReplayedAfterTheEditAddsNoSecondVersion() =
        runBlocking {
            open()
            put(series("s1", rating = "A"))
            clock.stepMs(1_000)
            put(series("s1", rating = "B"))
            clock.stepMs(1_000)

            put(series("s1", rating = "B"))

            assertEquals(1, repository.seriesVersions(ALICE).size)
        }
}
