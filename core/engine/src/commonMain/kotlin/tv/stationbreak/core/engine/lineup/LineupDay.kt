// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode

// "On the air on day d" (spec 23, D1): the one definition the planner, the edits and the day build (`core:domain`'s
// `LineupDays`) all read, so what the planner protects, what the basis keeps and what the day build airs agree.

/**
 * One broadcast day: from [startMs] up to [endMs], the next day's start (milliseconds since 1970). The day-boundary
 * predicates take one, so they are decided by the day and never by the moment asked about: every lookup of one day
 * agrees. `core:domain` makes a channel's from the scheduler's epochs; the planner and the edits, which see no
 * scheduler, make the lineup's from the boundary they are given ([endingAt], [startingAt]).
 */
data class LineupDay(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(endMs > startMs) { "A day ends after it starts" }
    }

    companion object {
        /** The lineup's day that ends at [boundaryMs], the next broadcast-day start: today, for the planner. */
        fun endingAt(boundaryMs: Long): LineupDay = LineupDay(boundaryMs - RecipeResolver.DAY_MS, boundaryMs)

        /** The lineup's day that starts at [boundaryMs]: the first day a change waiting for the boundary applies to. */
        fun startingAt(boundaryMs: Long): LineupDay = LineupDay(boundaryMs, boundaryMs + RecipeResolver.DAY_MS)
    }
}

/**
 * Whether this channel starts on a later day than [day]: switched on (by the planner) for a boundary at or after
 * the day's end ([LineupChannel.onSinceMs] a time ahead). Nothing of it airs on [day]. A viewer's switch-on in the
 * middle of a day (`onSinceMs` then) is on the air that very day.
 */
fun LineupChannel.startsAfter(day: LineupDay): Boolean = onSinceMs >= day.endMs

/**
 * Whether this channel has left the lineup by [day]: it leaves ([LineupChannel.leavesAtMs]) at or before the day's
 * start, so nothing of it airs on [day]. One that leaves at the next boundary is still on the air today.
 */
fun LineupChannel.leavesBy(day: LineupDay): Boolean = leavesAtMs.let { it != null && it <= day.startMs }

/** Whether this channel is on the air on [day]: on, started by then ([startsAfter]) and not left ([leavesBy]). */
fun LineupChannel.isOnAirOn(day: LineupDay): Boolean = state == ChannelState.On && !startsAfter(day) && !leavesBy(day)

/**
 * Whether this channel is curated on the day that starts at [dayStartMs] (spec 23, F2): an unedited starter whose
 * rules for that day ([rulesFor]) are in the curated order and whose recipe is one of [recipeKeys] (the catalog's: the
 * curation reads the recipe). Any other channel, a custom one, an edited starter and a leftovers channel, airs its own
 * pool. Says nothing of whether it is on the air ([isOnAirOn]).
 */
fun LineupChannel.isCuratedOn(
    dayStartMs: Long,
    recipeKeys: Set<String>,
): Boolean {
    val source = source
    return source is ChannelSource.Starter &&
        !source.edited &&
        source.rulesFor(dayStartMs).orderingMode == OrderingMode.Curated &&
        source.recipeKey in recipeKeys
}

/**
 * The curated on-set of [day]: the channels on the air that day ([isOnAirOn]) that are curated on it ([isCuratedOn]).
 * What the curation homes titles over that day when no basis holds it.
 */
fun Lineup.curatedOn(
    day: LineupDay,
    recipeKeys: Set<String>,
): Set<ChannelId> {
    val out = HashSet<ChannelId>()
    for (channel in channels) {
        if (channel.isOnAirOn(day) && channel.isCuratedOn(day.startMs, recipeKeys)) out += channel.id
    }
    return out
}
