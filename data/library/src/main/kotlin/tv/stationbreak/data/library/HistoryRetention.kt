// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.time.Duration.Companion.days

/**
 * How long the cache keeps item history (spec 09, Unit 3): a tombstone, or a version that was superseded, is purged
 * once it is more than [days] days old. A device-only tunable, so it lives here in app config and not in the rules:
 * what is kept never changes what a channel plays, only how far back [RoomLibraryRepository.scheduleItems] can look.
 *
 * It must exceed the longest look-back a coming day can need, the longest epoch plus the largest grace window
 * ([SAFE_LOOKBACK_MS], 72 h), read from the format's constants and not repeated here (Vimes L4).
 */
data class HistoryRetention(
    val days: Int = DEFAULT_DAYS,
) {
    init {
        require(days.days.inWholeMilliseconds > SAFE_LOOKBACK_MS) {
            "History is kept for longer than the longest epoch plus the largest grace window"
        }
    }

    /** The retention in milliseconds. */
    val millis: Long get() = days.days.inWholeMilliseconds

    companion object {
        /** The default retention in days (T11). */
        const val DEFAULT_DAYS = 30

        /**
         * The furthest back from now a cut-off may be and still get an unchanged answer from `scheduleItems` after a
         * purge: the longest epoch plus the largest grace window.
         */
        const val SAFE_LOOKBACK_MS = SchedulerSettings.MAX_EPOCH_MS + SchedulerSettings.MAX_GRACE_MS
    }
}
