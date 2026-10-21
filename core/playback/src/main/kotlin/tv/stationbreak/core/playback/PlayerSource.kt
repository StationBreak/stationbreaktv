// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import kotlin.time.Duration

/** How the item reaches the screen. */
enum class Route { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** A track choice. A null [subtitleIndex] means subtitles off, a null [audioIndex] the server's default. */
data class TrackOverride(
    val audioIndex: Int?,
    val subtitleIndex: Int?,
)

/**
 * A track the player picks itself on direct play: its [position] among the file's tracks of its kind (the third audio
 * track is 2), and the [language] and [codec] the server reports for it, which the player checks the pick against.
 */
data class TrackPick(
    val position: Int,
    val language: String?,
    val codec: String?,
)

/**
 * A WebVTT file the player loads beside the video. The [url] carries no credential (ADR-0016), but it is still a
 * server address, so [toString] does not show it.
 */
data class SeparateSubtitle(
    val url: String,
    val language: String?,
) {
    override fun toString(): String = "SeparateSubtitle(language=$language, url=<not shown>)"
}

/**
 * What the player is handed. [url] is a secret as far as logs go (ADR-0016): [toString] does not show it.
 * [serverOffset] is where the server starts the stream (HLS StartTimeTicks); [playerStart] is where the player seeks
 * within it. [directPlayTracks] is true when the player's own track selection applies (direct play only); then
 * [audioPick] and [subtitlePick] name the tracks to pick. A null [subtitlePick] with no [subtitles] means no subtitle.
 */
@Suppress("LongParameterList") // One independent fact each; the player reads them all.
data class PlayerSource(
    val url: String,
    val isHls: Boolean,
    val serverOffset: Duration,
    val playerStart: Duration,
    val subtitles: List<SeparateSubtitle>,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val directPlayTracks: Boolean,
    val audioPick: TrackPick? = null,
    val subtitlePick: TrackPick? = null,
) {
    override fun toString(): String = "PlayerSource(hls=$isHls, url=<not shown>, subtitles=${subtitles.size})"
}

/** The resolver's answer: what to play and why. [requested] is the start the caller asked for. */
@Suppress("LongParameterList") // One independent fact each.
data class ResolvedPlayback(
    val source: PlayerSource,
    val route: Route,
    val playSession: PlaySessionId?,
    val media: MediaSource,
    val chosenAudio: MediaStreamInfo?,
    val chosenSubtitle: MediaStreamInfo?,
    val requested: Duration,
)
