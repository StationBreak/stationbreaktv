// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest

/** The part of WorkManager the periodic stay-current job uses, so a test can stand in for it. */
internal interface PeriodicEnqueuer {
    fun enqueueUniquePeriodic(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )
}

/** The work that keeps the saved library current after the first sync (spec 06, Unit 4). */
object StayCurrentWork {
    /** The unique name of the on-open work: an open during a sync that is queued or running starts nothing. */
    const val OPEN_NAME = "stationbreak.stay-current.open"

    /** The unique name of the periodic job. */
    const val PERIODIC_NAME = "stationbreak.stay-current.periodic"
}
