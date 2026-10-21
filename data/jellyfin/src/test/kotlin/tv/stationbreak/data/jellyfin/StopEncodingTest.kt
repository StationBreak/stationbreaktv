// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.PlaySessionId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StopEncodingTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun stopEncodingDeletesTheActiveEncoding() {
        runBlocking {
            val token = fake("token")
            jellyfin.on("DELETE", "/Videos/ActiveEncodings") { jellyfin.status(204) }

            val result = testAdapter().stopEncoding(testSession(jellyfin.address, token), PlaySessionId("play-1"))

            assertEquals(ServerResult.Success(Unit), result)
            val request = jellyfin.requests("/Videos/ActiveEncodings").single()
            assertEquals("DELETE", request.method)
            assertEquals(TEST_DEVICE_ID, request.url.queryParameter("DeviceId"))
            assertEquals("play-1", request.url.queryParameter("PlaySessionId"))
            assertFalse(token in request.url.toString())
            assertTrue(request.headers["Authorization"]!!.contains("Token=\"$token\""))
        }
    }

    @Test
    fun aMissingEncodingIsSuccess() {
        runBlocking {
            jellyfin.on("DELETE", "/Videos/ActiveEncodings") { jellyfin.status(404) }

            val result = testAdapter().stopEncoding(testSession(jellyfin.address), PlaySessionId("play-1"))

            assertEquals(ServerResult.Success(Unit), result)
        }
    }

    @Test
    fun otherFailuresStayFailures() {
        runBlocking {
            jellyfin.on("DELETE", "/Videos/ActiveEncodings") { jellyfin.status(500) }

            val result = testAdapter().stopEncoding(testSession(jellyfin.address), PlaySessionId("play-1"))

            assertIs<MediaServerError.ServerError>(result.errorOrNull())
        }
    }
}
