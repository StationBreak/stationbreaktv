// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.SeriesPage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `librarySeries` decodes a series answer into `SeriesInfo` and `SeriesPage`, and `librarySeriesIds` into an
 * `ItemIdPage`. A rating of null, `""` and `" "` stays three different things: nothing here trims or blanks a value.
 */
class SeriesMappingTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun series(
        body: String,
        startIndex: Int = 0,
    ): ServerResult<SeriesPage> =
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }
            testAdapter().librarySeries(testSession(jellyfin.address), library, startIndex, 50)
        }

    @Test
    fun aDescribedSeriesDecodesEveryField() {
        val body =
            """{"Items":[{"Id":"s1","Name":"Night Shift","Type":"Series","OfficialRating":"TV-14",
            "Genres":["Drama","Crime"],"Tags":["slow burn"],"Overview":"ignored","Etag":"ignored"}],
            "TotalRecordCount":1,"StartIndex":0}"""

        val page = series(body).getOrNull()

        assertEquals(
            SeriesPage(
                startIndex = 0,
                totalCount = 1,
                series =
                    listOf(
                        SeriesInfo(
                            id = ItemId("s1"),
                            name = "Night Shift",
                            officialRating = "TV-14",
                            genres = listOf("Drama", "Crime"),
                            tags = listOf("slow burn"),
                        ),
                    ),
            ),
            page,
        )
    }

    @Test
    fun aSeriesWithNoRatingGenresOrTagsHasNullAndEmptyLists() {
        val one = series("""{"Items":[{"Id":"s1","Name":"Bare","Type":"Series"}],"TotalRecordCount":1}""").getOrNull()

        assertEquals(SeriesInfo(ItemId("s1"), "Bare", null, emptyList(), emptyList()), one?.series?.single())
    }

    @Test
    fun nullEmptyAndBlankRatingsStayDistinct() {
        val body =
            """{"Items":[{"Id":"s1","Name":"A","Type":"Series"},{"Id":"s2","Name":"B","Type":"Series","OfficialRating":""},
            {"Id":"s3","Name":"C","Type":"Series","OfficialRating":" "}],"TotalRecordCount":3}"""

        val ratings = series(body).getOrNull()?.series?.map { it.officialRating }

        assertEquals(listOf(null, "", " "), ratings)
    }

    @Test
    fun genresAndTagsKeepTheServersOrderAndCase() {
        val body =
            """{"Items":[{"Id":"s1","Name":"A","Type":"Series","Genres":["Zeta","alpha"," Mid "],
            "Tags":["b","A"]}],"TotalRecordCount":1}"""

        val one = series(body).getOrNull()?.series?.single()

        assertEquals(listOf("Zeta", "alpha", " Mid "), one?.genres)
        assertEquals(listOf("b", "A"), one?.tags)
    }

    @Test
    fun theFirstPageCarriesTheTotalAndALaterPageDoesNot() {
        val body = """{"Items":[],"TotalRecordCount":7}"""

        assertEquals(7, series(body, startIndex = 0).getOrNull()?.totalCount)
        assertNull(series(body, startIndex = 50).getOrNull()?.totalCount)
    }

    @Test
    fun aFirstPageWithoutATotalFailsInsteadOfLookingLikeAnEmptyLibrary() {
        val result = series("""{"Items":[]}""", startIndex = 0)

        assertIs<MediaServerError.ServerError>(result.errorOrNull())
    }

    @Test
    fun aBlankOrUnsafeIdIsNotJellyfin() {
        val result = series("""{"Items":[{"Id":"a/b","Name":"Bad","Type":"Series"}],"TotalRecordCount":1}""")

        assertIs<MediaServerError.NotJellyfin>(result.errorOrNull())
    }

    @Test
    fun theIdListingReturnsIdsInPagesAndNothingElse() {
        runBlocking {
            val all = (1..5).map { "sid%02d".format(java.util.Locale.ROOT, it) }
            jellyfin.on("GET", "/Items") { request ->
                val start = request.url.queryParameter("startIndex")!!.toInt()
                val limit = request.url.queryParameter("limit")!!.toInt()
                val items =
                    all.drop(start).take(limit).joinToString(",") {
                        "{\"Id\":\"$it\",\"Name\":\"secret title\",\"Type\":\"Series\"}"
                    }
                jellyfin.json("{\"Items\":[$items],\"TotalRecordCount\":${all.size},\"StartIndex\":$start}")
            }
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            val first = adapter.librarySeriesIds(session, library, 0, 3).getOrNull()
            val second = adapter.librarySeriesIds(session, library, 3, 3).getOrNull()

            assertEquals(all.take(3).map(::ItemId), first?.ids)
            assertEquals(5, first?.totalCount)
            assertEquals(all.drop(3).map(::ItemId), second?.ids)
            assertNull(second?.totalCount)
        }
    }
}
