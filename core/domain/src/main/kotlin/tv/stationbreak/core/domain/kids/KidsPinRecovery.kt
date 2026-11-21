// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.Session

/**
 * Forgets the Kids Mode PIN, with no PIN checked. It is the one way past a lost PIN, so it is not part of [KidsLock]:
 * only [KidsPinRecovery] calls it, after one of the server's administrators has approved.
 */
interface KidsPinReset {
    /** Clears the PIN and turns the lock off (the cap is kept). False when the lock's file could not be written. */
    suspend fun resetPin(): Boolean
}

/** The steps of [KidsPinRecovery.recover]. Every state but [Code] is the last. */
sealed interface PinRecovery {
    /** The code to approve with Quick Connect in the server's app or web page, signed in as an administrator. */
    data class Code(
        val code: String,
    ) : PinRecovery

    /** An administrator approved: the PIN is cleared and Kids Mode is off. */
    data object Done : PinRecovery

    /** This TV is not signed in to a server, so there is no administrator to ask. */
    data object NoServer : PinRecovery

    /** The code was approved by a user who is not an administrator; nothing changed. */
    data object NotAdministrator : PinRecovery

    /** The code ran out before anyone approved it. */
    data object Expired : PinRecovery

    /** The server refused the code. */
    data object Denied : PinRecovery

    /** Quick Connect is switched off on the server, so the PIN cannot be reset this way. */
    data object Disabled : PinRecovery

    /** The server could not be reached or answered something unexpected. */
    data object Unreachable : PinRecovery

    /** An administrator approved but the lock's file could not be written, so nothing changed. */
    data object Unavailable : PinRecovery
}

/**
 * The PIN's recovery (#789, free): a forgotten PIN is cleared once one of this TV's own Jellyfin server's
 * administrators approves a Quick Connect code the TV shows (instead of clearing the app's data, which also forgets the
 * channels). Nothing is typed on the TV. The request goes to the address this TV is signed in at and nowhere else. The
 * session the approval gives is never kept: it is revoked at once, unless the server answered with the TV's own token,
 * which is left alone so the TV is not signed out.
 */
class KidsPinRecovery(
    private val sessions: SessionRepository,
    private val server: MediaServer,
    private val reset: KidsPinReset,
) {
    /** Starts a Quick Connect request when collected; cancelling the collector stops it. Ends with a final state. */
    fun recover(): Flow<PinRecovery> =
        flow {
            val signedIn = sessions.current.value
            if (signedIn == null) {
                emit(PinRecovery.NoServer)
                return@flow
            }
            val steps =
                server.quickConnect(signedIn.address, signedIn.acknowledgement).transformWhile { state ->
                    val step = stepOf(state, signedIn)
                    if (step != null) emit(step)
                    step == null || step is PinRecovery.Code
                }
            emitAll(steps)
        }

    private suspend fun stepOf(
        state: QuickConnectState,
        signedIn: Session,
    ): PinRecovery? =
        when (state) {
            is QuickConnectState.CodeIssued -> PinRecovery.Code(state.code)
            QuickConnectState.Waiting -> null
            is QuickConnectState.SignedIn -> approved(state.session, signedIn)
            QuickConnectState.Expired -> PinRecovery.Expired
            QuickConnectState.Denied -> PinRecovery.Denied
            is QuickConnectState.Failed -> failed(state.error)
        }

    private suspend fun approved(
        approver: Session,
        signedIn: Session,
    ): PinRecovery {
        if (approver.token != signedIn.token) withContext(NonCancellable) { server.revoke(approver) }
        return when {
            !approver.isAdministrator -> PinRecovery.NotAdministrator
            reset.resetPin() -> PinRecovery.Done
            else -> PinRecovery.Unavailable
        }
    }

    private fun failed(error: MediaServerError): PinRecovery =
        if (error is MediaServerError.QuickConnectDisabled) PinRecovery.Disabled else PinRecovery.Unreachable
}
