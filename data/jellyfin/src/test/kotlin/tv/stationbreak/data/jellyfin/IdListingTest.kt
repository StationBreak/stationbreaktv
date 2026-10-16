// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `libraryItemIds`: only IDs, in pages, for the deletion check (F0.6). */
class IdListingTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun asksForIdsOnlyWithTheSameScopeAsTheItemCall() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"TotalRecordCount\":0}") }

            testAdapter().libraryItemIds(testSession(jellyfin.address), library, 0, 100)

            val url = jellyfin.requests("/Items").single().url
            assertEquals("true", url.queryParameter("recursive"))
            assertEquals("lib01", url.queryParameter("parentId"))
            assertEquals("Movie,Episode,Video,MusicVideo", url.queryParameter("includeItemTypes"))
            assertEquals("SortName", url.queryParameter("sortBy"))
            assertEquals("Ascending", url.queryParameter("sortOrder"))
            assertEquals("0", url.queryParameter("startIndex"))
            assertEquals("100", url.queryParameter("limit"))
            assertEquals("true", url.queryParameter("enableTotalRecordCount"))
            // No fields beyond the always-present ones, and no change filter: this is the whole list.
            assertNull(url.queryParameter("fields"))
            assertNull(url.queryParameter("minDateLastSaved"))
        }
    }

    @Test
    fun returnsEveryIdOfALibraryInPagesAndNothingElse() {
        runBlocking {
            val all = (1..7).map { "id%02d".format(java.util.Locale.ROOT, it) }
            jellyfin.on("GET", "/Items") { request ->
                val start = request.url.queryParameter("startIndex")!!.toInt()
                val limit = request.url.queryParameter("limit")!!.toInt()
                val slice = all.drop(start).take(limit)
                val items = slice.joinToString(",") { "{\"Id\":\"$it\",\"Name\":\"secret title\",\"Type\":\"Movie\"}" }
                jellyfin.json("{\"Items\":[$items],\"TotalRecordCount\":${all.size},\"StartIndex\":$start}")
            }
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            val seen = mutableListOf<ItemId>()
            var start = 0
            var total: Int? = null
            while (true) {
                val page = adapter.libraryItemIds(session, library, start, 3).getOrNull() ?: error("page failed")
                if (start == 0) total = page.totalCount else assertNull(page.totalCount)
                assertEquals(start, page.startIndex)
                seen += page.ids
                if (page.ids.size < 3) break
                start += page.ids.size
            }

            assertEquals(all.map(::ItemId), seen)
            assertEquals(all.size, total)
        }
    }

    @Test
    fun aBlankOrUnsafeIdIsNotJellyfin() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[{\"Id\":\"../x\"}],\"TotalRecordCount\":1}") }

            val error = testAdapter().libraryItemIds(testSession(jellyfin.address), library, 0, 5).errorOrNull()

            assertIs<MediaServerError.NotJellyfin>(error)
        }
    }

    @Test
    fun anItemWithNoIdAtAllIsNotJellyfin() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[{\"Name\":\"x\"}],\"TotalRecordCount\":1}") }

            val error = testAdapter().libraryItemIds(testSession(jellyfin.address), library, 0, 5).errorOrNull()

            assertIs<MediaServerError.NotJellyfin>(error)
        }
    }
}
