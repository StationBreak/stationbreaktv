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
 * What a switched-on channel holds in its broadcast day, in the viewer's terms (#757); the channel box and the
 * properties sheet both read this one value, so they never disagree. Read-only.
 *
 * - [totalMs]: everything the channel airs today, guests included.
 * - [titleCount]: the titles it is counted by: those whose home it is, or, for a channel with no home shows (a
 *   guest-only decade channel, a custom channel, the leftovers), every title it airs. A guest spot is never counted.
 * - [homeCount]: how many titles have their home here (zero for those channels).
 * - [homes]: up to [LISTED] of them, anchors first, then by hours (most first), then by title ID.
 * - [top]: up to [LISTED] titles holding the most hours (anchors have no say), then by title ID.
 *
 * A title is a show (all its episodes) or a film.
 */
data class ChannelHoldings(
    val totalMs: Long,
    val titleCount: Int,
    val homeCount: Int,
    val homes: List<HeldTitle>,
    val top: List<HeldTitle>,
) {
    /** The line the titles go on. */
    val list: HoldingsList get() = if (homeCount > 0) HoldingsList.HomeTo else HoldingsList.Mostly

    /** The titles that line names. */
    val listed: List<HeldTitle> get() = if (homeCount > 0) homes else top

    /** How many titles are counted but not named: the "+ N more". */
    val more: Int get() = (titleCount - listed.size).coerceAtLeast(0)

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

/** How features read what a channel holds. Read-only; none of it throws, but for cancellation. */
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
