// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `PagedMediaServer` pages BoxSets and their members as the adapter does: the total on page one, `changedSince`,
 * faults.
 */
class PagedMediaServerCollectionsTest {
    private val library = Library(ItemId("lib"), "Films", LibraryKind.Movies)
    private val source = CollectionSource()
    private val server = PagedMediaServer(listOf(library), mapOf(library.id to ListLibrarySource()))
    private val address = ServerAddress(Scheme.Https, "media.example.com", 443)
    private val info = ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("u1"), AccessToken("tok"), info, address, null, false)

    init {
        (1..5).forEach { source.collections += CollectionInfo(ItemId("c$it"), "Collection $it") }
        source.members[ItemId("c1")] =
            (1..5).map { CollectionMember(ItemId("m$it"), CollectionMemberKind.Movie) }.toMutableList()
        server.collectionSource = source
    }

    @Test
    fun pagesBoxSetsWithTheTotalOnTheFirstPageOnly() =
        runBlocking {
            val first = server.collections(session, 0, 2).getOrNull()
            val last = server.collections(session, 4, 2).getOrNull()

            assertEquals(5, first?.totalCount)
            assertEquals(listOf("c1", "c2"), first?.collections?.map { it.id.value })
            assertNull(last?.totalCount)
            assertEquals(listOf("c5"), last?.collections?.map { it.id.value })
        }

    @Test
    fun aChangedSinceCallReturnsOnlyBoxSetsSavedAtOrAfterTheMoment() =
        runBlocking {
            val since = Instant.parse("2026-03-04T05:06:07Z")
            source.saved[ItemId("c2")] = since
            source.saved[ItemId("c4")] = since + 10.seconds

            val page = server.collections(session, 0, 10, since).getOrNull()

            assertEquals(listOf("c2", "c4"), page?.collections?.map { it.id.value })
            assertEquals(2, page?.totalCount)
        }

    @Test
    fun theIdListingPagesIdsWithTheTotalOnPageOne() =
        runBlocking {
            val first = server.collectionIds(session, 0, 3).getOrNull()
            val second = server.collectionIds(session, 3, 3).getOrNull()

            assertEquals(listOf("c1", "c2", "c3"), first?.ids?.map { it.value })
            assertEquals(5, first?.totalCount)
            assertEquals(listOf("c4", "c5"), second?.ids?.map { it.value })
            assertNull(second?.totalCount)
        }

    @Test
    fun membersPageWithTheTotalOnPageOneAndAnUnknownBoxSetIsEmpty() =
        runBlocking {
            val first = server.collectionMembers(session, ItemId("c1"), 0, 2).getOrNull()
            val rest = server.collectionMembers(session, ItemId("c1"), 4, 2).getOrNull()
            val none = server.collectionMembers(session, ItemId("c2"), 0, 2).getOrNull()

            assertEquals(5, first?.totalCount)
            assertEquals(listOf("m1", "m2"), first?.members?.map { it.id.value })
            assertNull(rest?.totalCount)
            assertEquals(listOf("m5"), rest?.members?.map { it.id.value })
            assertEquals(0, none?.totalCount)
        }

    @Test
    fun everyCollectionCallIsRecordedApartFromTheLibraryCalls() =
        runBlocking {
            server.collections(session, 0, 2)
            server.collectionIds(session, 0, 2)
            server.collectionMembers(session, ItemId("c1"), 0, 2)

            assertEquals(emptyList(), server.calls)
            assertEquals(
                listOf(CollectionCallKind.Listing, CollectionCallKind.Ids, CollectionCallKind.Members),
                server.collectionCalls.map { it.kind },
            )
            assertEquals(listOf(null, null, ItemId("c1")), server.collectionCalls.map { it.collection })
        }

    @Test
    fun aFaultHookCanFailAnyCollectionCall() =
        runBlocking {
            server.respondToCollections = {
                if (it.kind == CollectionCallKind.Members) MediaServerError.Timeout() else null
            }

            assertIs<MediaServerError.Timeout>(server.collectionMembers(session, ItemId("c1"), 0, 2).errorOrNull())
            assertEquals(5, server.collections(session, 0, 2).getOrNull()?.totalCount)
        }

    @Test
    fun badArgumentsAreRefusedAsTheRealPortDoes() =
        runBlocking {
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1

            assertFailsWith<IllegalArgumentException> { server.collections(session, -1, 10) }
            assertFailsWith<IllegalArgumentException> { server.collectionIds(session, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { server.collectionMembers(session, ItemId("c1"), 0, 0) }
        }
}
