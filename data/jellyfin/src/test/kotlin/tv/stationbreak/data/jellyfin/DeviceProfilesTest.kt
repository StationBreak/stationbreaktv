// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DeviceProfilesTest {
    private fun body(request: PlaybackRequest = requestFor(insigniaCapabilities(hevc = false))): JsonObject =
        Json.parseToJsonElement(DeviceProfiles.body(request, UserId("user1")).decodeToString()).jsonObject

    private fun JsonObject.profile(): JsonObject = getValue("DeviceProfile").jsonObject

    private fun JsonObject.list(name: String): JsonArray = getValue(name).jsonArray

    private fun JsonObject.codecProfile(type: String): JsonObject =
        list("CodecProfiles").map { it.jsonObject }.single { it.text("Type") == type }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun csv(value: String): Set<String> = value.split(',').filter { it.isNotEmpty() }.toSet()

    @Test
    fun anInsigniaLikeDeviceDirectPlaysH264Only() {
        val profile = body().profile()
        val direct = profile.list("DirectPlayProfiles").single().jsonObject

        assertEquals("Video", direct.text("Type"))
        assertEquals(setOf("h264"), csv(direct.text("VideoCodec")))
        assertEquals(setOf("aac", "mp3"), csv(direct.text("AudioCodec")))
        val codec = profile.codecProfile("Video")
        assertEquals("h264", codec.text("Codec"))
        val conditions = codec.list("Conditions").map { it.jsonObject }

        fun condition(property: String) = conditions.single { it.text("Property") == property }
        assertEquals("EqualsAny", condition("VideoProfile").text("Condition"))
        // ffprobe calls x264's ultrafast output "Constrained Baseline", a subset of Baseline that the server compares
        // as its own name (contract test, 12.1): without it every such file is transcoded for nothing.
        assertEquals(
            setOf("baseline", "constrained baseline", "main", "high"),
            condition("VideoProfile").text("Value").split('|').toSet(),
        )
        assertEquals("LessThanEqual", condition("VideoLevel").text("Condition"))
        assertEquals("41", condition("VideoLevel").text("Value"))
        assertEquals("1920", condition("Width").text("Value"))
        assertEquals("1080", condition("Height").text("Value"))
        assertEquals("8", condition("VideoBitDepth").text("Value"))
    }

    @Test
    fun constrainedBaselineIsAddedOnlyWhereBaselineIsDeclared() {
        fun profiles(vararg names: String): String {
            val caps = insigniaCapabilities(hevc = false)
            val h264 = caps.videoCodecs.single().copy(profiles = names.toSet())
            val body = body(requestFor(caps.copy(videoCodecs = listOf(h264))))
            val conditions =
                body
                    .profile()
                    .codecProfile("Video")
                    .list("Conditions")
                    .map { it.jsonObject }
            return conditions.single { it.text("Property") == "VideoProfile" }.text("Value")
        }

        assertEquals("baseline|constrained baseline|main", profiles("baseline", "main"))
        assertEquals("main|high", profiles("main", "high"))
    }

    @Test
    fun containersComeFromCapabilities() {
        val caps = insigniaCapabilities().copy(containers = setOf("ts", "webm"))

        val direct =
            body(requestFor(caps))
                .profile()
                .list("DirectPlayProfiles")
                .single()
                .jsonObject

        assertEquals(setOf("ts", "webm"), csv(direct.text("Container")))
    }

    @Test
    fun imageSubtitlesAreEncodedAndTextSubtitlesEmbeddedOrExternal() {
        val subs = body().profile().list("SubtitleProfiles").map { it.jsonObject }

        fun formats(method: String) = subs.filter { it.text("Method") == method }.map { it.text("Format") }.toSet()
        assertEquals(setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text"), formats("Embed"))
        assertEquals(setOf("vtt"), formats("External"))
        assertEquals(Codecs.IMAGE_SUBTITLES, formats("Encode"))
    }

    @Test
    fun dtsIsNotDeclaredWithoutPassthrough() {
        val text = DeviceProfiles.body(requestFor(), UserId("user1")).decodeToString()

        assertFalse("dts" in text, text)
        assertFalse("ac3" in text, text)
    }

    @Test
    fun hevcIsListedAfterH264AndTheContainerIsFmp4OnlyWhereTheDeviceDecodesIt() {
        val withHevc = body(requestFor(insigniaCapabilities(hevc = true))).profile()
        val transcode = withHevc.list("TranscodingProfiles").single().jsonObject
        assertEquals("h264,hevc", transcode.text("VideoCodec"))
        assertEquals("mp4", transcode.text("Container"))
        assertEquals("hls", transcode.text("Protocol"))

        val without = body(requestFor(insigniaCapabilities(hevc = false))).profile()
        val h264Only = without.list("TranscodingProfiles").single().jsonObject
        assertEquals("h264", h264Only.text("VideoCodec"))
        assertEquals("ts", h264Only.text("Container"))
    }

    @Test
    fun fmp4TranscodingKeepsOnlyAudioFmp4Takes() {
        val caps = insigniaCapabilities(setOf("dts", "ac3", "eac3", "truehd"), 6)
        assertEquals("aac,ac3,eac3", DeviceProfiles.transcodeAudio(caps, fmp4 = true))
        assertEquals("aac,ac3,dts,eac3,truehd", DeviceProfiles.transcodeAudio(caps, fmp4 = false))
        val profile =
            body(requestFor(caps))
                .profile()
                .list("TranscodingProfiles")
                .single()
                .jsonObject
        assertEquals("aac,ac3,eac3", profile.text("AudioCodec"))
    }

    @Test
    fun ac3PassthroughIsDeclaredWhenTheSinkSupportsIt() {
        val profile = body(requestFor(insigniaCapabilities(setOf("ac3"), maxAudioChannels = 6, hevc = false))).profile()

        val direct = profile.list("DirectPlayProfiles").single().jsonObject
        assertEquals(setOf("aac", "mp3", "ac3"), csv(direct.text("AudioCodec")))
        val transcode = profile.list("TranscodingProfiles").single().jsonObject
        assertEquals("ts", transcode.text("Container"))
        assertEquals("hls", transcode.text("Protocol"))
        assertEquals("h264", transcode.text("VideoCodec"))
        assertEquals("aac,ac3", transcode.text("AudioCodec"))
        assertEquals("Streaming", transcode.text("Context"))
        assertEquals("6", transcode.text("MaxAudioChannels"))
        assertTrue(transcode.getValue("BreakOnNonKeyFrames").jsonPrimitive.boolean)
        // The channel limit applies to the codecs the device decodes itself, not to the passed-through one.
        val limited = profile.codecProfile("VideoAudio")
        assertEquals(setOf("aac", "mp3"), csv(limited.text("Codec")))
    }

    @Test
    fun forceTranscodeTurnsOffDirectPlayAndVideoCopy() {
        val forced = body(requestFor(forceTranscode = true))
        val normal = body()

        for (name in listOf("EnableDirectPlay", "EnableDirectStream", "AllowVideoStreamCopy")) {
            assertFalse(forced.getValue(name).jsonPrimitive.boolean, name)
            assertTrue(normal.getValue(name).jsonPrimitive.boolean, name)
        }
        assertTrue(forced.getValue("EnableTranscoding").jsonPrimitive.boolean)
        assertTrue(forced.getValue("AllowAudioStreamCopy").jsonPrimitive.boolean)
        assertFalse(normal.getValue("AutoOpenLiveStream").jsonPrimitive.boolean)
        assertEquals("20000000", normal.text("MaxStreamingBitrate"))
        assertEquals("user1", normal.text("UserId"))
    }

    @Test
    fun nullIndicesAreOmittedSoTheServerChooses() {
        val none = body()
        val chosen = body(requestFor(audioIndex = 2, subtitleIndex = 5))

        assertNull(none["AudioStreamIndex"])
        assertNull(none["SubtitleStreamIndex"])
        assertEquals("2", chosen.text("AudioStreamIndex"))
        assertEquals("5", chosen.text("SubtitleStreamIndex"))
    }

    @Test
    fun theStartIsSentInTicks() {
        assertEquals("900000000", body(requestFor(start = 90.seconds)).text("StartTimeTicks"))
    }
}
