// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** `libraryItems` decodes what the server sends and shrugs at what it doesn't: no answer fails for a missing field. */
class ItemsDecodeLenientTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun page(
        body: String,
        startIndex: Int = 0,
    ): ServerResult<LibraryItemsPage> =
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }
            testAdapter().libraryItems(testSession(jellyfin.address), library, startIndex, 50)
        }

    @Test
    fun aFullyDescribedEpisodeDecodesEveryField() {
        val body =
            """{"Items":[{"Id":"e1","Name":"Pilot","Type":"Episode","RunTimeTicks":13200000000,"ParentId":"s1",
            "DateCreated":"2026-01-02T03:04:05.0000000Z","DateLastSaved":"2026-02-03T04:05:06.1234567Z",
            "ProductionYear":1994,"OfficialRating":"TV-14","Genres":["Comedy","Drama"],"Tags":["sitcom"],
            "SeriesId":"series1","ParentIndexNumber":1,"IndexNumber":2,"Etag":"ignored","Overview":"ignored"}],
            "TotalRecordCount":1,"StartIndex":0}"""

        val item = page(body).getOrNull()?.items?.single()

        assertEquals(
            MediaItem(
                id = ItemId("e1"),
                name = "Pilot",
                kind = ItemKind.Episode,
                runtime = 22.minutes,
                parentId = ItemId("s1"),
                dateCreated = Instant.parse("2026-01-02T03:04:05Z"),
                dateLastSaved = Instant.parse("2026-02-03T04:05:06.123456700Z"),
                productionYear = 1994,
                officialRating = "TV-14",
                genres = listOf("Comedy", "Drama"),
                tags = listOf("sitcom"),
                seriesId = ItemId("series1"),
                seasonNumber = 1,
                episodeNumber = 2,
            ),
            item,
        )
    }

    @Test
    fun anItemWithOnlyAnIdAndATypeStillDecodes() {
        val item = page("""{"Items":[{"Id":"i1","Type":"Movie"}],"TotalRecordCount":1}""").getOrNull()?.items?.single()

        assertEquals(MediaItem(ItemId("i1"), "", ItemKind.Movie, null, null), item)
    }

    @Test
    fun nullsAndUnknownFieldsAreIgnored() {
        val body =
            """{"Items":[{"Id":"i1","Name":"x","Type":"Video","DateCreated":null,"Genres":null,"Tags":null,
            "SeriesId":null,"SomethingNew":{"a":1}}],"TotalRecordCount":1,"AlsoNew":true}"""

        val item = page(body).getOrNull()?.items?.single()

        assertEquals(MediaItem(ItemId("i1"), "x", ItemKind.Video, null, null), item)
    }

    @Test
    fun anUnreadableDateIsAbsentNotAFailure() {
        val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie","DateCreated":"not a date"}]}"""

        val item = page(body).getOrNull()?.items?.single()

        assertNull(item?.dateCreated)
    }

    @Test
    fun aNegativeRuntimeIsUnknown() {
        val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie","RunTimeTicks":-5}],"TotalRecordCount":1}"""

        assertNull(
            page(body)
                .getOrNull()
                ?.items
                ?.single()
                ?.runtime,
        )
    }

    @Test
    fun theFirstPageCarriesTheTotalAndLaterPagesDoNot() {
        val body = """{"Items":[],"TotalRecordCount":42,"StartIndex":0}"""

        assertEquals(42, page(body, startIndex = 0).getOrNull()?.totalCount)
        assertNull(page(body, startIndex = 50).getOrNull()?.totalCount)
    }

    @Test
    fun aBlankOrUnsafeIdIsNotJellyfin() {
        for (id in listOf("", "  ", "../Users", "a/b", "a?b")) {
            val body = """{"Items":[{"Id":"$id","Name":"x","Type":"Movie"}],"TotalRecordCount":1}"""

            assertIs<MediaServerError.NotJellyfin>(page(body).errorOrNull(), "id '$id'")
        }
    }

    @Test
    fun aSeriesIdThatIsUnsafeIsNotJellyfinEither() {
        val body = """{"Items":[{"Id":"e1","Name":"x","Type":"Episode","SeriesId":"../x"}],"TotalRecordCount":1}"""

        assertIs<MediaServerError.NotJellyfin>(page(body).errorOrNull())
    }

    @Test
    fun anAnswerThatIsNotJsonIsNotJellyfin() {
        assertIs<MediaServerError.NotJellyfin>(page("<html>nope</html>").errorOrNull())
    }
}
