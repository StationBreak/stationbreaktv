// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val DEFAULT_PAUSE_HOLD_MINUTES = 60
private const val DEFAULT_MARGIN_KIB = 64
private const val KIB = 1024

/**
 * The tunables of the repair engine (spec 26), as named defaults. They are safe starting values, and none of them
 * changes what a day looks like, so every TV agrees whatever they are set to.
 *
 * - [pauseHold]: how long a library stays held after its last pinned programme ends, so a paused programme can still
 *   be resumed (read by the repair that makes a hold, Task 2.5).
 * - [maxFixes]: the most fixes of lost blocks the record keeps; the oldest go first.
 * - [fileMargin]: bytes kept free under the lineup file's cap (`LineupLimits.MAX_FILE_BYTES`), so the store never has
 *   to refuse a save of the record.
 */
internal data class RepairConfig(
    val pauseHold: Duration = DEFAULT_PAUSE_HOLD_MINUTES.minutes,
    val maxFixes: Int = 256,
    val fileMargin: Int = DEFAULT_MARGIN_KIB * KIB,
)
