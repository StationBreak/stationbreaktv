// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * What an app open starts (spec 06 Unit 4, owner answers 1 and 5). A small pure class: it reads the saved library's
 * status, whether a sync is running now, and the clock, and says which passes to run.
 *
 * - The ID reconciliation runs on **every** open.
 * - The incremental pass runs on open only when the last successful pass is **over** [interval] (6 hours) old.
 * - An open while a sync is running starts nothing.
 * - Before the first sync has ended (or with nobody signed in) there is nothing to top up: the first sync owns the
 *   library, and the progress screen starts it.
 * - The periodic job runs both, whatever the age.
 */
class SyncTriggerPolicy(
    private val clock: Clock = Clock.System,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    /** The passes to start for an open, given the signed-in account's [status] (null when nobody is signed in). */
    fun onOpen(
        status: LibraryStatus?,
        syncRunning: Boolean,
    ): SyncPlan {
        val lastSuccess = status?.lastSuccess
        return when {
            syncRunning || lastSuccess == null -> SyncPlan.None
            status.state is SyncState.FirstSyncRunning -> SyncPlan.None
            else -> SyncPlan(incremental = clock.now() - lastSuccess > interval, reconcile = true)
        }
    }

    /** The passes the periodic job runs: both. */
    fun periodic(): SyncPlan = SyncPlan.Both

    companion object {
        /** How old the last successful pass must be, on open, before the incremental pass runs (owner answer 5). */
        val DEFAULT_INTERVAL: Duration = 6.hours
    }
}
