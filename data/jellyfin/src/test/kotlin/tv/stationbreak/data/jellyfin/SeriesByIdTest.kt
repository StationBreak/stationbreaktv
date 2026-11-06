// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.SeriesInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `seriesById` reads one series straight by its id (`GET /Users/{userId}/Items/{id}`), which still answers for a series
 * the server's series lists leave out (issue 604). A missing item, or one that is not a series, is "no such series".
 */
class SeriesByIdTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun fetch(
        status: () -> mockwebserver3.MockResponse,
        id: String = "h1",
    ): ServerResult<SeriesInfo?> =
        runBlocking {
            jellyfin.on("GET", "/Users/user1/Items/$id") { status() }
            testAdapter().seriesById(testSession(jellyfin.address), ItemId(id))
        }

    @Test
    fun asksForTheItemByIdWithTheSeriesFields() {
        fetch({ jellyfin.json("""{"Id":"h1","Name":"Night Shift","Type":"Series"}""") })

        val url = jellyfin.requests("/Users/user1/Items/h1").single().url
        assertEquals(listOf("Genres", "Tags", "Studios"), url.queryParameter("fields").orEmpty().split(','))
    }

    @Test
    fun decodesTheSameInfoTheListProduces() {
        val body =
            """{"Id":"h1","Name":"Night Shift","Type":"Series","OfficialRating":"TV-14",
            "Genres":["Drama"],"Tags":["slow"],"Studios":[{"Name":"Acme"}]}"""

        val found = fetch({ jellyfin.json(body) }).getOrNull()

        assertEquals(
            SeriesInfo(ItemId("h1"), "Night Shift", "TV-14", listOf("Drama"), listOf("slow"), listOf("Acme")),
            found,
        )
    }

    @Test
    fun aMissingItemIsNullNotAnError() {
        val result = fetch({ jellyfin.status(404) })

        assertIs<ServerResult.Success<SeriesInfo?>>(result)
        assertNull(result.value)
    }

    @Test
    fun anItemThatIsNotASeriesIsNull() {
        val result = fetch({ jellyfin.json("""{"Id":"h1","Name":"A Film","Type":"Movie"}""") })

        assertIs<ServerResult.Success<SeriesInfo?>>(result)
        assertNull(result.value)
    }

    @Test
    fun aServerFailureStaysAFailure() {
        val error = fetch({ jellyfin.status(500) }).errorOrNull()

        assertIs<MediaServerError.ServerError>(error)
    }
}
