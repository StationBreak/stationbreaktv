// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val DEFAULT_PAUSE_HOLD_MINUTES = 60
private const val DEFAULT_MARGIN_KIB = 64
private const val KIB = 1024
private const val DEFAULT_PLAN_THREADS = 2
private const val MIN_PLAN_THREADS = 1
private const val MAX_PLAN_THREADS = 4

/**
 * The default [RepairConfig.pauseHold]: how long a library stays held after its last pinned programme ends. Public so
 * the library cache can size its hold-expiry clock guard to it (spec 26, Task 4.5).
 */
val DEFAULT_PAUSE_HOLD: Duration = DEFAULT_PAUSE_HOLD_MINUTES.minutes

/**
 * The tunables of the repair engine (spec 26), as named defaults and safe starting values. One instance serves the
 * service's saves of the record and the repairer (`LineupSeams.repairConfig`, read by `TodayRepairer.config`). They
 * are per TV, as the record is: [maxFixes] and [fileMargin] decide what a full record drops, and so can change what a
 * day looks like on this TV near the file's cap; [pauseHold] changes no day.
 *
 * - [pauseHold]: how long a library stays held after its last pinned programme ends, so a paused programme can still
 *   be resumed (`TodayRepairer.pauseHold`, for the caller that makes the hold).
 * - [maxFixes]: the most fixes of lost blocks the record keeps; the oldest go first.
 * - [fileMargin]: bytes kept free under the lineup file's cap (`LineupLimits.MAX_FILE_BYTES`), so the store never has
 *   to refuse a save of the record.
 * - [planThreads]: how many threads, at most, the repair's planning runs on (1 to 4; `TodayRepairer`'s `planning`
 *   dispatcher). They are normal priority, not the day-saves thread's lowest, so the plan is not starved by other
 *   work; fewer threads leave more CPU to the picture playing behind Settings. Changes no day.
 */
internal data class RepairConfig(
    val pauseHold: Duration = DEFAULT_PAUSE_HOLD,
    val maxFixes: Int = 256,
    val fileMargin: Int = DEFAULT_MARGIN_KIB * KIB,
    val planThreads: Int = DEFAULT_PLAN_THREADS,
) {
    init {
        require(planThreads in MIN_PLAN_THREADS..MAX_PLAN_THREADS) { "planThreads $planThreads is not in 1..4" }
    }
}
