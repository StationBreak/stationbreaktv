// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind

/**
 * A test oracle and a diagnostic only. Playback always follows the server's answer. It says what the device's
 * capabilities imply for [source] with [subtitle] selected, for [audio], the track the player starts on (the source's
 * default when not given). The chosen track is an input here, not a rule: which track is chosen is the resolver's
 * business, what that track implies is this oracle's.
 *
 * It is an independent oracle, not a copy of the resolver: it shares no rule with it, and its audio rule is its own,
 * written from the capabilities' raw sets (a table test holds the two to the same answers). Its subtitle rule is
 * deliberately by file: an image codec means a transcode, whatever delivery the server picked. Video levels compare as
 * sent, in the probe's unit (level_idc: h264 41 = 4.1, hevc 153 = 5.1). Video that fits with audio the device can't
 * play is a direct stream: the server copies the video and converts only the audio (ADR-0016, the contract suite's
 * `h264-dts`; for HEVC the server transcodes until #351). A subtitle the device can't draw or video that doesn't fit
 * is a transcode.
 */
object ExpectedRoute {
    fun of(
        capabilities: DeviceCapabilities,
        source: MediaSource,
        subtitle: MediaStreamInfo?,
        audio: MediaStreamInfo? = defaultAudio(source),
    ): Route {
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        return when {
            subtitle != null && Codecs.isImageSubtitle(subtitle.codec) -> Route.TRANSCODE
            video != null && !videoFits(capabilities, video) -> Route.TRANSCODE
            !deviceTakesAudio(capabilities, audio) -> Route.DIRECT_STREAM
            Codecs.normalize(source.container) !in capabilities.containers -> Route.DIRECT_STREAM
            else -> Route.DIRECT_PLAY
        }
    }

    private fun defaultAudio(source: MediaSource): MediaStreamInfo? =
        source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.index == source.defaultAudioIndex }

    /**
     * No audio track: nothing to refuse. Otherwise the codec must be passed through to the sink (which then decides
     * the channels) or decoded here with no more channels than the decoder takes; an unreported channel count is not
     * held against the file. A codec the app does not know is refused.
     */
    private fun deviceTakesAudio(
        capabilities: DeviceCapabilities,
        audio: MediaStreamInfo?,
    ): Boolean {
        val codec = audio?.let { Codecs.normalize(it.codec) }
        val channelsFit = audio?.channels?.let { it <= capabilities.maxAudioChannels } ?: true
        return when {
            audio == null -> true
            codec == null -> false
            capabilities.audioPassthrough.contains(codec) -> true
            else -> capabilities.audioDecoders.contains(codec) && channelsFit
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
