// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.VideoCodecSupport

/**
 * The `PlaybackInfo` request body: the device profile built from what the player's probe found, plus the start and the
 * choices (spec 13). Nothing in it is a credential. A null track index is left out so the server applies the user's
 * own preferences, which it can only do for the `UserId` sent here.
 */
internal object DeviceProfiles {
    private val json = Json

    fun body(
        request: PlaybackRequest,
        userId: UserId,
    ): ByteArray {
        val caps = request.capabilities
        val direct = !request.forceTranscode
        val body =
            buildJsonObject {
                put("UserId", userId.value)
                put("StartTimeTicks", PlaybackTicks.of(request.start))
                request.audioIndex?.let { put("AudioStreamIndex", it) }
                request.subtitleIndex?.let { put("SubtitleStreamIndex", it) }
                put("MaxStreamingBitrate", caps.maxStreamingBitrate)
                put("EnableDirectPlay", direct)
                put("EnableDirectStream", direct)
                put("EnableTranscoding", true)
                put("AllowVideoStreamCopy", direct)
                put("AllowAudioStreamCopy", true)
                put("AutoOpenLiveStream", false)
                put("DeviceProfile", profile(caps))
            }
        return json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    private fun profile(caps: DeviceCapabilities): JsonObject =
        buildJsonObject {
            put("Name", "StationBreak")
            put("MaxStreamingBitrate", caps.maxStreamingBitrate)
            put("DirectPlayProfiles", directPlay(caps))
            putJsonArray("TranscodingProfiles") { add(transcoding(caps)) }
            put("ContainerProfiles", JsonArray(emptyList()))
            put("CodecProfiles", codecProfiles(caps))
            put("SubtitleProfiles", subtitleProfiles())
        }

    /** Every audio codec the device can play: decoded itself or passed through to the sink. */
    private fun audioCodecs(caps: DeviceCapabilities) = (caps.audioDecoders + caps.audioPassthrough).sorted()

    private fun directPlay(caps: DeviceCapabilities): JsonArray =
        buildJsonArray {
            if (caps.containers.isNotEmpty() && caps.videoCodecs.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("Container", caps.containers.sorted().joinToString(","))
                        put("Type", "Video")
                        put("VideoCodec", caps.videoCodecs.joinToString(",") { it.codec })
                        put("AudioCodec", audioCodecs(caps).joinToString(","))
                    },
                )
            }
        }

    private fun transcoding(caps: DeviceCapabilities): JsonObject =
        buildJsonObject {
            put("Container", segmentContainer(caps))
            put("Type", "Video")
            put("Protocol", "hls")
            put("VideoCodec", transcodeVideoNames(caps))
            put("AudioCodec", transcodeAudio(caps, segmentContainer(caps) == Codecs.MP4))
            put("Context", "Streaming")
            put("MaxAudioChannels", caps.maxAudioChannels.toString())
            put("BreakOnNonKeyFrames", true)
        }

    /**
     * What the server may put in the transcoded audio: AAC, or a codec the sink takes as it is. In fMP4 segments
     * ([fmp4]) only AAC, AC-3 and E-AC-3, which every fMP4 muxer and player takes; DTS and TrueHD stay on MPEG-TS.
     */
    fun transcodeAudio(
        caps: DeviceCapabilities,
        fmp4: Boolean = false,
    ): String {
        val passthrough = caps.audioPassthrough.sorted().filter { !fmp4 || it in FMP4_AUDIO }
        return (listOf(Codecs.AAC) + passthrough).joinToString(",")
    }

    private val FMP4_AUDIO = setOf(Codecs.AAC, Codecs.AC3, Codecs.EAC3)

    private fun codecProfiles(caps: DeviceCapabilities): JsonArray =
        buildJsonArray {
            caps.videoCodecs.forEach { add(videoProfile(it)) }
            val limited = (caps.audioDecoders - caps.audioPassthrough).sorted()
            if (limited.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("Type", "VideoAudio")
                        put("Codec", limited.joinToString(","))
                        putJsonArray("Conditions") {
                            add(condition("LessThanEqual", "AudioChannels", caps.maxAudioChannels.toString()))
                        }
                    },
                )
            }
        }

    private fun videoProfile(support: VideoCodecSupport): JsonObject =
        buildJsonObject {
            put("Type", "Video")
            put("Codec", support.codec)
            putJsonArray("Conditions") {
                if (support.profiles.isNotEmpty()) {
                    add(condition("EqualsAny", "VideoProfile", profileNames(support).joinToString("|")))
                }
                support.maxLevel?.let { add(condition("LessThanEqual", "VideoLevel", it.toString())) }
                add(condition("LessThanEqual", "Width", support.maxWidth.toString()))
                add(condition("LessThanEqual", "Height", support.maxHeight.toString()))
                add(condition("LessThanEqual", "VideoBitDepth", support.maxBitDepth.toString()))
            }
        }

    private fun condition(
        kind: String,
        property: String,
        value: String,
    ): JsonObject =
        buildJsonObject {
            put("Condition", kind)
            put("Property", property)
            put("Value", value)
            put("IsRequired", true)
        }

    private fun subtitleProfiles(): JsonArray =
        buildJsonArray {
            Codecs.TEXT_SUBTITLES.forEach { add(subtitle(it, "Embed")) }
            add(subtitle("vtt", "External"))
            Codecs.IMAGE_SUBTITLES.forEach { add(subtitle(it, "Encode")) }
        }

    private fun subtitle(
        format: String,
        method: String,
    ): JsonObject =
        buildJsonObject {
            put("Format", format)
            put("Method", method)
        }
}

/**
 * The profile names to send for [support]. ffprobe names an encoder's baseline-without-extras output "Constrained
 * Baseline" (x264's ultrafast does, and so do most phones), which the server compares as a name of its own, so it has
 * to be listed beside "baseline". Every baseline decoder plays it: it is the subset of baseline.
 */
internal fun profileNames(support: VideoCodecSupport): List<String> =
    support.profiles.flatMap { name ->
        if (support.codec == Codecs.H264 && name == Codecs.BASELINE) {
            listOf(name, Codecs.CONSTRAINED_BASELINE)
        } else {
            listOf(name)
        }
    }

/**
 * The video codecs the server may put in an HLS stream, H.264 first. HEVC is listed only where the probe found a
 * decoder, so the server can copy an HEVC video and convert just its audio (ADR-0016, update 2026-10-22); a device
 * without one keeps the H.264-only list. H.264 comes first, which keeps any encode on H.264 (a server admin can enable
 * HEVC encoding). The HLS URL lists HEVC only for a source it will copy (R33); this list is for the profile.
 */
internal fun transcodeVideo(caps: DeviceCapabilities): List<VideoCodecSupport> =
    listOfNotNull(
        caps.videoCodecs.firstOrNull { it.codec == Codecs.H264 },
        caps.videoCodecs.firstOrNull { it.codec == Codecs.HEVC },
    )

/** [transcodeVideo] as the profile and the HLS URL spell it: always H.264, then HEVC where it may be copied. */
internal fun transcodeVideoNames(caps: DeviceCapabilities): String =
    (listOf(Codecs.H264) + transcodeVideo(caps).map { it.codec }).distinct().joinToString(",")

/**
 * The container the profile declares for its transcoding profile: fMP4 where the device decodes HEVC, so the server
 * offers a direct stream of HEVC. The HLS URL decides per source and uses fMP4 only for an HEVC source it copies.
 */
internal fun segmentContainer(caps: DeviceCapabilities): String =
    if (transcodeVideo(caps).any { it.codec == Codecs.HEVC }) Codecs.MP4 else Codecs.TS
