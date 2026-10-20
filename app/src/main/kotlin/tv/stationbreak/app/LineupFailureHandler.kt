// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineExceptionHandler

/** The event name logged when something escapes the lineup refresher's own error handling. */
internal const val LINEUP_REFRESHER_FAILED = "RefresherFailed"

/**
 * A last-resort handler for the app scope that runs the lineup refresher (spec 12): whatever escapes it is logged by
 * event name only through [log], never with the exception, its message or any data, and does not crash the process.
 */
internal fun lineupFailureHandler(log: (String) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, _ -> log(LINEUP_REFRESHER_FAILED) }
