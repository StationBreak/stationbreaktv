// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * What one decoder handles for one video [codec]. [maxLevel] is the codec's bitstream level_idc (H.264 4.1 = 41; HEVC
 * as defined by HEVC), or null when unknown. [profiles] are lowercase ffmpeg profile names from [Codecs] (checked for
 * h264 and hevc).
 */
data class VideoCodecSupport(
    val codec: String,
    val profiles: Set<String>,
    val maxLevel: Int?,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitDepth: Int,
    val hdr: Set<String>,
) {
    init {
        Codecs.requireKnown("video codec", setOf(codec), Codecs.VIDEO)
        when (codec) {
            Codecs.H264 -> Codecs.requireKnown("h264 profile", profiles, Codecs.H264_PROFILES)
            Codecs.HEVC -> Codecs.requireKnown("hevc profile", profiles, Codecs.HEVC_PROFILES)
        }
    }
}

/**
 * What this device can play, as the player's capability probe found it: the input to the server's device profile.
 * [containers] are what the player opens directly (mp4, m4v, mkv, webm, ts, avi ...). [audioPassthrough] is a subset
 * of [Codecs.PASSTHROUGH_AUDIO]. Every name is from the [Codecs] vocabulary, or construction fails.
 */
data class DeviceCapabilities(
    val containers: Set<String>,
    val videoCodecs: List<VideoCodecSupport>,
    val audioDecoders: Set<String>,
    val audioPassthrough: Set<String>,
    val maxAudioChannels: Int,
    val maxStreamingBitrate: Int = DEFAULT_MAX_STREAMING_BITRATE,
) {
    init {
        Codecs.requireKnown("container", containers, Codecs.CONTAINERS)
        Codecs.requireKnown("audio decoder", audioDecoders, Codecs.AUDIO)
        Codecs.requireKnown("passthrough codec", audioPassthrough, Codecs.PASSTHROUGH_AUDIO)
    }

    companion object {
        const val DEFAULT_MAX_STREAMING_BITRATE: Int = 40_000_000
    }
}
