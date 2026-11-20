// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.RevokeOutcome
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.LibrarySetup

/** Who is signed in, for the Libraries screen's "Signed in to" line and the sign-out question (#336). */
internal interface AccountSignOut {
    /** The signed-in server's name (never its address), or null when nobody is signed in or it has none. */
    fun serverName(): String?

    /**
     * Signs the user out on this TV (memory, store, library cache and choice), as Switch user does, and asks the server
     * to forget the token. False when the server could not be told: the TV is signed out, but its device entry is still
     * valid on the server until it is removed there.
     */
    suspend fun signOut(): Boolean

    /** No sign-out: nobody is signed in. For tests that do not exercise it. */
    object None : AccountSignOut {
        override fun serverName(): String? = null

        override suspend fun signOut() = true
    }
}

/** [AccountSignOut] over the app's one [SessionRepository], as "Signed in as"'s Switch user does it. */
internal class SessionSignOut(
    private val sessions: SessionRepository,
    private val setup: LibrarySetup,
) : AccountSignOut {
    // The server's own name, never its address; none when it is blank.
    override fun serverName(): String? =
        sessions.current.value
            ?.server
            ?.name
            ?.takeIf { it.isNotBlank() }

    // Runs to its end even if the screen goes: once chosen, nothing may find this session to use.
    override suspend fun signOut(): Boolean =
        withContext(NonCancellable) {
            val session = sessions.current.value
            val revoked =
                if (session == null) {
                    // Nothing held (the start could not reach the server): still leave nothing usable stored behind.
                    sessions.clear()
                } else {
                    sessions.signOut(session) == RevokeOutcome.Revoked
                }
            // The next user chooses their libraries afresh, so a restart can't find setup done for this one.
            setup.clear()
            revoked
        }
}
