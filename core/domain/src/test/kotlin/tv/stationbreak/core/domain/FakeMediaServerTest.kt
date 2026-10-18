// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FakeMediaServerTest {
    private val address = ServerAddress(Scheme.Https, "media.example.com", 443)
    private val resolved = ResolvedServer(address, ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)))

    @Test
    fun answersWithWhatItWasToldAndRecordsTheCalls() =
        runBlocking {
            val fake = FakeMediaServer()
            fake.discoverResult = ServerResult.Success(resolved)
            fake.connectResult = ServerResult.Failure(MediaServerError.Timeout())

            assertEquals(ServerResult.Success(resolved), fake.discover("media.example.com"))
            val acknowledgement = CleartextAcknowledgement(address)
            assertEquals(ServerResult.Failure(MediaServerError.Timeout()), fake.connect(address, acknowledgement))

            assertEquals(listOf("media.example.com"), fake.discoverCalls)
            val expectedCalls: List<Pair<ServerAddress, CleartextAcknowledgement?>> = listOf(address to acknowledgement)
            assertEquals(expectedCalls, fake.connectCalls)
        }

    private fun session() = Session(UserId("u1"), AccessToken("tok"), resolved.server, address, null, false)

    @Test
    fun recordsTheMediaCallsAndAnswersWithWhatItWasToldInOrder() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)
            val page = ItemPage(1, 0, listOf(MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, null, null)))
            fake.librariesResult = ServerResult.Success(listOf(library))
            fake.itemsResult = ServerResult.Success(page)
            val report = PlaybackReport(ItemId("i1"), MediaSourceId("s1"), null, 3.seconds)

            assertEquals(ServerResult.Success(listOf(library)), fake.libraries(session()))
            assertEquals(ServerResult.Success(page), fake.items(session(), library, 0, 10))
            fake.reportStart(session(), report)
            fake.reportProgress(session(), report)
            fake.reportStopped(session(), report)

            assertEquals(listOf(Triple(library, 0, 10)), fake.itemsCalls)
            assertEquals(listOf("start", "progress", "stopped"), fake.reports.map { it.first })
        }
    }

    @Test
    fun refusesABadPageRequestAsTheRealPortDoes() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)

            assertFailsWith<IllegalArgumentException> { fake.items(session(), library, -1, 10) }
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1
            assertFailsWith<IllegalArgumentException> { fake.items(session(), library, 0, tooMany) }
        }
    }

    @Test
    fun recordsTheCacheCallsAndAnswersWithWhatItWasTold() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)
            val page = LibraryItemsPage(0, 1, listOf(MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, null, null)))
            val ids = ItemIdPage(0, 1, listOf(ItemId("i1")))
            val since = Instant.parse("2026-03-04T05:06:07Z")
            fake.libraryItemsResult = ServerResult.Success(page)
            fake.libraryItemIdsResult = ServerResult.Success(ids)

            assertEquals(ServerResult.Success(page), fake.libraryItems(session(), library, 0, 10, since))
            assertEquals(ServerResult.Success(page), fake.libraryItems(session(), library, 10, 20))
            assertEquals(ServerResult.Success(ids), fake.libraryItemIds(session(), library, 5, 7))

            assertEquals(
                listOf(LibraryItemsCall(library, 0, 10, since), LibraryItemsCall(library, 10, 20, null)),
                fake.libraryItemsCalls,
            )
            assertEquals(listOf(Triple(library, 5, 7)), fake.libraryItemIdsCalls)
        }
    }

    @Test
    fun refusesABadCachePageRequestAsTheRealPortDoes() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1

            assertFailsWith<IllegalArgumentException> { fake.libraryItems(session(), library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { fake.libraryItems(session(), library, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { fake.libraryItemIds(session(), library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { fake.libraryItemIds(session(), library, 0, 0) }
        }
    }

    @Test
    fun answersTheSeriesCallsWithWhatItWasToldAndRecordsThem() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Shows", LibraryKind.TvShows)
            val page = SeriesPage(0, 1, listOf(SeriesInfo(ItemId("s1"), "Heat", null, listOf("Crime"), emptyList())))
            val ids = ItemIdPage(0, 1, listOf(ItemId("s1")))
            val since = Instant.parse("2026-03-04T05:06:07Z")
            fake.librarySeriesResult = ServerResult.Success(page)
            fake.librarySeriesIdsResult = ServerResult.Success(ids)

            assertEquals(ServerResult.Success(page), fake.librarySeries(session(), library, 0, 10, since))
            assertEquals(ServerResult.Success(page), fake.librarySeries(session(), library, 10, 20))
            assertEquals(ServerResult.Success(ids), fake.librarySeriesIds(session(), library, 5, 7))

            assertEquals(
                listOf(LibraryItemsCall(library, 0, 10, since), LibraryItemsCall(library, 10, 20, null)),
                fake.librarySeriesCalls,
            )
            assertEquals(listOf(Triple(library, 5, 7)), fake.librarySeriesIdsCalls)
        }
    }

    @Test
    fun refusesABadSeriesPageRequestAsTheRealPortDoes() {
        runBlocking {
            val fake = FakeMediaServer()
            val library = Library(ItemId("lib"), "Shows", LibraryKind.TvShows)
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1

            assertFailsWith<IllegalArgumentException> { fake.librarySeries(session(), library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { fake.librarySeries(session(), library, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { fake.librarySeriesIds(session(), library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { fake.librarySeriesIds(session(), library, 0, 0) }
        }
    }
}
