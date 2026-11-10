// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import kotlin.time.Duration

/** How the item reaches the screen. */
enum class Route { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** What a tune does about subtitles. Three states, so no null has to mean two things. */
sealed interface SubtitleChoice {
    /** The server applies the user's own preferences (ADR-0016, 6): the source's default subtitle, or none. */
    data object Default : SubtitleChoice

    /** No subtitles, whatever the server's default is. */
    data object Off : SubtitleChoice

    /** The subtitle track with the server's stream [index]. */
    data class Track(
        val index: Int,
    ) : SubtitleChoice
}

/**
 * Tracks to play, by the server's stream indices of one source. A null [audioIndex] is the server's default audio;
 * [subtitle] says what to do about subtitles. The indices mean something only for one source, so an override never
 * travels alone: see [TrackChoice].
 */
data class TrackOverride(
    val audioIndex: Int?,
    val subtitle: SubtitleChoice = SubtitleChoice.Default,
)

/**
 * A viewer's choice for one tune: the [source] (version) to play, and optionally the [tracks] within it. The tracks
 * and their source are one value, because an override's indices are one source's stream indices. If [source] is not in
 * the server's answer, [tracks] is dropped and the item is resolved as if no choice was made (ADR-0016, update
 * 2026-11-09).
 */
data class TrackChoice(
    val source: MediaSourceId,
    val tracks: TrackOverride? = null,
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
 * [playerStart] is where the player seeks, for HLS as for direct play. [directPlayTracks] is true when the player's
 * own track selection applies (direct play only); then [audioPick] and [subtitlePick] name the tracks to pick.
 * A null [subtitlePick] with no [subtitles] means no subtitle. [warmKey] is the handle [VideoPlayer.warm] returned for
 * this source (spec 24), stamped on by the caller: a play with it swaps to that standby when it is ready. It is not
 * shown by [toString] either.
 */
@Suppress("LongParameterList") // One independent fact each; the player reads them all.
data class PlayerSource(
    val url: String,
    val isHls: Boolean,
    val playerStart: Duration,
    val subtitles: List<SeparateSubtitle>,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val directPlayTracks: Boolean,
    val audioPick: TrackPick? = null,
    val subtitlePick: TrackPick? = null,
    val warmKey: String? = null,
) {
    override fun toString(): String = "PlayerSource(hls=$isHls, url=<not shown>, subtitles=${subtitles.size})"
}

/**
 * The resolver's answer: what to play and why. [requested] is the start the caller asked for. [sources] are every
 * version the server offered for the item, in its order, the chosen [media] among them; empty when a result is built by
 * hand without them. [limits] are the device's capabilities the route was decided with (null for a result built by
 * hand): the facts' switch costs are decided against them.
 */
@Suppress("LongParameterList") // One independent fact each.
data class ResolvedPlayback(
    val source: PlayerSource,
    val route: Route,
    val playSession: PlaySessionId?,
    val media: MediaSource,
    val chosenAudio: MediaStreamInfo?,
    val chosenSubtitle: MediaStreamInfo?,
    val requested: Duration,
    val sources: List<MediaSource> = emptyList(),
    val limits: DeviceCapabilities? = null,
)
