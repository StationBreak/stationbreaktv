// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** `playbackInfo(session, item)`: the request's device profile out, media sources and a URL builder back. */
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

            testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1"), requestFor())

            val request = jellyfin.requests("/PlaybackInfo").single()
            assertEquals("POST", request.method)
            assertEquals("/Items/i1/PlaybackInfo", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("user1", body["UserId"]!!.jsonPrimitive.content)
            val profile = body["DeviceProfile"]!!.jsonObject
            assertTrue((profile["DirectPlayProfiles"] as JsonArray).isNotEmpty())
            assertTrue((profile["TranscodingProfiles"] as JsonArray).isNotEmpty())
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
    fun streamsDefaultsDeliveryAndReasonsAreParsed() {
        runBlocking {
            val body =
                """{"MediaSources":[{"Id":"src1","SupportsTranscoding":true,"Bitrate":8000000,
                |"DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":3,
                |"TranscodeReasons":["AudioCodecNotSupported","ContainerNotSupported"],"MediaStreams":[
                |{"Index":0,"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"IsDefault":true},
                |{"Index":1,"Type":"Audio","Codec":"ac3","Language":"eng","Channels":6,"IsDefault":true},
                |{"Index":2,"Type":"Subtitle","Codec":"subrip","IsExternal":true,"DeliveryMethod":"External"},
                |{"Index":3,"Type":"Subtitle","Codec":"subrip","DeliveryMethod":"Embed","IsForced":true},
                |{"Index":4,"Type":"Subtitle","Codec":"pgssub","DeliveryMethod":"Encode"},
                |{"Index":5,"Type":"Subtitle","Codec":"ass","DeliveryMethod":"Drop"},
                |{"Index":6,"Type":"Subtitle","Codec":"ass"},
                |{"Type":"Subtitle","Codec":"ass","DeliveryMethod":"Embed"},
                |{"Index":7,"Type":"EmbeddedImage","Codec":"mjpeg"}]}],"PlaySessionId":"play-1"}
                """.trimMargin().replace("\n", "")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(body) }

            val source =
                testAdapter()
                    .playbackInfo(testSession(jellyfin.address), ItemId("i1"))
                    .getOrNull()!!
                    .sources
                    .single()

            assertTrue(source.supportsTranscoding)
            assertEquals(8_000_000, source.bitrate)
            assertEquals(1, source.defaultAudioIndex)
            assertEquals(3, source.defaultSubtitleIndex)
            assertEquals(setOf("AudioCodecNotSupported", "ContainerNotSupported"), source.transcodeReasons)
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), source.streams.map { it.index })
            val video = source.streams[0]
            assertEquals(StreamKind.VIDEO, video.kind)
            assertEquals(Triple("h264", 1920, 1080), Triple(video.codec, video.width, video.height))
            assertNull(video.delivery)
            assertEquals(6, source.streams[1].channels)
            assertEquals("eng", source.streams[1].language)
            assertEquals(
                listOf(
                    SubtitleDelivery.SEPARATE,
                    SubtitleDelivery.IN_STREAM,
                    SubtitleDelivery.BURNED_IN,
                    SubtitleDelivery.NONE,
                    SubtitleDelivery.NONE,
                ),
                source.streams.drop(2).map { it.delivery },
            )
            assertTrue(source.streams[2].isExternal)
            assertTrue(source.streams[3].isForced)
        }
    }

    @Test
    fun aVideoStreamCarriesItsProfileLevelAndBitDepth() {
        runBlocking {
            val body =
                """{"MediaSources":[{"Id":"src1","MediaStreams":[
                |{"Index":0,"Type":"Video","Codec":"h264","Profile":"Constrained Baseline","Level":41,"BitDepth":8},
                |{"Index":1,"Type":"Video","Codec":"hevc","Profile":"Main 10","Level":153,"BitDepth":10},
                |{"Index":2,"Type":"Video","Codec":"h264","Level":"odd"},
                |{"Index":3,"Type":"Audio","Codec":"aac","Profile":"LC"}]}],"PlaySessionId":"play-1"}
                """.trimMargin().replace("\n", "")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(body) }

            val streams =
                testAdapter()
                    .playbackInfo(testSession(jellyfin.address), ItemId("i1"))
                    .getOrNull()!!
                    .sources
                    .single()
                    .streams

            fun facts(index: Int) = streams[index].let { Triple(it.videoProfile, it.videoLevel, it.bitDepth) }
            assertEquals(Triple("constrained baseline", 41, 8), facts(0))
            assertEquals(Triple("main 10", 153, 10), facts(1))
            assertNull(streams[2].videoLevel)
            assertNull(streams[3].videoProfile)
        }
    }

    @Test
    fun eachEmbeddedStreamKnowsItsPositionWithinItsKind() {
        runBlocking {
            // Server indexes interleave the kinds and an external subtitle sits among them; positions count per kind.
            val body =
                """{"MediaSources":[{"Id":"src1","MediaStreams":[
                |{"Index":0,"Type":"Video","Codec":"h264"},
                |{"Index":1,"Type":"Audio","Codec":"aac"},
                |{"Index":2,"Type":"Audio","Codec":"ac3"},
                |{"Index":3,"Type":"Subtitle","Codec":"subrip","DeliveryMethod":"Embed"},
                |{"Index":4,"Type":"Subtitle","Codec":"subrip","IsExternal":true,"DeliveryMethod":"External"},
                |{"Index":5,"Type":"Subtitle","Codec":"pgssub","DeliveryMethod":"Encode"},
                |{"Index":6,"Type":"Audio","Codec":"dts"}]}],"PlaySessionId":"play-1"}
                """.trimMargin().replace("\n", "")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(body) }

            val source =
                testAdapter()
                    .playbackInfo(testSession(jellyfin.address), ItemId("i1"))
                    .getOrNull()!!
                    .sources
                    .single()

            assertEquals(listOf(0, 0, 1, 0, null, 1, 2), source.streams.map { it.positionInKind })
        }
    }

    @Test
    fun theServerTranscodingUrlIsNeverRead() {
        runBlocking {
            val body =
                "{\"MediaSources\":[{\"Id\":\"src1\",\"Path\":\"/srv/secret-share/a.mkv\"," +
                    "\"TranscodingUrl\":\"/videos/i1/master.m3u8?api_key=secret\"," +
                    "\"MediaStreams\":[{\"Index\":2,\"Type\":\"Subtitle\",\"Codec\":\"subrip\"," +
                    "\"DeliveryMethod\":\"External\",\"DeliveryUrl\":\"/s/Stream.srt?api_key=secret\"," +
                    "\"Path\":\"/srv/secret-share/a.srt\"}]}]," +
                    "\"TranscodingUrl\":\"/videos/i1/master.m3u8?api_key=secret\",\"PlaySessionId\":\"play-1\"}"
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(body) }

            val info = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).getOrNull()!!

            val source = info.sources.single()
            val everything =
                listOf(
                    info.toString(),
                    source.toString(),
                    info.streams.original(source.id),
                    info.streams.hls(source, null, null, insigniaCapabilities(), false),
                    info.streams.subtitle(source.id, source.streams.single()).orEmpty(),
                ).joinToString("\n")
            assertFalse("secret" in everything, everything)
            assertFalse("api_key" in everything, everything)
        }
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
