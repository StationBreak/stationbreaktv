// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.ProgramInfo

/** What the live screen shows (spec 14). The screen draws it; the machine in [LiveChannel] decides it. */
sealed interface LiveState {
    /** Signed in, but no channel is on the air. Not final: a lineup that arrives later opens a channel. */
    data object NoChannels : LiveState

    /** Snow with the banner, until the picture plays (at least the minimum snow time). */
    data class Tuning(
        val banner: Banner,
    ) : LiveState

    /** The picture. [banner] is set while it lingers over the picture and null after. */
    data class Watching(
        val banner: Banner?,
    ) : LiveState

    /** The slate for a show that is missing or will not play. [upNext] names what follows, when known. */
    data class TechnicalDifficulties(
        val upNext: Banner?,
    ) : LiveState

    /** The StationBreak ident card: a gap in the schedule, or a show that ended early. */
    data class Ident(
        val upNext: Banner?,
    ) : LiveState

    /** The session ended: hand over to sign-in. */
    data object SignedOut : LiveState
}

/** What a [Banner] says about its show. */
enum class BannerLabel {
    /** Joining a show in progress under the snow (shows "joining N min in", or "Just started"). */
    TuningIn,

    /** The show that has just cut in at its start. */
    Now,

    /** The show that follows (slate and ident). */
    UpNext,

    /** A stand-in playing in place of a dead show. */
    FillingIn,
}

/**
 * The cable-box banner: all values are the schedule's own answer for the moment it was made (design goal 7). Times are
 * UTC milliseconds; the screen converts them to wall-clock time. [title] is the movie's or the series' name; [info]
 * holds the rest of the show's names (the episode line is made from it on the screen, from resources). [joinedMs] is
 * how far into the media the viewer joined (null when the show cut in at its start, and for "up next").
 */
data class Banner(
    val number: Int,
    val channelName: String,
    val label: BannerLabel,
    val title: String?,
    val info: ProgramInfo?,
    val slotStartMs: Long,
    val slotEndMs: Long,
    val joinedMs: Long?,
)
