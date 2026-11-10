// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId

/*
 * What the first-sync, stay-current and add runners share (#654): reading the signed-in account, verifying the stored
 * session against it, and ending a run whose account signed out. Each runner keeps its own outcomes and log lines; only
 * the account and session handling is here.
 */

internal fun ActiveAccountEntity.toAccount() = LibraryAccount(ServerId(serverId), UserId(userId))

/** The accounts the saved library holds as signed in (one, when a user is). */
internal suspend fun RoomLibraryRepository.activeAccounts(): List<LibraryAccount> {
    val signedIn = dao.activeAccounts()
    return signedIn.map { it.toAccount() }
}

/** True when [account] is the one signed-in account (a sign-out, or another account in, is not). */
internal suspend fun RoomLibraryRepository.isOnlyActive(account: LibraryAccount): Boolean {
    val signedIn = activeAccounts()
    return signedIn == listOf(account)
}

/**
 * Verifies the stored session at [address] and runs [run] for it when it is restored and its account is still the one
 * signed in; signed out (or another account in) while the server was answering ends as [SyncOutcome.Cancelled]. No
 * session, or one the server rejected, is [SyncFailure.SessionExpired]. A server that could not be asked is
 * [unavailable] (by default [SyncOutcome.Failed] for its [SyncFailure]). It never activates an account or
 * writes the session.
 */
internal suspend fun SessionRepository.verifiedRun(
    address: ServerAddress,
    repository: RoomLibraryRepository,
    unavailable: suspend (MediaServerError) -> SyncOutcome = { SyncOutcome.Failed(it.toSyncFailure()) },
    run: suspend (Session, LibraryAccount) -> SyncOutcome,
): SyncOutcome =
    when (val restored = verify(address)) {
        is RestoreOutcome.Restored -> {
            val session = restored.session
            val account = LibraryAccount(session.server.id, session.userId)
            if (repository.isOnlyActive(account)) run(session, account) else SyncOutcome.Cancelled
        }

        RestoreOutcome.SignedOut, is RestoreOutcome.Rejected -> {
            SyncOutcome.Failed(SyncFailure.SessionExpired)
        }

        is RestoreOutcome.Unavailable -> {
            unavailable(restored.error)
        }
    }

/** Runs [block], and ends as [SyncOutcome.Cancelled] when its account signed out while it ran. */
internal inline fun cancelledOnSignOut(block: () -> SyncOutcome): SyncOutcome =
    try {
        block()
    } catch (_: AccountNotActiveException) {
        SyncOutcome.Cancelled
    }
