// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind

/**
 * Spec 10 task 1.5.3 (Vimes L3): a rating of null, `""` and `" "` goes into the cache and comes out as it went in, on
 * series and on episodes. Rules-format rule 4 treats the first two as "no rating" and uses a blank as it is, so nothing
 * may trim, blank-to-null or merge them. This arrives green: the stores already keep the text untouched, and the test
 * keeps it so.
 */
class RatingDistinctRoundTripTest {
    private val repository = TestLibraries.inMemory(FakeClock())
    private val ratings = listOf<String?>(null, "", " ", "  TV-14 ")

    @After
    fun close() = repository.close()

    @Test
    fun seriesRatingsRoundTripDistinct() =
        runBlocking {
            repository.select(ALICE, setOf(SHOWS))

            repository.upsertSeries(ALICE, SHOWS, ratings.mapIndexed { i, rating -> series("s$i", rating = rating) })

            assertEquals(ratings, repository.currentSeries(ALICE).sortedBy { it.seriesId }.map { it.officialRating })
        }

    @Test
    fun episodeRatingsRoundTripDistinct() =
        runBlocking {
            repository.select(ALICE, setOf(SHOWS))
            val episodes =
                ratings.mapIndexed {
                    i,
                    rating,
                    ->
                    sitem(i + 1, library = SHOWS).copy(kind = ItemKind.Episode, officialRating = rating)
                }

            repository.upsertItems(ALICE, SHOWS, episodes)

            assertEquals(ratings, repository.currentRows(ALICE).sortedBy { it.itemId }.map { it.officialRating })
            assertEquals(ratings, repository.stored(ALICE).sortedBy { it.item.id.value }.map { it.item.officialRating })
        }

    @Test
    fun aRatingChangeBetweenTheThreeIsAVersionOnSeriesAndEpisodes() =
        runBlocking {
            val clock = FakeClock()
            val tracked = TestLibraries.inMemory(clock)
            tracked.select(ALICE, setOf(SHOWS))
            val id = ItemId(hexId(1))
            for (rating in listOf<String?>(null, "", " ")) {
                tracked.upsertSeries(ALICE, SHOWS, listOf(series(id.value, rating = rating)))
                tracked.upsertItems(ALICE, SHOWS, listOf(sitem(1, library = SHOWS).copy(officialRating = rating)))
                clock.stepMs(1_000)
            }

            assertEquals(2, tracked.seriesVersions(ALICE).size)
            assertEquals(2, tracked.versions(ALICE).size)
            tracked.close()
        }
}
