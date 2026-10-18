// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Spec 10 task 1.3.3: an episode's `SeasonId` reaches `MediaItem.seasonId` like the other IDs, and an episode's
 * `OfficialRating` is passed on exactly as sent. Null, `""` and `" "` stay three different values (never trimmed,
 * never blank-to-null), because a rule that excludes a rating must not mistake one for another.
 */
class EpisodeSeasonMappingTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun page(items: String): ServerResult<LibraryItemsPage> =
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("""{"Items":[$items],"TotalRecordCount":1,"StartIndex":0}""") }
            testAdapter().libraryItems(testSession(jellyfin.address), library, 0, 50)
        }

    private fun one(items: String): MediaItem = page(items).getOrNull()?.items?.single() ?: error("no item")

    @Test
    fun anEpisodesSeasonIdMapsToTheItemsSeasonId() {
        val item = one("""{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"series1","SeasonId":"season1"}""")

        assertEquals(ItemId("season1"), item.seasonId)
        assertEquals(ItemId("series1"), item.seriesId)
    }

    @Test
    fun anEpisodeWithNoSeasonFolderHasNoSeasonId() {
        assertNull(one("""{"Id":"e1","Name":"Loose","Type":"Episode","SeriesId":"series1"}""").seasonId)
    }

    @Test
    fun aMovieHasNoSeasonId() {
        assertNull(one("""{"Id":"m1","Name":"Heat","Type":"Movie"}""").seasonId)
    }

    @Test
    fun anUnsafeSeasonIdIsNotJellyfinLikeAnyOtherUnsafeId() {
        val result = page("""{"Id":"e1","Name":"Bad","Type":"Episode","SeasonId":"a/b"}""")

        assertIs<MediaServerError.NotJellyfin>(result.errorOrNull())
    }

    @Test
    fun aMissingEpisodeRatingIsNull() {
        assertNull(one("""{"Id":"e1","Name":"Pilot","Type":"Episode"}""").officialRating)
    }

    @Test
    fun nullEmptyAndBlankRatingsStayDistinct() {
        val none = one("""{"Id":"e1","Name":"A","Type":"Episode"}""").officialRating
        val empty = one("""{"Id":"e2","Name":"B","Type":"Episode","OfficialRating":""}""").officialRating
        val blank = one("""{"Id":"e3","Name":"C","Type":"Episode","OfficialRating":" "}""").officialRating

        assertEquals(listOf(null, "", " "), listOf(none, empty, blank))
    }
}
