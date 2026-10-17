// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** What `libraryItems` asks the server for (F0.6, spec 06 Unit 1). Jellyfin reads query names without case. */
class ItemsRequestShapeTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun sent(
        startIndex: Int = 0,
        limit: Int = 50,
        changedSince: Instant? = null,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"TotalRecordCount\":0}") }
            testAdapter().libraryItems(testSession(jellyfin.address), library, startIndex, limit, changedSince)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun HttpUrl.listOf(name: String): List<String> = queryParameter(name).orEmpty().split(',')

    @Test
    fun asksForTheLibrarysItemsRecursively() {
        val url = sent()

        assertEquals("true", url.queryParameter("recursive"))
        assertEquals("lib01", url.queryParameter("parentId"))
    }

    @Test
    fun asksForTheVideoKindsOnly() {
        val url = sent()

        assertEquals(listOf("Movie", "Episode", "Video", "MusicVideo"), url.listOf("includeItemTypes"))
    }

    @Test
    fun namesTheFieldsItNeedsAndNoOthers() {
        val url = sent()

        assertEquals(
            setOf("DateCreated", "PremiereDate", "Genres", "Tags", "ParentId"),
            url.listOf("fields").toSet(),
        )
    }

    @Test
    fun sortsByNameWhichTheServerBreaksTiesOnByIdSoAPageBoundaryCannotSkipOrRepeat() {
        val url = sent()

        assertEquals(listOf("SortName"), url.listOf("sortBy"))
        assertEquals("Ascending", url.queryParameter("sortOrder"))
    }

    @Test
    fun passesTheOffsetAndLimitThrough() {
        val url = sent(startIndex = 150, limit = 25)

        assertEquals("150", url.queryParameter("startIndex"))
        assertEquals("25", url.queryParameter("limit"))
    }

    @Test
    fun asksForTheTotalCountOnTheFirstPageOnly() {
        assertEquals("true", sent(startIndex = 0).queryParameter("enableTotalRecordCount"))
        assertEquals("false", sent(startIndex = 200).queryParameter("enableTotalRecordCount"))
    }

    @Test
    fun sendsNoChangedSinceFilterUnlessAMomentIsGiven() {
        assertNull(sent().queryParameter("minDateLastSaved"))
    }

    @Test
    fun sendsTheChangedSinceMomentInUtc() {
        val url = sent(changedSince = Instant.parse("2026-03-04T05:06:07Z"))

        assertEquals("2026-03-04T05:06:07Z", url.queryParameter("minDateLastSaved"))
    }

    @Test
    fun cutsTheChangedSinceMomentToMillisecondsSoTheServerCanReadIt() {
        val url = sent(changedSince = Instant.parse("2026-03-04T05:06:07.123456789Z"))

        assertEquals("2026-03-04T05:06:07.123Z", url.queryParameter("minDateLastSaved"))
    }
}
