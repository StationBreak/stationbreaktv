// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.SignInRequest

/**
 * Forgets the Kids Mode PIN, with no PIN checked. It is the one way past a lost PIN, so it is not part of [KidsLock]:
 * only [KidsPinRecovery] calls it, after the server's administrator has signed in.
 */
interface KidsPinReset {
    /** Clears the PIN and turns the lock off (the cap is kept). False when the lock's file could not be written. */
    suspend fun resetPin(): Boolean
}

/** What [KidsPinRecovery.recover] came to. */
enum class PinRecovery {
    /** The administrator signed in: the PIN is cleared and Kids Mode is off. */
    Done,

    /** This TV is not signed in to a server, so there is no administrator to ask. */
    NoServer,

    /** The server refused the name or the password. */
    WrongCredentials,

    /** The server refused the sign-in for another reason (the user is locked out or disabled). */
    Refused,

    /** The name and password are right, but the user is not an administrator. */
    NotAdministrator,

    /** The server could not be reached or answered something unexpected. */
    Unreachable,

    /** The administrator signed in but the lock's file could not be written, so nothing changed. */
    Unavailable,
}

/**
 * The PIN's recovery (#789, free): a forgotten PIN is cleared by signing in to this TV's own Jellyfin server as one of
 * its administrators (instead of clearing the app's data, which also forgets the channels). The sign-in goes to the
 * address this TV is signed in at and nowhere else. Its session is never kept: it is revoked at once, unless the
 * server answered with the TV's own token (the administrator is the viewer signed in here), which is left alone so the
 * TV is not signed out. Wrong passwords are the server's to count and limit. Nothing here logs or returns the name or
 * password.
 */
class KidsPinRecovery(
    private val sessions: SessionRepository,
    private val server: MediaServer,
    private val reset: KidsPinReset,
) {
    /** Signs in with [username] and [password] (wiped when the call ends) and, for an administrator, resets the PIN. */
    suspend fun recover(
        username: String,
        password: CharArray,
    ): PinRecovery {
        val signedIn =
            sessions.current.value ?: run {
                password.fill('\u0000')
                return PinRecovery.NoServer
            }
        val request = SignInRequest(signedIn.address, username, password, signedIn.acknowledgement)
        return when (val result = server.signIn(request)) {
            is ServerResult.Failure -> {
                refusal(result.error)
            }

            is ServerResult.Success -> {
                val admin = result.value
                if (admin.token != signedIn.token) withContext(NonCancellable) { server.revoke(admin) }
                when {
                    !admin.isAdministrator -> PinRecovery.NotAdministrator
                    reset.resetPin() -> PinRecovery.Done
                    else -> PinRecovery.Unavailable
                }
            }
        }
    }

    private fun refusal(error: MediaServerError): PinRecovery =
        when (error) {
            is MediaServerError.HttpUnauthorized -> PinRecovery.WrongCredentials
            is MediaServerError.HttpForbidden -> PinRecovery.Refused
            else -> PinRecovery.Unreachable
        }
}
