// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** F0.6: the item grows the fields the cache and the scheduler need, and every one is optional (S0.6.3). */
class MediaItemFieldsTest {
    @Test
    fun anItemBuiltWithTheOriginalFiveFieldsStillBuildsAndEqualsItself() {
        val item = MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, 60.minutes, ItemId("lib01"))

        assertEquals(MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, 60.minutes, ItemId("lib01")), item)
        assertEquals(item.hashCode(), item.copy().hashCode())
    }

    @Test
    fun everyNewFieldIsAbsentUntilSet() {
        val item = MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, null, null)

        assertNull(item.dateCreated)
        assertNull(item.premiereDate)
        assertNull(item.productionYear)
        assertNull(item.officialRating)
        assertEquals(emptyList(), item.genres)
        assertEquals(emptyList(), item.tags)
        assertNull(item.seriesId)
        assertNull(item.seasonId)
        assertNull(item.seasonNumber)
        assertNull(item.episodeNumber)
    }

    @Test
    fun theNewFieldsCarryWhatTheyAreGiven() {
        val created = Instant.parse("2026-01-02T03:04:05Z")

        val item =
            MediaItem(
                id = ItemId("e1"),
                name = "Pilot",
                kind = ItemKind.Episode,
                runtime = 22.minutes,
                parentId = ItemId("season1"),
                dateCreated = created,
                premiereDate = "1994-09-22",
                productionYear = 1994,
                officialRating = "TV-14",
                genres = listOf("Comedy"),
                tags = listOf("sitcom"),
                seriesId = ItemId("series1"),
                seasonNumber = 1,
                episodeNumber = 2,
            )

        assertEquals(created, item.dateCreated)
        assertEquals("1994-09-22", item.premiereDate)
        assertEquals(1994, item.productionYear)
        assertEquals("TV-14", item.officialRating)
        assertEquals(listOf("Comedy"), item.genres)
        assertEquals(listOf("sitcom"), item.tags)
        assertEquals(ItemId("series1"), item.seriesId)
        assertEquals(1, item.seasonNumber)
        assertEquals(2, item.episodeNumber)
    }

    @Test
    fun theSeasonIdIsAnOptionalParameterSoEarlierCallersStillCompile() {
        val plain = MediaItem(ItemId("e1"), "Pilot", ItemKind.Episode, null, null, seriesId = ItemId("s1"))
        val withSeason = plain.copy(seasonId = ItemId("season1"))

        assertNull(plain.seasonId)
        assertEquals(ItemId("season1"), withSeason.seasonId)
    }
}
