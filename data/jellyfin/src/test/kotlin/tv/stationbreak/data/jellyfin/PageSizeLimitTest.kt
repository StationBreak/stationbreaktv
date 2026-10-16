// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Open Question 4: is a 500-item page safe inside the adapter's response limit? The adapter reads at most
 * [MAX_BODY_BYTES] of an answer. This measures a 500-item page of the fields the cache asks for, and the page the
 * adapter will accept today, and records what it found: the cap stays at 200 unless there is room to spare.
 */
class PageSizeLimitTest {
    private val jellyfin = FakeJellyfin()
    private val synthetic = SyntheticLibrary(size = 2_000, seed = 1)
    private val library = Library(synthetic.libraryId, "Generated", LibraryKind.Mixed)
    private val fields = setOf("DateCreated", "DateLastSaved", "Genres", "Tags", "ParentId")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun pageBytes(count: Int): Int {
        val items = synthetic.page(0, count).map { wireItem(it, fields) }
        val body = buildJsonObject { put("Items", JsonArray(items)) }
        return body.toString().toByteArray().size
    }

    @Test
    fun aFiveHundredItemPageOfTheRequestedFieldsFitsWithRoomToSpare() {
        val bytes = pageBytes(500)

        // Half the limit is the bar for "room to spare": real servers write longer names and more genres than this.
        assertTrue(bytes < MAX_BODY_BYTES / 2, "a 500-item page is $bytes bytes against a limit of $MAX_BODY_BYTES")
    }

    @Test
    fun theLargestPageTheAdapterAllowsIsFarInsideTheLimit() {
        val bytes = pageBytes(MediaServer.MAX_PAGE_SIZE)

        assertTrue(bytes < MAX_BODY_BYTES / 4, "a ${MediaServer.MAX_PAGE_SIZE}-item page is $bytes bytes")
    }

    @Test
    fun aServerThatAnswersWithFiveHundredItemsIsStillReadInFull() {
        runBlocking {
            jellyfin.serve(synthetic)
            jellyfin.on("GET", "/Items") {
                val items = synthetic.page(0, 500).map { wireItem(it, fields) }
                jellyfin.json(
                    buildJsonObject {
                        put("Items", JsonArray(items))
                        put("TotalRecordCount", 2_000)
                    }.toString(),
                )
            }

            val page = testAdapter().libraryItems(testSession(jellyfin.address), library, 0, MediaServer.MAX_PAGE_SIZE)

            assertEquals(500, page.getOrNull()?.items?.size)
        }
    }

    @Test
    fun anAnswerOverTheLimitIsAServerErrorNotAnOutOfMemory() {
        runBlocking {
            val pad = "x".repeat(MAX_BODY_BYTES)
            jellyfin.on("GET", "/Items") { jellyfin.json("{\"Items\":[],\"Pad\":\"$pad\"}") }

            val error = testAdapter().libraryItems(testSession(jellyfin.address), library, 0, 10).errorOrNull()

            assertIs<MediaServerError.ServerError>(error)
        }
    }
}
