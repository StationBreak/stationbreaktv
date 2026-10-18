// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** `PagedMediaServer` pages a library's series the way the adapter does: the total on page one, `changedSince`, faults. */
class PagedMediaServerSeriesTest {
    private val library = Library(ItemId("lib"), "Shows", LibraryKind.TvShows)
    private val source = SeriesSource()
    private val server = PagedMediaServer(listOf(library), mapOf(library.id to ListLibrarySource()))
    private val address = ServerAddress(Scheme.Https, "media.example.com", 443)
    private val session =
        Session(UserId("u1"), AccessToken("tok"), ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)), address, null, false)

    init {
        (1..5).forEach { source.series += SeriesInfo(ItemId("s$it"), "Series $it", null, emptyList(), emptyList()) }
        server.seriesSources[library.id] = source
    }

    @Test
    fun pagesSeriesWithTheTotalOnTheFirstPageOnly() =
        runBlocking {
            val first = server.librarySeries(session, library, 0, 2).getOrNull()
            val last = server.librarySeries(session, library, 4, 2).getOrNull()

            assertEquals(5, first?.totalCount)
            assertEquals(listOf("s1", "s2"), first?.series?.map { it.id.value })
            assertNull(last?.totalCount)
            assertEquals(listOf("s5"), last?.series?.map { it.id.value })
        }

    @Test
    fun aChangedSinceCallReturnsOnlySeriesSavedAtOrAfterTheMoment() =
        runBlocking {
            val since = Instant.parse("2026-03-04T05:06:07Z")
            source.saved[ItemId("s2")] = since
            source.saved[ItemId("s4")] = since + 1.seconds

            val page = server.librarySeries(session, library, 0, 10, since).getOrNull()

            assertEquals(listOf("s2", "s4"), page?.series?.map { it.id.value })
            assertEquals(2, page?.totalCount)
        }

    @Test
    fun theIdListingNamesEverySeriesIdAndRespondCanFailIt() =
        runBlocking {
            val ids =
                server
                    .librarySeriesIds(session, library, 0, 3)
                    .getOrNull()
                    ?.ids
                    ?.map { it.value }
            assertEquals(listOf("s1", "s2", "s3"), ids)

            server.respond = { MediaServerError.Timeout() }

            assertEquals(ServerResult.Failure(MediaServerError.Timeout()), server.librarySeriesIds(session, library, 0, 3))
            assertEquals(ServerResult.Failure(MediaServerError.Timeout()), server.librarySeries(session, library, 0, 3))
        }

    @Test
    fun seriesCallsAreRecordedApartFromTheItemCalls() =
        runBlocking {
            server.librarySeries(session, library, 0, 2)
            server.librarySeriesIds(session, library, 0, 2)

            assertEquals(2, server.calls.size)
            assertEquals(emptyList(), server.itemCalls)
            assertEquals(listOf(false, true), server.calls.map { it.idsOnly })
            assertEquals(listOf(true, true), server.calls.map { it.series })
        }
}
