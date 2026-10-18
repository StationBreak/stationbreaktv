// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.ItemId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What the collection calls ask the server for (spec 10, Unit 2). BoxSets are listed recursively with no parent;
 * members are the BoxSet's direct children and never a recursive call, which would flatten nested BoxSets. Every call
 * is paged and sorted, asks for no images, no user data and no extra fields, and asks for the total on the first page
 * only (Jellyfin issue #18069). Jellyfin reads query names without case.
 */
class CollectionRequestShapeTest {
    private val jellyfin = FakeJellyfin()
    private val box = ItemId("box01")
    private val empty = "{\"Items\":[],\"TotalRecordCount\":0}"

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun listing(
        startIndex: Int = 0,
        limit: Int = 50,
        changedSince: Instant? = null,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(empty) }
            testAdapter().collections(testSession(jellyfin.address), startIndex, limit, changedSince)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun ids(
        startIndex: Int = 0,
        limit: Int = 50,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(empty) }
            testAdapter().collectionIds(testSession(jellyfin.address), startIndex, limit)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun members(
        startIndex: Int = 0,
        limit: Int = 50,
    ): HttpUrl {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(empty) }
            testAdapter().collectionMembers(testSession(jellyfin.address), box, startIndex, limit)
        }
        return jellyfin.requests("/Items").last().url
    }

    private fun HttpUrl.listOf(name: String): List<String> = queryParameter(name).orEmpty().split(',')

    private fun everyCall() = listOf(listing(), ids(), members())

    @Test
    fun theBoxSetListingIsRecursiveForBoxSetsWithNoParent() {
        val url = listing()

        assertEquals("true", url.queryParameter("recursive"))
        assertEquals(listOf("BoxSet"), url.listOf("includeItemTypes"))
        assertNull(url.queryParameter("parentId"))
    }

    @Test
    fun theBoxSetIdListingIsTheSameQueryWithNothingMoreAsked() {
        val url = ids()

        assertEquals("true", url.queryParameter("recursive"))
        assertEquals(listOf("BoxSet"), url.listOf("includeItemTypes"))
        assertNull(url.queryParameter("parentId"))
        assertNull(url.queryParameter("minDateLastSaved"))
    }

    @Test
    fun membersAreTheBoxSetsDirectChildrenAndNeverRecursive() {
        val url = members()

        assertEquals("box01", url.queryParameter("parentId"))
        assertNull(url.queryParameter("recursive"))
        assertNull(url.queryParameter("includeItemTypes"))
    }

    @Test
    fun noMemberCallEverSendsRecursiveTrue() {
        for (page in listOf(0, 50, 400)) {
            assertNull(members(startIndex = page).queryParameter("recursive"))
        }
        assertEquals(
            emptyList(),
            jellyfin.requests("/Items").filter {
                it.url.queryParameter("recursive") == "true" &&
                    it.url.queryParameter("parentId") != null
            },
        )
    }

    @Test
    fun everyCallIsSortedByNameAscending() {
        for (url in everyCall()) {
            assertEquals(listOf("SortName"), url.listOf("sortBy"))
            assertEquals("Ascending", url.queryParameter("sortOrder"))
        }
    }

    @Test
    fun everyCallAsksForNoImagesNoUserDataAndNoExtraFields() {
        for (url in everyCall()) {
            assertEquals("false", url.queryParameter("enableImages"))
            assertEquals("false", url.queryParameter("enableUserData"))
            assertNull(url.queryParameter("fields"))
        }
    }

    @Test
    fun everyCallPassesTheOffsetAndLimitThrough() {
        val urls = listOf(listing(150, 25), ids(150, 25), members(150, 25))

        for (url in urls) {
            assertEquals("150", url.queryParameter("startIndex"))
            assertEquals("25", url.queryParameter("limit"))
        }
    }

    @Test
    fun everyCallAsksForTheTotalOnTheFirstPageOnly() {
        assertEquals(
            listOf("true", "true", "true"),
            listOf(listing(0), ids(0), members(0)).map { it.queryParameter("enableTotalRecordCount") },
        )
        assertEquals(
            listOf("false", "false", "false"),
            listOf(listing(200), ids(200), members(200)).map {
                it.queryParameter("enableTotalRecordCount")
            },
        )
    }

    @Test
    fun sendsNoChangedSinceFilterUnlessAMomentIsGiven() {
        assertNull(listing().queryParameter("minDateLastSaved"))
        assertNull(ids().queryParameter("minDateLastSaved"))
        assertNull(members().queryParameter("minDateLastSaved"))
    }

    @Test
    fun sendsTheChangedSinceMomentInUtcCutToMilliseconds() {
        val url = listing(changedSince = Instant.parse("2026-03-04T05:06:07.123456789Z"))

        assertEquals("2026-03-04T05:06:07.123Z", url.queryParameter("minDateLastSaved"))
    }

    @Test
    fun badArgumentsThrowBeforeAnythingIsSent() {
        runBlocking {
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)
            val tooMany = MediaServer.MAX_PAGE_SIZE + 1

            assertFailsWith<IllegalArgumentException> { adapter.collections(session, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.collections(session, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.collections(session, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionIds(session, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionIds(session, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionIds(session, 0, tooMany) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionMembers(session, box, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionMembers(session, box, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.collectionMembers(session, box, 0, tooMany) }
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun aRejectedTokenIsSessionExpiredForEveryCall() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.status(401) }
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertIs<MediaServerError.SessionExpired>(adapter.collections(session, 0, 5).errorOrNull())
            assertIs<MediaServerError.SessionExpired>(adapter.collectionIds(session, 0, 5).errorOrNull())
            assertIs<MediaServerError.SessionExpired>(adapter.collectionMembers(session, box, 0, 5).errorOrNull())
        }
    }
}
