// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.SyncFailure

/** What the worker tells WorkManager when a run ends. */
enum class SyncVerdict {
    /** The sync finished. */
    Success,

    /** Try again later; WorkManager waits longer each time (exponential backoff). */
    Retry,

    /** Stop. Starting the sync again is the user's Retry, never a loop. */
    Failure,
}

/**
 * What to do with each way a run of the first sync can end (spec 06 task 3.6). A network failure retries for as long as
 * it lasts, with WorkManager's growing waits. A rejected session stops at once and never loops: signing in again is the
 * way out. A server failure (an answer the sync cannot use) is tried a few times and then stops, so a broken
 * server does not keep the TV busy for ever.
 */
internal object SyncRetryPolicy {
    /** How many runs a server failure may be retried after (the first run is attempt 0). */
    const val MAX_SERVER_ATTEMPTS = 3

    fun verdict(
        outcome: SyncOutcome,
        attempt: Int,
    ): SyncVerdict =
        when (outcome) {
            SyncOutcome.Completed -> {
                SyncVerdict.Success
            }

            SyncOutcome.Cancelled -> {
                SyncVerdict.Failure
            }

            is SyncOutcome.Failed -> {
                when (outcome.failure) {
                    SyncFailure.Network -> {
                        SyncVerdict.Retry
                    }

                    SyncFailure.SessionExpired -> {
                        SyncVerdict.Failure
                    }

                    SyncFailure.Server, SyncFailure.Suspect -> {
                        if (attempt < MAX_SERVER_ATTEMPTS) SyncVerdict.Retry else SyncVerdict.Failure
                    }
                }
            }
        }
}
