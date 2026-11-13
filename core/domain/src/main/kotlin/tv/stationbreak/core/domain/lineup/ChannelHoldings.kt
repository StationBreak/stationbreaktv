// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** One title a channel holds: its [name], the [ms] of it the channel airs in its day, and whether it is an [anchor]. */
data class HeldTitle(
    val id: CanonicalId,
    val name: String,
    val ms: Long,
    val anchor: Boolean,
)

/** Which line lists a channel's titles: those that live there, or (a channel with none) those with the most hours. */
enum class HoldingsList { HomeTo, Mostly }

/**
 * Which titles have their home on a channel. The two cases must not be confused: [Known] with a [Known.count] of zero
 * is a fact (a custom channel, the leftovers, a guest-only channel: nothing lives here), and the channel box says
 * "Mostly:" and lists its biggest titles; [Unknown] is a day that was built before homes were kept, so nothing is said
 * of them at all: no "Home to:" and no "Mostly:" guess.
 */
sealed interface HomeShows {
    /** The day does not say which titles live on the channel (read back from a file of an older format). */
    data object Unknown : HomeShows

    /** [count] titles live on the channel; [titles] are the first up to [ChannelHoldings.LISTED] of them, named. */
    data class Known(
        val count: Int,
        val titles: List<HeldTitle>,
    ) : HomeShows
}

/**
 * What a switched-on channel holds in its broadcast day, in the viewer's terms (#757); the channel box and the
 * properties sheet both read this one value and its derived figures, so they never disagree. Read-only.
 *
 * - [totalMs]: everything the channel airs today, home shows and guests alike.
 * - [titleCount]: how many titles that is: a total of what it airs, like the hours, not a count of guests. A title is
 *   a show (all its episodes) or a film.
 * - [homes]: the titles whose home is this channel, anchors first, then by hours (most first), then by title ID; or
 *   [HomeShows.Unknown]. A guest is never listed as a home.
 * - [top]: up to [LISTED] titles holding the most hours whatever their status (on a guest-only channel, its guests),
 *   then by title ID.
 * - [validUntilMs]: when the broadcast day ends and this must be read again (the default: no end known).
 */
data class ChannelHoldings(
    val totalMs: Long,
    val titleCount: Int,
    val homes: HomeShows,
    val top: List<HeldTitle>,
    val validUntilMs: Long = Long.MAX_VALUE,
) {
    /** The line the titles go on; null when the homes are unknown and so no list is shown. */
    val list: HoldingsList? get() =
        when (val shown = homes) {
            HomeShows.Unknown -> null
            is HomeShows.Known -> if (shown.count > 0) HoldingsList.HomeTo else HoldingsList.Mostly
        }

    /** Whether the list is "Home to:" (else "Mostly:", or none when [list] is null). */
    val homeTo: Boolean get() = list == HoldingsList.HomeTo

    /** The titles that line names (none for no list). */
    val listed: List<HeldTitle> get() =
        when (val shown = homes) {
            HomeShows.Unknown -> emptyList()
            is HomeShows.Known -> if (shown.count > 0) shown.titles else top
        }

    /** The names of [listed], in order. */
    val listedNames: List<String> get() = listed.map { it.name }

    /** How many titles the list leaves out: the "+ N more" (homes beyond those listed, or titles beyond the top). */
    val more: Int get() =
        when (val shown = homes) {
            HomeShows.Unknown -> 0
            is HomeShows.Known -> ((if (shown.count > 0) shown.count else titleCount) - listed.size).coerceAtLeast(0)
        }

    /** The hours the channel airs, to the nearest hour and never less than one. */
    val hours: Long get() = ((totalMs + MS_PER_HOUR / 2) / MS_PER_HOUR).coerceAtLeast(1)

    /** The whole days of [hours] (hours / 24), or null under two: the line says "about N days" from two up. */
    val days: Long? get() = (hours / HOURS_PER_DAY).takeIf { it >= MIN_DAYS }

    companion object {
        /** How many titles a line names. */
        const val LISTED = 6
        private const val MS_PER_HOUR = 3_600_000L
        private const val HOURS_PER_DAY = 24L
        private const val MIN_DAYS = 2L
    }
}

/**
 * How features read what a channel holds. Read-only; none of it throws, but for cancellation. [read] is main-safe: it
 * does its work off the caller's thread.
 */
interface ChannelHoldingsReader {
    /**
     * What channel [id] holds in its broadcast day at [at], or null while nobody is signed in, the channel is not
     * switched on, is hidden at [at] or has nothing to air that day. Worked out from the day's own curation, once for
     * the broadcast day.
     */
    suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelHoldings?

    companion object {
        /** Nothing is held: for a screen that has no lineup to read. */
        val None: ChannelHoldingsReader =
            object : ChannelHoldingsReader {
                override suspend fun read(
                    id: ChannelId,
                    at: Instant,
                ): ChannelHoldings? = null
            }
    }
}
