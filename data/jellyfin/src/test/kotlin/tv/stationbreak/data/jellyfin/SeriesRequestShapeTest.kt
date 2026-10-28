// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What `librarySeries` and `librarySeriesIds` ask the server for (spec 10, Unit 1): the library's series at any depth,
 * with their genres and tags, in the same stable order as the item walk, the total on the first page only, and a
 * changed-since filter only when a moment is given. Jellyfin reads query names without case.
 */
class SeriesRequestShapeTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)
    private val empty = "{\"Items\":[],\"TotalRecordCount\":0}"

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun sent(
        startIndex: Int = 0,
        limit: Int = 50,
        changedSince: Instant? = null,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(empty) }
            testAdapter().librarySeries(testSession(jellyfin.address), library, startIndex, limit, changedSince)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun sentIds(
        startIndex: Int = 0,
        limit: Int = 50,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(empty) }
            testAdapter().librarySeriesIds(testSession(jellyfin.address), library, startIndex, limit)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun HttpUrl.listOf(name: String): List<String> = queryParameter(name).orEmpty().split(',')

    @Test
    fun asksForTheLibrarysSeriesAtAnyDepthAndNothingElse() {
        val url = sent()

        assertEquals("true", url.queryParameter("recursive"))
        assertEquals("lib01", url.queryParameter("parentId"))
        assertEquals(listOf("Series"), url.listOf("includeItemTypes"))
    }

    @Test
    fun namesGenresTagsAndStudiosAsTheOnlyFields() {
        assertEquals(listOf("Genres", "Tags", "Studios"), sent().listOf("fields"))
    }

    @Test
    fun usesTheSameStableOrderAsTheItemWalk() {
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
    fun doesNotCollapseBoxSetsLikeEveryOtherItemCall() {
        assertEquals("false", sent().queryParameter("collapseBoxSetItems"))
        assertEquals("false", sentIds().queryParameter("collapseBoxSetItems"))
    }

    @Test
    fun sendsNoChangedSinceFilterUnlessAMomentIsGiven() {
        assertNull(sent().queryParameter("minDateLastSaved"))
    }

    @Test
    fun sendsTheChangedSinceMomentInUtcCutToMilliseconds() {
        val url = sent(changedSince = Instant.parse("2026-03-04T05:06:07.123456789Z"))

        assertEquals("2026-03-04T05:06:07.123Z", url.queryParameter("minDateLastSaved"))
    }

    @Test
    fun theIdListingAsksForSeriesIdsOnlyWithTheSameOrderAndScope() {
        val url = sentIds(startIndex = 0, limit = 100)

        assertEquals("true", url.queryParameter("recursive"))
        assertEquals("lib01", url.queryParameter("parentId"))
        assertEquals(listOf("Series"), url.listOf("includeItemTypes"))
        assertEquals(listOf("SortName"), url.listOf("sortBy"))
        assertEquals("Ascending", url.queryParameter("sortOrder"))
        assertEquals("true", url.queryParameter("enableTotalRecordCount"))
        assertEquals("false", url.queryParameter("enableImages"))
        assertEquals("false", url.queryParameter("enableUserData"))
        // This is the whole list: no fields and no change filter.
        assertNull(url.queryParameter("fields"))
        assertNull(url.queryParameter("minDateLastSaved"))
    }

    @Test
    fun theIdListingAsksForTheTotalOnTheFirstPageOnly() {
        assertEquals("false", sentIds(startIndex = 100).queryParameter("enableTotalRecordCount"))
    }

    @Test
    fun badArgumentsThrowBeforeAnythingIsSent() {
        runBlocking {
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1

            assertFailsWith<IllegalArgumentException> { adapter.librarySeries(session, library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.librarySeries(session, library, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.librarySeries(session, library, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { adapter.librarySeriesIds(session, library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.librarySeriesIds(session, library, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.librarySeriesIds(session, library, 0, tooMany) }
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun aRejectedTokenIsSessionExpiredForBothCalls() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.status(401) }
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertIs<MediaServerError.SessionExpired>(adapter.librarySeries(session, library, 0, 5).errorOrNull())
            assertIs<MediaServerError.SessionExpired>(adapter.librarySeriesIds(session, library, 0, 5).errorOrNull())
        }
    }
}
