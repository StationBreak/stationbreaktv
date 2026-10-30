// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val writingNow = MutableStateFlow(false)

    // Runs in flight, not a flag: a retry that replaces the work may start before the run it cancelled has unwound.
    private var writers = 0

    /**
     * True while a run is writing: from the moment its session is verified and the account is still the one signed in
     * to the moment the run ends, whatever ended it. A run that stops before that (nothing stored, nobody signed in,
     * the server unreachable) never sets it. The same signal as [StayCurrentRunner.running], so the lineup refresher
     * holds the triggers a first sync's writes cause and checks once when it stops (#434, #480).
     */
    val running: StateFlow<Boolean> = writingNow.asStateFlow()

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
                    writing { FirstSyncEngine(repository, server, clock).run(session, account, chosen) }
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

    private suspend fun writing(run: suspend () -> SyncOutcome): SyncOutcome {
        count(+1)
        try {
            return run()
        } finally {
            count(-1)
        }
    }

    @Synchronized
    private fun count(change: Int) {
        writers += change
        writingNow.value = writers > 0
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
