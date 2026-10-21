// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.VideoCodecSupport
import kotlin.time.Duration

/** An Insignia-like set-top box: H.264 up to level 4.1 at 1080p, AAC in MP4 and Matroska, no passthrough. */
internal fun insigniaCapabilities(
    passthrough: Set<String> = emptySet(),
    maxAudioChannels: Int = 2,
): DeviceCapabilities =
    DeviceCapabilities(
        containers = setOf("mp4", "mkv"),
        videoCodecs =
            listOf(
                VideoCodecSupport("h264", setOf("baseline", "main", "high"), 41, 1920, 1080, 8, emptySet()),
            ),
        audioDecoders = setOf("aac", "mp3"),
        audioPassthrough = passthrough,
        maxAudioChannels = maxAudioChannels,
        maxStreamingBitrate = 20_000_000,
    )

internal fun requestFor(
    capabilities: DeviceCapabilities = insigniaCapabilities(),
    start: Duration = Duration.ZERO,
    audioIndex: Int? = null,
    subtitleIndex: Int? = null,
    forceTranscode: Boolean = false,
): PlaybackRequest = PlaybackRequest(capabilities, start, audioIndex, subtitleIndex, forceTranscode)
