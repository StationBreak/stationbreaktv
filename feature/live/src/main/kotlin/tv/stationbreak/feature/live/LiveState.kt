// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.ItemDetails

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

    /**
     * The slate for a show that is missing or will not play. [upNext] names what follows, when known; without it the
     * card still names its [channel] (design: "the card shows the channel only").
     */
    data class TechnicalDifficulties(
        val upNext: Banner?,
        val channel: ChannelTag? = null,
    ) : LiveState

    /**
     * The StationBreak ident card: a gap in the schedule, or a show that ended early. [upNext] and [channel] are as on
     * [TechnicalDifficulties]; both are null before a channel is chosen.
     */
    data class Ident(
        val upNext: Banner?,
        val channel: ChannelTag? = null,
    ) : LiveState

    /**
     * The channel number is moving (spec 15): [target] is where a commit would go. [overPicture] is true while the old
     * channel's picture is playing underneath (the banner only), false over snow or a slate (snow under the banner).
     */
    data class Choosing(
        val target: ChannelTag,
        val overPicture: Boolean,
    ) : LiveState

    /** The session ended: hand over to sign-in. */
    data object SignedOut : LiveState
}

/** A channel's number and name: all a card says when no show follows. */
data class ChannelTag(
    val number: Int,
    val name: String,
)

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
 * how far into the media the viewer joined (null when the show cut in at its start, and for "up next"). [next] is the
 * program that follows, when one does. [actions] is true for the banner the viewer asked for (Select or Info), which
 * carries the Info and Last buttons ([hasLast]: a previous channel exists, so Last has somewhere to go), and a Live
 * button while [behind] (the viewer is paused or behind live).
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
    val next: NextProgram? = null,
    val actions: Boolean = false,
    val hasLast: Boolean = false,
    val behind: Boolean = false,
)

/** The program after the one a [Banner] names: its title as the banner shows it, and its start (UTC milliseconds). */
data class NextProgram(
    val title: String,
    val startMs: Long,
)

/**
 * The program info overlay (spec 17): [banner] names the program; [details] are the server's extras, null until they
 * arrive and when they never do. While [loading] the overlay shows placeholders; when the fetch fails it simply drops
 * them, with no error text.
 */
data class LiveInfo(
    val banner: Banner,
    val details: ItemDetails?,
    val loading: Boolean,
)
