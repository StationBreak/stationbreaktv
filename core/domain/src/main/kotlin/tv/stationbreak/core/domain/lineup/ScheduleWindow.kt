// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

/** Whether [ScheduleSource.schedule] may build a day it does not yet hold. */
enum class ScheduleBuild {
    /** Only days already built and kept; a day that is not is reported as missing, and nothing is built or awaited. */
    CachedOnly,

    /** Build the days the window needs, as [ScheduleSource.onAir] does. */
    AsNeeded,
}

/**
 * One window of airings for every visible channel (spec 17). [rows] are in number order and leave out channels that
 * are hidden at the start of the window. [complete] is false when a row was cut short for lack of a built (or
 * laid-out) day, or the lineup changed while the window was read. With [ScheduleBuild.CachedOnly] the rows then end
 * where the usable days do, and are empty when the day holding the start is not kept.
 */
data class ScheduleWindow(
    val rows: List<ChannelRow>,
    val complete: Boolean,
)

/**
 * One channel's part of a [ScheduleWindow]: [blocks] are every block overlapping the window, in order and contiguous;
 * the first may start before the window does. [builtUntilMs] is how far ahead the channel's schedule is built, as in
 * [ChannelOnAir.builtUntilMs] (milliseconds since 1970).
 */
data class ChannelRow(
    val channel: ChannelAiring,
    val blocks: List<AiringBlock>,
    val builtUntilMs: Long,
)
