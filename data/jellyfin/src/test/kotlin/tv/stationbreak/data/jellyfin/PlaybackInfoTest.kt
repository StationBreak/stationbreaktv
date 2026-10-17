// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaySessionId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** `playbackInfo(session, item)`: a static conservative device profile out, media sources and a URL builder back. */
class PlaybackInfoTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private val response =
        "{\"MediaSources\":[{\"Id\":\"src1\",\"Name\":\"Heat\",\"Container\":\"mkv\",\"RunTimeTicks\":36000000000," +
            "\"SupportsDirectPlay\":true,\"SupportsDirectStream\":true}]," +
            "\"PlaySessionId\":\"play-1\"}"

    @Test
    fun postsAConservativeDeviceProfileToTheItem() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(response) }

            testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1"))

            val request = jellyfin.requests("/PlaybackInfo").single()
            assertEquals("POST", request.method)
            assertEquals("/Items/i1/PlaybackInfo", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            val profile = body["DeviceProfile"]!!.jsonObject
            assertTrue((profile["DirectPlayProfiles"] as JsonArray).isNotEmpty())
        }
    }

    @Test
    fun aSourceRuntimeIsFloorDivOfTheTicksInMilliseconds() {
        runBlocking {
            val body = response.replace("36000000000", "36000009999")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(body) }

            val info = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).getOrNull()

            assertEquals(
                3_600_000L,
                info
                    ?.sources
                    ?.single()
                    ?.runtime
                    ?.inWholeMilliseconds,
            )
        }
    }

    @Test
    fun theRequestBodyAndUrlCarryNoCredential() {
        runBlocking {
            val token = fake("token")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(response) }

            testAdapter().playbackInfo(testSession(jellyfin.address, token), ItemId("i1"))

            val request = jellyfin.requests("/PlaybackInfo").single()
            assertFalse(token in request.body!!.utf8())
            assertFalse(token in request.url.toString())
            assertTrue(request.headers["Authorization"]!!.contains("Token=\"$token\""))
        }
    }

    @Test
    fun theProfileIsPlainJsonThatNamesWhatItIs() {
        val parsed = Json.parseToJsonElement(PlaybackProfile.JSON)

        assertIs<JsonObject>(parsed)
        assertNotNull(parsed["Name"])
    }

    @Test
    fun returnsTheMediaSourcesAndThePlaySession() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(response) }

            val info = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).getOrNull()

            assertEquals(ItemId("i1"), info?.itemId)
            assertEquals(PlaySessionId("play-1"), info?.playSessionId)
            assertEquals(
                listOf(MediaSource(MediaSourceId("src1"), "Heat", "mkv", 60.minutes, true, true)),
                info?.sources,
            )
        }
    }

    @Test
    fun anAnswerWithAnErrorCodeIsAServerError() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") {
                jellyfin.json("{\"MediaSources\":[],\"ErrorCode\":\"NoCompatibleStream\"}")
            }

            val error = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).errorOrNull()

            assertIs<MediaServerError.ServerError>(error)
        }
    }

    @Test
    fun anUnknownItemIsNotFound() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.status(404) }

            assertIs<MediaServerError.NotFound>(
                testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("gone")).errorOrNull(),
            )
        }
    }

    @Test
    fun aRedirectIsNotFollowed() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.redirect("http://127.0.0.1:1/x") }

            assertIs<MediaServerError.Redirected>(
                testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).errorOrNull(),
            )
        }
    }

    @Test
    fun cleartextThatNeedsConfirmationSendsNothingWithoutIt() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(response) }

            val error = testAdapter().playbackInfo(testSession(jellyfin.fallbackAddress), ItemId("i1")).errorOrNull()

            assertIs<MediaServerError.CleartextNotConfirmed>(error)
            assertEquals(emptyList(), jellyfin.log.toList())
        }
    }
}
