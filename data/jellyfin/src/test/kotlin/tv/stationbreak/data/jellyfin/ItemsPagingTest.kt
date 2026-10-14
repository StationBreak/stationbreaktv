// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.ItemFields
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `items(session, library, startIndex, limit)`: a page of minimal items, decoded without tripping on the rest. */
class ItemsPagingTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Films", LibraryKind.Movies)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing fixture $name" }.use { String(it.readBytes()) }

    @Test
    fun returnsTheTotalAndAMinimalItem() {
        runBlocking {
            jellyfin.on("GET", "/Items") {
                jellyfin.json(
                    "{\"Items\":[{\"Id\":\"i1\",\"Name\":\"Heat\",\"Type\":\"Movie\",\"RunTimeTicks\":36000000000," +
                        "\"ParentId\":\"lib01\"}],\"TotalRecordCount\":42,\"StartIndex\":10}",
                )
            }

            val page = testAdapter().items(testSession(jellyfin.address), library, 10, 1).getOrNull()

            assertEquals(
                ItemPage(42, 10, listOf(MediaItem(ItemId("i1"), "Heat", ItemKind.Movie, 60.minutes, ItemId("lib01")))),
                page,
            )
        }
    }

    @Test
    fun passesTheOffsetTheLimitAndTheLibraryThrough() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"TotalRecordCount\":0,\"StartIndex\":50}") }

            testAdapter().items(testSession(jellyfin.address), library, 50, 25)

            val url = jellyfin.requests("/Items").single().url
            assertEquals("50", url.queryParameter("startIndex"))
            assertEquals("25", url.queryParameter("limit"))
            assertEquals("lib01", url.queryParameter("parentId"))
        }
    }

    @Test
    fun aResponseCarryingEveryItemFieldsValueDecodes() {
        runBlocking {
            val body = fixture("items-all-fields.json")
            for (field in ItemFields.entries) {
                assertTrue("\"${field.serialName}\"" in body, "the fixture lacks ${field.serialName}")
            }
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }

            val page = testAdapter().items(testSession(jellyfin.address), library, 0, 5).getOrNull()

            assertEquals(listOf("Heat", "Friends", "Something new"), page?.items?.map { it.name })
            assertEquals(listOf(ItemKind.Movie, ItemKind.Episode, ItemKind.Other), page?.items?.map { it.kind })
            assertEquals(3, page?.totalCount)
        }
    }

    @Test
    fun anItemWithNoRuntimeOrParentHasNone() {
        runBlocking {
            jellyfin.on("GET", "/Items") {
                jellyfin.json(
                    "{\"Items\":[{\"Id\":\"i2\",\"Name\":\"Folder\",\"Type\":\"Folder\"}],\"TotalRecordCount\":1}",
                )
            }

            val item =
                testAdapter()
                    .items(testSession(jellyfin.address), library, 0, 5)
                    .getOrNull()
                    ?.items
                    ?.single()

            assertEquals(MediaItem(ItemId("i2"), "Folder", ItemKind.Folder, null, null), item)
        }
    }

    @Test
    fun aSubSecondRuntimeKeepsItsPrecision() {
        runBlocking {
            jellyfin.on("GET", "/Items") {
                jellyfin.json(
                    "{\"Items\":[{\"Id\":\"i3\",\"Name\":\"Short\",\"Type\":\"Video\",\"RunTimeTicks\":15000000}]}",
                )
            }

            val item =
                testAdapter()
                    .items(testSession(jellyfin.address), library, 0, 5)
                    .getOrNull()
                    ?.items
                    ?.single()

            assertEquals(1.5.seconds, item?.runtime)
        }
    }

    @Test
    fun anIdThatCouldEscapeThePathIsNotAnItem() {
        runBlocking {
            jellyfin.on("GET", "/Items") {
                jellyfin.json("{\"Items\":[{\"Id\":\"../Users\",\"Name\":\"x\",\"Type\":\"Movie\"}]}")
            }

            val error = testAdapter().items(testSession(jellyfin.address), library, 0, 5).errorOrNull()

            assertIs<MediaServerError.NotJellyfin>(error)
        }
    }

    @Test
    fun anOffsetBelowZeroOrALimitOutsideTheRangeIsRefusedBeforeAnythingIsSent() {
        runBlocking {
            val adapter = testAdapter()
            val session = testSession(jellyfin.address)

            assertFailsWith<IllegalArgumentException> { adapter.items(session, library, -1, 10) }
            assertFailsWith<IllegalArgumentException> { adapter.items(session, library, 0, 0) }
            assertFailsWith<IllegalArgumentException> { adapter.items(session, library, 0, 100_000) }
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }

    @Test
    fun aRejectedTokenIsSessionExpired() {
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.status(401) }

            assertIs<MediaServerError.SessionExpired>(
                testAdapter().items(testSession(jellyfin.address), library, 0, 5).errorOrNull(),
            )
        }
    }
}
