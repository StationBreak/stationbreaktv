// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** `itemDetails`: the overview, year, genres and runtime of one item, with the server's text made safe. */
class ItemDetailsCallTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun ok(body: String): ItemDetails =
        runBlocking {
            jellyfin.on("GET", "/Items/i1") { jellyfin.json(body) }
            val result = testAdapter().itemDetails(testSession(jellyfin.address), ItemId("i1"))
            (result as ServerResult.Success).value
        }

    /** A direction control by code point: the formatter turns a `\\u` escape into the raw character. */
    private fun bidi(code: Int) = code.toChar().toString()

    /** A JSON string literal for [text], quotes included. */
    private fun literal(text: String) = JsonPrimitive(text).toString()

    @Test
    fun parsesOverviewYearGenresRuntime() {
        val result =
            ok(
                """{"Id":"i1","Overview":"A made-up story.","ProductionYear":1999,"Genres":["Drama","Comedy"],
                "RunTimeTicks":36000000000}""",
            )

        assertEquals(ItemDetails(ItemId("i1"), "A made-up story.", 1999, listOf("Drama", "Comedy"), 60.minutes), result)
        val request = jellyfin.requests("/Items/i1").single()
        assertEquals("user1", request.url.queryParameter("userId"))
    }

    @Test
    fun stripsHtmlTags() {
        val result = ok("""{"Id":"i1","Overview":"<p>One &amp; two<br>three<br/>four <b>bold</b> &lt;x&gt;</p>"}""")

        assertEquals("One & two\nthree\nfour bold <x>", result.overview)
    }

    @Test
    fun stripsControlAndBidiCharacters() {
        val raw =
            "a\u0000b\u0007c\u0085d\u009fe" + bidi(0x202E) + "f" + bidi(0x202A) + "g" +
                bidi(0x2066) + "h" + bidi(0x2069) + "i\nj"
        val result = ok("""{"Id":"i1","Overview":${literal(raw)}}""")

        assertEquals("abcdefghi\nj", result.overview)
        assertFalse(result.overview.orEmpty().any { it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 })
    }

    @Test
    fun capsOverviewAndGenres() {
        val genres = (1..8).joinToString(",") { "\"" + "g$it".padEnd(60, 'x') + "\"" }
        val result = ok("""{"Id":"i1","Overview":"${"y".repeat(5000)}","Genres":[$genres]}""")

        assertEquals(1500, result.overview?.length)
        assertEquals(5, result.genres.size)
        assertEquals(listOf(40), result.genres.map { it.length }.distinct())
    }

    @Test
    fun serverErrorIsAFailureResult() {
        runBlocking {
            jellyfin.on("GET", "/Items/i1") { jellyfin.status(500) }

            val error = testAdapter().itemDetails(testSession(jellyfin.address), ItemId("i1")).errorOrNull()

            assertIs<MediaServerError.ServerError>(error)
        }
    }

    @Test
    fun episodeWithoutGenresIsEmptyList() {
        val result = ok("""{"Id":"i1","Name":"Pilot"}""")

        assertEquals(emptyList(), result.genres)
        assertNull(result.overview)
        assertNull(result.year)
        assertNull(result.runtime)
    }
}
