// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
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

/** A bad argument is a bug in the caller, so it throws before anything is sent; a server problem is a result. */
class ItemsArgumentTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun libraryItemsRefusesABadOffsetOrLimitBeforeSendingAnything() {
        runBlocking {
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertFailsWith<IllegalArgumentException> { adapter.libraryItems(session, library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.libraryItems(session, library, 0, 0) }
            assertFailsWith<IllegalArgumentException> {
                adapter.libraryItems(session, library, 0, MediaServer.MAX_PAGE_SIZE + 1)
            }
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun libraryItemIdsRefusesABadOffsetOrLimitBeforeSendingAnything() {
        runBlocking {
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertFailsWith<IllegalArgumentException> { adapter.libraryItemIds(session, library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.libraryItemIds(session, library, 0, 0) }
            assertFailsWith<IllegalArgumentException> {
                adapter.libraryItemIds(session, library, 0, MediaServer.MAX_PAGE_SIZE + 1)
            }
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun theLargestAllowedPageIsAccepted() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"TotalRecordCount\":0}") }

            val session = testSession(jellyfin.address)
            val result = testAdapter().libraryItems(session, library, 0, MediaServer.MAX_PAGE_SIZE)

            assertEquals(null, result.errorOrNull())
        }
    }

    @Test
    fun aRejectedTokenIsSessionExpiredForBothCalls() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.status(401) }
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertIs<MediaServerError.SessionExpired>(adapter.libraryItems(session, library, 0, 5).errorOrNull())
            assertIs<MediaServerError.SessionExpired>(adapter.libraryItemIds(session, library, 0, 5).errorOrNull())
        }
    }
}
