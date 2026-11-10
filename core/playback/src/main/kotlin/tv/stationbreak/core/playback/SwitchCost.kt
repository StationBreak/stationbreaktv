// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo

/** What a change of track or version costs the picture. */
enum class SwitchCost {
    /** The player switches while it plays: no snow, no new stream. */
    Instant,

    /** The stream is asked for again at the current position, under the snow ("restarts picture"). */
    Retune,
}

/** What a viewer may choose to change while watching. */
sealed interface SwitchTarget {
    /** Another audio track of the playing version. */
    data class Audio(
        val stream: MediaStreamInfo,
    ) : SwitchTarget

    /** Another subtitle track of the playing version, or null for subtitles off. */
    data class Subtitle(
        val stream: MediaStreamInfo?,
    ) : SwitchTarget

    /** Another version of the item. */
    data class Version(
        val source: MediaSourceId,
    ) : SwitchTarget
}

/**
 * The one place that decides whether a change interrupts the picture (design doc B3), so the picker's "restarts
 * picture" label and what then happens cannot disagree. Only direct play can switch while it plays, because only
 * there does the player hold the file's own tracks ([PlayerSource.directPlayTracks]); an HLS route (direct stream or
 * transcode) has the choice baked into the playlist, so every change re-tunes. On direct play:
 *
 * - **Audio** is instant when the player can decode the track as it is ([audioPlays]); one it cannot decode moves the
 *   route to HLS (ADR-0016, 8), so it re-tunes.
 * - **Subtitles off** is instant. A **text subtitle in the container** is instant. One in **a separate file** is
 *   instant only if it is the one loaded at the start (the source carries its file); any other re-tunes, because
 *   nothing else was side-loaded. A **picture subtitle** re-tunes (burn-in).
 * - A track the player cannot find by its position in the file (no `positionInKind`) re-tunes: the instant switch
 *   names the track by it.
 * - **Version** always re-tunes.
 *
 * Text subtitles while transcoding stay [SwitchCost.Retune] until an on-TV measurement shows otherwise (design doc B3,
 * PR 4's spike).
 *
 * Internal on purpose: a screen never decides this. It reads the answer from [StreamFacts], where [facts] has already
 * put the cost on every track and version, so the picker's label and the behaviour come from this one function.
 */
internal fun switchCost(
    playing: ResolvedPlayback,
    target: SwitchTarget,
    capabilities: DeviceCapabilities,
): SwitchCost =
    when {
        target is SwitchTarget.Version -> SwitchCost.Retune
        !playing.source.directPlayTracks || playing.route != Route.DIRECT_PLAY -> SwitchCost.Retune
        target is SwitchTarget.Audio -> audioCost(target.stream, capabilities)
        target is SwitchTarget.Subtitle -> subtitleCost(playing, target.stream)
        else -> SwitchCost.Retune
    }

private fun audioCost(
    stream: MediaStreamInfo,
    capabilities: DeviceCapabilities,
): SwitchCost {
    val playable = stream.positionInKind != null && audioPlays(capabilities, stream)
    return if (playable) SwitchCost.Instant else SwitchCost.Retune
}

private fun subtitleCost(
    playing: ResolvedPlayback,
    stream: MediaStreamInfo?,
): SwitchCost =
    when {
        stream == null -> {
            SwitchCost.Instant
        }

        !Codecs.isTextSubtitle(stream.codec) -> {
            SwitchCost.Retune
        }

        stream.isExternal -> {
            val loaded = stream.index == playing.chosenSubtitle?.index && playing.source.subtitles.isNotEmpty()
            if (loaded) SwitchCost.Instant else SwitchCost.Retune
        }

        stream.positionInKind == null -> {
            SwitchCost.Retune
        }

        else -> {
            SwitchCost.Instant
        }
    }
