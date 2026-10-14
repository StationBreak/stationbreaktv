// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.Session

/**
 * The steps of a Quick Connect sign-in (S0.3.2). The secret that finishes it is never in a state: only the
 * human-readable [CodeIssued.code] the user types on another device.
 */
sealed interface QuickConnectState {
    /** The server issued a code for the user to approve on a signed-in device. */
    data class CodeIssued(
        val code: String,
    ) : QuickConnectState

    data object Waiting : QuickConnectState

    data class SignedIn(
        val session: Session,
    ) : QuickConnectState

    /** The server stopped waiting, or the client-side limit (5 minutes) ran out. */
    data object Expired : QuickConnectState

    data object Denied : QuickConnectState

    /** The flow couldn't run. `QuickConnectDisabled` tells the UI to offer password sign-in instead. */
    data class Failed(
        val error: MediaServerError,
    ) : QuickConnectState
}
