// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineExceptionHandler

/** The event logged when something escapes a background job's own handling (the scope runs more than the lineup). */
internal const val BACKGROUND_JOB_FAILED = "BackgroundJobFailed"

/**
 * A last-resort handler for the app scope that runs the lineup refresher (spec 12): whatever escapes it is logged by
 * event name only through [log], never with the exception, its message or any data, and does not crash the process.
 */
internal fun lineupFailureHandler(log: (String) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, _ -> log(BACKGROUND_JOB_FAILED) }
