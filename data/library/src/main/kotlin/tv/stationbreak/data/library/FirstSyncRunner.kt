// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock

/**
 * What the worker runs (spec 06 task 3.4): reads the stored server, the ticked libraries and the session from the
 * app's own stores, the way the app does at launch, and hands them to the engine. Nothing is passed in from
 * WorkManager, so no token is in the work's input data. It only verifies the session: it never activates an account
 * or writes the session, so a sign-out that lands while the server is answering is not undone.
 */
@Suppress("ReturnCount") // Each early return is a reason there is nothing to sync yet.
class FirstSyncRunner(
    internal val settings: ServerSettings,
    internal val sessions: SessionRepository,
    internal val server: MediaServer,
    internal val repository: RoomLibraryRepository,
    internal val clock: Clock = Clock.System,
) {
    /** Runs the first sync once and says what WorkManager should do next. [attempt] is the run's retry count. */
    suspend fun run(attempt: Int): SyncVerdict = SyncRetryPolicy.verdict(outcome(), attempt)

    internal suspend fun outcome(): SyncOutcome {
        // Nothing stored, or nothing ticked yet: onboarding is not finished, so there is nothing to sync.
        val record = settings.load() ?: return SyncOutcome.Cancelled
        val chosen = settings.loadLibraries()?.takeIf { it.isNotEmpty() } ?: return SyncOutcome.Cancelled
        return when (val restored = sessions.verify(record.address)) {
            is RestoreOutcome.Restored -> {
                val session = restored.session
                val account = LibraryAccount(session.server.id, session.userId)
                // Signed out (or another account in) while the server was answering: nothing to sync.
                val active = repository.dao.activeAccounts()
                if (active != listOf(ActiveAccountEntity(account.server.value, account.user.value))) {
                    SyncOutcome.Cancelled
                } else {
                    FirstSyncEngine(repository, server, clock).run(session, account, chosen)
                }
            }

            RestoreOutcome.SignedOut, is RestoreOutcome.Rejected -> {
                SyncOutcome.Failed(SyncFailure.SessionExpired)
            }

            is RestoreOutcome.Unavailable -> {
                unavailable(restored.error)
            }
        }
    }

    // The server could not be asked: the signed-in account keeps what it has, and the screen can say why.
    private suspend fun unavailable(error: MediaServerError): SyncOutcome {
        val failure = error.toSyncFailure()
        val active = repository.dao.activeAccounts().singleOrNull()
        if (active != null) {
            try {
                repository.failSync(LibraryAccount(ServerId(active.serverId), UserId(active.userId)), failure)
            } catch (_: AccountNotActiveException) {
                // Signed out while this ran: there is nothing left to say it to.
            }
        }
        return SyncOutcome.Failed(failure)
    }
}
