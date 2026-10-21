// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind

/**
 * Can this device play [audio] (null: no audio track, nothing to refuse) as it is? The codec must be one it decodes or
 * passes through to the HDMI sink. The channel limit is the decoder's, so it applies to decoded audio only: a
 * passthrough codec leaves the app as it is and the sink decides (ADR-0016 is silent; this is the reading chosen).
 */
internal fun audioPlays(
    capabilities: DeviceCapabilities,
    audio: MediaStreamInfo?,
): Boolean {
    if (audio == null) return true
    val codec = Codecs.normalize(audio.codec)
    return when {
        codec == null -> false
        codec in capabilities.audioPassthrough -> true
        codec in capabilities.audioDecoders -> (audio.channels ?: 0) <= capabilities.maxAudioChannels
        else -> false
    }
}

/**
 * A test oracle and a diagnostic only. Playback always follows the server's answer. It says what the device's
 * capabilities imply for [source] with [subtitle] selected, for the audio track the source starts on.
 *
 * It is an independent oracle, not a copy of the resolver, so its subtitle rule is deliberately by file: an image
 * codec means a transcode, whatever delivery the server picked. It predicts for the default tracks only; a track
 * override is the resolver's business. Video levels compare as sent, in the probe's unit (level_idc: h264 41 = 4.1,
 * hevc 153 = 5.1).
 */
object ExpectedRoute {
    fun of(
        capabilities: DeviceCapabilities,
        source: MediaSource,
        subtitle: MediaStreamInfo?,
    ): Route {
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val audio = source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.index == source.defaultAudioIndex }
        return when {
            subtitle != null && Codecs.isImageSubtitle(subtitle.codec) -> Route.TRANSCODE
            video != null && !videoFits(capabilities, video) -> Route.TRANSCODE
            !audioPlays(capabilities, audio) -> Route.TRANSCODE
            Codecs.normalize(source.container) !in capabilities.containers -> Route.DIRECT_STREAM
            else -> Route.DIRECT_PLAY
        }
    }

    /** A limit the server did not report (null) is not held against the file. */
    private fun videoFits(
        capabilities: DeviceCapabilities,
        video: MediaStreamInfo,
    ): Boolean {
        val codec = Codecs.normalize(video.codec)
        val support = capabilities.videoCodecs.firstOrNull { it.codec == codec } ?: return false
        val profile = Codecs.normalize(video.videoProfile)
        val profileFits = profile == null || support.profiles.isEmpty() || profile in support.profiles
        return profileFits &&
            within(video.videoLevel, support.maxLevel) &&
            within(video.width, support.maxWidth) &&
            within(video.height, support.maxHeight) &&
            within(video.bitDepth, support.maxBitDepth)
    }

    private fun within(
        value: Int?,
        limit: Int?,
    ): Boolean = value == null || limit == null || value <= limit
}
