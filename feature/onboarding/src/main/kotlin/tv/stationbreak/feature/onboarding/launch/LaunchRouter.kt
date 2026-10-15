// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import javax.inject.Inject

/** Where launch routing decided the app goes (spec 05, Unit 4). */
internal sealed interface LaunchRoute {
    /** Nothing is configured, or the stored server can't be used without asking the user again: the server screen. */
    data object NoServer : LaunchRoute

    /** A server, no usable session: sign-in with [server] handed over, and a line why if a session was refused. */
    data class SignIn(
        val server: ConnectedServer,
    ) : LaunchRoute

    /** A restored session and no library choice yet: the picker. */
    data class Libraries(
        val session: Session,
    ) : LaunchRoute

    /** A restored session and a saved choice: the placeholder "Setup complete" screen. */
    data object SetupComplete : LaunchRoute

    /** The server could not be asked. [address] is the stored one, for a certificate question about it. */
    data class Unavailable(
        val error: MediaServerError,
        val address: ServerAddress,
    ) : LaunchRoute
}

/**
 * Decides where the app opens (spec 05, Unit 4). It **reads the stored settings first**, which is what puts a stored
 * certificate pin in force (`PinSyncingServerSettings.load` applies it) before the first request of the run.
 *
 * - no stored server: [LaunchRoute.NoServer];
 * - a restored session: the setup is complete when a library choice is stored, otherwise the picker;
 * - signed out, or the session was refused: sign-in for the stored server, after a credential-free `connect` that
 *   names it and checks it is still there. A refused session is explained in one line;
 * - the server can't be asked: [LaunchRoute.Unavailable], whose Retry asks again. A changed certificate is one of
 *   these; the screen turns it into the trust question.
 *
 * A stored `http` address that needs a cleartext confirmation (ADR-0014) goes to the server screen, because the
 * confirmation was never stored with it and is not assumed.
 */
internal class LaunchRouter
    @Inject
    constructor(
        private val settings: ServerSettings,
        private val sessions: SessionRepository,
        private val mediaServer: MediaServer,
    ) {
        suspend fun route(): LaunchRoute {
            val record = settings.load() ?: return LaunchRoute.NoServer
            val address = record.address
            return when (val restored = sessions.restore(address)) {
                is RestoreOutcome.Restored -> restoredRoute(restored.session)
                RestoreOutcome.SignedOut -> signIn(address, because = null)
                is RestoreOutcome.Rejected -> signIn(address, because = restored.error)
                is RestoreOutcome.Unavailable -> LaunchRoute.Unavailable(restored.error, address)
            }
        }

        private suspend fun restoredRoute(session: Session): LaunchRoute =
            if (settings.loadLibraries().isNullOrEmpty()) LaunchRoute.Libraries(session) else LaunchRoute.SetupComplete

        private suspend fun signIn(
            address: ServerAddress,
            because: MediaServerError?,
        ): LaunchRoute {
            if (address.cleartextNeedsConfirmation) return LaunchRoute.NoServer
            return when (val found = mediaServer.connect(address)) {
                is ServerResult.Success -> LaunchRoute.SignIn(ConnectedServer(found.value, null, because))
                is ServerResult.Failure -> LaunchRoute.Unavailable(found.error, address)
            }
        }
    }
