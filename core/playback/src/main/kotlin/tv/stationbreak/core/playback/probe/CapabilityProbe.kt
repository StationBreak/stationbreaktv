// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import android.media.AudioFormat
import android.media.MediaCodecInfo.CodecProfileLevel
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.VideoCodecSupport

/**
 * Turns a [CodecInventory] into the [DeviceCapabilities] the server's device profile is built from. Only names from
 * [Codecs] come out: a MIME type or profile we have no name for is dropped, never passed through. A full [probe] is
 * slow enough that callers cache it; [withSink] refreshes the part that changes while the app runs.
 */
class CapabilityProbe(
    private val inventory: CodecInventory,
) {
    fun probe(): DeviceCapabilities {
        val display = inventory.display()
        val video = mergeByCodec(inventory.videoDecoders(), display)
        val audioDecoders = inventory.audioDecoders().mapNotNullTo(mutableSetOf()) { AUDIO_MIMES[it] }
        return DeviceCapabilities(
            containers = containersFor(video.any { it.codec == Codecs.MPEG4 }),
            videoCodecs = video,
            audioDecoders = audioDecoders,
            audioPassthrough = passthroughNames(),
            maxAudioChannels = inventory.maxChannels(),
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
        )
    }

    /** [base] with only the sink-dependent audio fields re-read (an HDMI sink came or went). */
    fun withSink(base: DeviceCapabilities): DeviceCapabilities =
        base.copy(audioPassthrough = passthroughNames(), maxAudioChannels = inventory.maxChannels())

    private fun passthroughNames(): Set<String> =
        inventory.passthroughEncodings().mapNotNullTo(mutableSetOf()) { PASSTHROUGH_ENCODINGS[it] }

    /** Two decoders of one codec (a hardware and a software one) become one entry that is the best of both. */
    private fun mergeByCodec(
        decoders: List<DecoderInfo>,
        display: DisplayInfo,
    ): List<VideoCodecSupport> {
        val byCodec = LinkedHashMap<String, MutableList<DecoderInfo>>()
        for (decoder in decoders) {
            val codec = VIDEO_MIMES[decoder.mime] ?: continue
            byCodec.getOrPut(codec) { mutableListOf() }.add(decoder)
        }
        return byCodec.map { (codec, group) ->
            VideoCodecSupport(
                codec = codec,
                profiles = group.flatMapTo(mutableSetOf()) { profileNames(codec, it.profiles) },
                maxLevel = group.mapNotNull { levelIdc(codec, it.maxLevel) }.maxOrNull(),
                maxWidth = minOf(group.maxOf { it.maxWidth }, display.width),
                maxHeight = minOf(group.maxOf { it.maxHeight }, display.height),
                maxBitDepth = if (group.any { hasTenBit(codec, it.profiles) }) TEN_BIT else EIGHT_BIT,
                // Not filled yet: the vocabulary has no HDR names and the server isn't sent them (issue #323).
                hdr = emptySet(),
            )
        }
    }

    private fun containersFor(hasMpeg4Decoder: Boolean): Set<String> =
        if (hasMpeg4Decoder) BASE_CONTAINERS + Codecs.AVI else BASE_CONTAINERS

    private fun profileNames(
        codec: String,
        profiles: Set<Int>,
    ): Set<String> =
        when (codec) {
            Codecs.H264 -> profiles.flatMapTo(mutableSetOf()) { H264_PROFILES[it].orEmpty() }
            Codecs.HEVC -> profiles.mapNotNullTo(mutableSetOf()) { HEVC_PROFILES[it] }
            else -> emptySet()
        }

    private fun hasTenBit(
        codec: String,
        profiles: Set<Int>,
    ): Boolean =
        when (codec) {
            Codecs.H264 -> CodecProfileLevel.AVCProfileHigh10 in profiles
            Codecs.HEVC -> CodecProfileLevel.HEVCProfileMain10 in profiles
            Codecs.VP9 -> CodecProfileLevel.VP9Profile2 in profiles
            Codecs.AV1 -> CodecProfileLevel.AV1ProfileMain10 in profiles
            else -> false
        }

    private fun levelIdc(
        codec: String,
        level: Int?,
    ): Int? {
        val table =
            when (codec) {
                Codecs.H264 -> AVC_LEVELS
                Codecs.HEVC -> HEVC_LEVELS
                else -> return null
            }
        return level?.let {
            table.entries
                .filter { e -> e.key <= it }
                .maxByOrNull { e -> e.key }
                ?.value
        }
    }

    private companion object {
        const val MAX_STREAMING_BITRATE = 40_000_000
        const val EIGHT_BIT = 8
        const val TEN_BIT = 10

        val BASE_CONTAINERS = setOf(Codecs.MP4, Codecs.M4V, Codecs.MKV, Codecs.WEBM, Codecs.TS, Codecs.MPEGTS)

        val VIDEO_MIMES =
            mapOf(
                "video/avc" to Codecs.H264,
                "video/hevc" to Codecs.HEVC,
                "video/av01" to Codecs.AV1,
                "video/x-vnd.on2.vp9" to Codecs.VP9,
                "video/mpeg2" to Codecs.MPEG2,
                "video/mp4v-es" to Codecs.MPEG4,
            )

        val AUDIO_MIMES =
            mapOf(
                "audio/mp4a-latm" to Codecs.AAC,
                "audio/mpeg" to Codecs.MP3,
                "audio/ac3" to Codecs.AC3,
                "audio/eac3" to Codecs.EAC3,
                "audio/opus" to Codecs.OPUS,
                "audio/flac" to Codecs.FLAC,
                "audio/vorbis" to Codecs.VORBIS,
            )

        val PASSTHROUGH_ENCODINGS =
            mapOf(
                AudioFormat.ENCODING_AC3 to Codecs.AC3,
                AudioFormat.ENCODING_E_AC3 to Codecs.EAC3,
                AudioFormat.ENCODING_E_AC3_JOC to Codecs.EAC3,
                AudioFormat.ENCODING_DTS to Codecs.DTS,
                AudioFormat.ENCODING_DTS_HD to Codecs.DTS,
                AudioFormat.ENCODING_DOLBY_TRUEHD to Codecs.TRUEHD,
            )

        // A baseline decoder also decodes constrained baseline (a subset), and Jellyfin asks for it by name.
        val H264_PROFILES =
            mapOf(
                CodecProfileLevel.AVCProfileBaseline to setOf(Codecs.BASELINE, Codecs.CONSTRAINED_BASELINE),
                CodecProfileLevel.AVCProfileConstrainedBaseline to setOf(Codecs.CONSTRAINED_BASELINE),
                CodecProfileLevel.AVCProfileMain to setOf(Codecs.MAIN),
                CodecProfileLevel.AVCProfileHigh to setOf(Codecs.HIGH),
                CodecProfileLevel.AVCProfileHigh10 to setOf(Codecs.HIGH_10),
            )

        val HEVC_PROFILES =
            mapOf(
                CodecProfileLevel.HEVCProfileMain to Codecs.MAIN,
                CodecProfileLevel.HEVCProfileMain10 to Codecs.MAIN_10,
            )

        /** MediaCodec's AVC level flags to the bitstream's level_idc (4.1 = 41). */
        val AVC_LEVELS =
            mapOf(
                CodecProfileLevel.AVCLevel1 to 10,
                CodecProfileLevel.AVCLevel1b to 9,
                CodecProfileLevel.AVCLevel11 to 11,
                CodecProfileLevel.AVCLevel12 to 12,
                CodecProfileLevel.AVCLevel13 to 13,
                CodecProfileLevel.AVCLevel2 to 20,
                CodecProfileLevel.AVCLevel21 to 21,
                CodecProfileLevel.AVCLevel22 to 22,
                CodecProfileLevel.AVCLevel3 to 30,
                CodecProfileLevel.AVCLevel31 to 31,
                CodecProfileLevel.AVCLevel32 to 32,
                CodecProfileLevel.AVCLevel4 to 40,
                CodecProfileLevel.AVCLevel41 to 41,
                CodecProfileLevel.AVCLevel42 to 42,
                CodecProfileLevel.AVCLevel5 to 50,
                CodecProfileLevel.AVCLevel51 to 51,
                CodecProfileLevel.AVCLevel52 to 52,
                CodecProfileLevel.AVCLevel6 to 60,
                CodecProfileLevel.AVCLevel61 to 61,
                CodecProfileLevel.AVCLevel62 to 62,
            )

        /** MediaCodec's HEVC level flags (either tier) to general_level_idc (level times 30: 4.1 = 123). */
        val HEVC_LEVELS =
            mapOf(
                CodecProfileLevel.HEVCMainTierLevel1 to 30,
                CodecProfileLevel.HEVCMainTierLevel2 to 60,
                CodecProfileLevel.HEVCMainTierLevel21 to 63,
                CodecProfileLevel.HEVCMainTierLevel3 to 90,
                CodecProfileLevel.HEVCMainTierLevel31 to 93,
                CodecProfileLevel.HEVCMainTierLevel4 to 120,
                CodecProfileLevel.HEVCMainTierLevel41 to 123,
                CodecProfileLevel.HEVCMainTierLevel5 to 150,
                CodecProfileLevel.HEVCMainTierLevel51 to 153,
                CodecProfileLevel.HEVCMainTierLevel52 to 156,
                CodecProfileLevel.HEVCMainTierLevel6 to 180,
                CodecProfileLevel.HEVCMainTierLevel61 to 183,
                CodecProfileLevel.HEVCMainTierLevel62 to 186,
            )
    }
}
