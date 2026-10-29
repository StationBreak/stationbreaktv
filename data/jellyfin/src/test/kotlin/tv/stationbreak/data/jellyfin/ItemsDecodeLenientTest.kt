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
            "DateCreated":"2026-01-02T03:04:05.0000000Z",
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
        val body = """{"Items":[{"Id":"i1","Type":"Movie","DateCreated":"not a date"}],"TotalRecordCount":1}"""

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

    @Test
    fun aFirstPageWithNoTotalIsAServerErrorNotAnEmptyLibrary() {
        val error = page("""{"Items":[{"Id":"i1","Name":"x","Type":"Movie"}]}""", startIndex = 0).errorOrNull()

        assertIs<MediaServerError.ServerError>(error)
    }

    @Test
    fun aLaterPageNeedsNoTotal() {
        val items = page("""{"Items":[{"Id":"i1","Name":"x","Type":"Movie"}]}""", startIndex = 50).getOrNull()?.items

        assertEquals(1, items?.size)
    }

    @Test
    fun aRuntimeOfZeroTicksIsUnknownToo() {
        val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie","RunTimeTicks":0}],"TotalRecordCount":1}"""

        assertNull(
            page(body)
                .getOrNull()
                ?.items
                ?.single()
                ?.runtime,
        )
    }

    private fun studiosOfItem(studios: String): List<String>? {
        val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie"$studios}],"TotalRecordCount":1}"""
        return page(body)
            .getOrNull()
            ?.items
            ?.single()
            ?.studios
    }

    @Test
    fun studiosAreReadByNameAndKeepTheirOrderSpellingAndInnerSpaces() {
        val studios = ""","Studios":[{"Name":"Zed Pictures","Id":"x"},{"Name":" a studio "},{"Name":"A  Studio"}]"""

        assertEquals(listOf("Zed Pictures", "a studio", "A  Studio"), studiosOfItem(studios))
    }

    // JSON escapes built from numbers, so the source holds no invisible character for the formatter to rewrite.
    private fun esc(vararg codes: Int) = codes.joinToString("") { "\\u" + it.toString(16).padStart(4, '0') }

    private fun named(vararg names: String) =
        names.joinToString(",", prefix = ""","Studios":[""", postfix = "]") {
            """{"Name":"$it"}"""
        }

    @Test
    fun aStudioNameLosesControlBidiAndZeroWidthCharactersButStillReadsAsItsName() {
        val studios = named("Pix${esc(0x202E)}ar", "Ani${esc(0x200B)}mation${esc(7)}", "${esc(0x85)}Ghi${esc(0x9F)}bli")

        assertEquals(listOf("Pixar", "Animation", "Ghibli"), studiosOfItem(studios))
    }

    @Test
    fun aTabOrNewLineInAStudioNameBecomesASpaceNotAFusion() {
        val studios = named("Cartoon${esc(9)}Network", "Nick${esc(10)}Jr", "A${esc(0x2028)}B${esc(0x2029)}C")

        assertEquals(listOf("Cartoon Network", "Nick Jr", "A B C"), studiosOfItem(studios))
    }

    @Test
    fun aStudioNameKeepsZeroWidthJoinersAndLosesALoneSurrogate() {
        val studios = named("A${esc(0x200D)}B${esc(0x200C)}C", "Lone${esc(0xD800)} Name")
        val joined = "A" + String(Character.toChars(0x200D)) + "B" + String(Character.toChars(0x200C)) + "C"

        assertEquals(listOf(joined, "Lone Name"), studiosOfItem(studios))
    }

    @Test
    fun aStudioNameOfOnlyFormatCharactersIsDropped() {
        val studios = named(esc(0x202E, 0x200B, 0x2066, 0xFEFF), " ${esc(0x200B)} ", "Kept")

        assertEquals(listOf("Kept"), studiosOfItem(studios))
    }

    // The 256 limit is on the name as sent: one that fits only once invisible characters are stripped is dropped.
    @Test
    fun theLengthLimitIsCheckedOnTheRawNameBeforeCleaning() {
        val rawLong = "a".repeat(250) + esc(*IntArray(10) { 0x200B })
        val fits = "a".repeat(250) + esc(*IntArray(6) { 0x200B })

        assertEquals(listOf("Kept"), studiosOfItem(named(rawLong, "Kept")))
        assertEquals(listOf("a".repeat(250)), studiosOfItem(named(fits)))
    }

    @Test
    fun missingNullAndEmptyStudiosAreEmpty() {
        assertEquals(emptyList(), studiosOfItem(""))
        assertEquals(emptyList(), studiosOfItem(""","Studios":null"""))
        assertEquals(emptyList(), studiosOfItem(""","Studios":[]"""))
    }

    @Test
    fun aStudioWithNoOrBlankNameIsDropped() {
        val studios = ""","Studios":[{"Id":"x"},{"Name":null},{"Name":""},{"Name":"  "},{"Name":"Kept"}]"""

        assertEquals(listOf("Kept"), studiosOfItem(studios))
    }

    @Test
    fun aStudioWithAnExtraFieldAndANumericIdDecodes() {
        val studios = ""","Studios":[{"Name":"Invented Pictures","Id":42,"Extra":{"Nested":[1,2]}}]"""

        assertEquals(listOf("Invented Pictures"), studiosOfItem(studios))
    }

    @Test
    fun aNullStudioIsDroppedAndTheRestKept() {
        assertEquals(listOf("Kept"), studiosOfItem(""","Studios":[null,{"Name":"Kept"},null]"""))
    }

    @Test
    fun atMostSixteenStudiosAreKept() {
        val many = (1..20).joinToString(",", prefix = ""","Studios":[""", postfix = "]") { """{"Name":"S$it"}""" }

        assertEquals((1..16).map { "S$it" }, studiosOfItem(many))
    }

    @Test
    fun aNameOver256CharactersIsDroppedNotCut() {
        val long = "a".repeat(257)
        val exact = "b".repeat(256)

        assertEquals(listOf(exact), studiosOfItem(""","Studios":[{"Name":"$long"},{"Name":"$exact"}]"""))
    }

    @Test
    fun studiosThatAreTextFailThePageLikeTheOtherStrictFields() {
        val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie","Studios":"A Studio"}],"TotalRecordCount":1}"""

        assertIs<MediaServerError.NotJellyfin>(page(body).errorOrNull())
    }
}
