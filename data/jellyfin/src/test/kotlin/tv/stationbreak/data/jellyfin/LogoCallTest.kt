// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okio.Buffer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `logo`: an item's logo image, fetched with the session's token, bounded in size and kind. */
class LogoCallTest {
    private val jellyfin = FakeJellyfin()
    private val logo = ItemLogo(ItemId("series1"), "abc123")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun image(
        bytes: ByteArray,
        type: String = "image/png",
        code: Int = 200,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", type)
            .body(Buffer().write(bytes))
            .build()

    private fun fetch(
        token: String = fake("token"),
        maxHeight: Int = 192,
    ) = runBlocking { testAdapter().logo(testSession(jellyfin.address, token), logo, maxHeight) }

    @Test
    fun theBytesComeBackAndTheRequestCarriesTheTokenButNotInTheUrl() {
        val token = fake("token")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        jellyfin.on("GET", "/Items/series1/Images/Logo") { image(png) }

        val result = fetch(token)

        assertContentEquals(png, result.getOrNull())
        val request = jellyfin.requests("/Items/series1/Images/Logo").single()
        assertEquals("abc123", request.url.queryParameter("tag"))
        assertEquals("192", request.url.queryParameter("maxHeight"))
        assertEquals("image/*", request.headers["Accept"])
        assertTrue(request.headers["Authorization"]!!.contains("Token=\"$token\""))
        assertFalse(token in request.url.toString())
    }

    @Test
    fun theHeightAskedForIsKeptInRange() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { image(byteArrayOf(1)) }

        fetch(maxHeight = 50_000)
        fetch(maxHeight = 0)

        val asked = jellyfin.requests("/Items/series1/Images/Logo").map { it.url.queryParameter("maxHeight") }
        assertEquals(listOf("1024", "1"), asked)
    }

    @Test
    fun anAnswerThatIsNotAnImageIsRefused() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { image(byteArrayOf(1, 2, 3), type = "text/html") }

        assertIs<MediaServerError.ServerError>(fetch().errorOrNull())
    }

    @Test
    fun anAnswerWithNoContentTypeIsRefused() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") {
            MockResponse
                .Builder()
                .code(200)
                .body(Buffer().write(byteArrayOf(1, 2, 3)))
                .build()
        }

        assertIs<MediaServerError.ServerError>(fetch().errorOrNull())
    }

    @Test
    fun anAnswerOverTheSizeCapIsRefusedAndOneAtTheCapIsKept() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { image(ByteArray(MediaServer.MAX_LOGO_BYTES + 1)) }
        assertIs<MediaServerError.ServerError>(fetch().errorOrNull())

        jellyfin.on("GET", "/Items/series1/Images/Logo") { image(ByteArray(MediaServer.MAX_LOGO_BYTES)) }
        assertEquals(MediaServer.MAX_LOGO_BYTES, fetch().getOrNull()?.size)
    }

    @Test
    fun aMissingLogoIsAFailureNotAnEmptySuccess() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { jellyfin.status(404) }

        assertIs<ServerResult.Failure>(fetch())
    }

    @Test
    fun aRedirectIsNotFollowedAndTheTokenGoesNowhere() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { jellyfin.redirect("http://elsewhere.example/logo.png") }

        assertIs<MediaServerError.Redirected>(fetch().errorOrNull())
        assertEquals(1, jellyfin.requests("/Items/series1/Images/Logo").size)
    }

    @Test
    fun aRefusedTokenIsAnExpiredSession() {
        jellyfin.on("GET", "/Items/series1/Images/Logo") { jellyfin.status(401) }

        assertIs<MediaServerError.SessionExpired>(fetch().errorOrNull())
    }
}
