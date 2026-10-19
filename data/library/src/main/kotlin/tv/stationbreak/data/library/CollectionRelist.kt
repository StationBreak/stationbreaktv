// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * How often the cache lists every collection and re-reads every member list (spec 10, Unit 2, ADR-0015): a safety net
 * for change paths the incremental pass cannot see. A device-only tunable, so it lives here in app config and not in
 * the rules, like [HistoryRetention]. The default is the incremental interval; raising it is allowed only after the
 * member-deletion cascade has shipped and its contract test passes (Vimes M5). Seasons are never tombstoned, so the
 * cascade cannot remove a deleted Season member and only this re-list does: raising the interval also needs a season
 * reconcile, or acceptance that a deleted season stays in a collection for up to the new interval (Vimes L3, #291).
 */
data class CollectionRelist(
    val interval: Duration = DEFAULT_INTERVAL,
) {
    init {
        require(interval.isPositive()) { "The re-list interval must be positive" }
    }

    companion object {
        /** The default: 6 h, the periodic job's own interval. */
        val DEFAULT_INTERVAL: Duration = 6.hours
    }
}
