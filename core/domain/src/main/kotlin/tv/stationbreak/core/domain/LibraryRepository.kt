// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Instant

/**
 * Whose library the saved copy holds: one user on one server. Every saved row is keyed by it, so a different account on
 * the same TV never sees the first account's library (ADR-0002, spec 06 Unit 2). It holds IDs only, never a token.
 */
data class LibraryAccount(
    val server: ServerId,
    val user: UserId,
)

/** Why the last sync did not finish. A closed set: no cause, URL or message travels with it. */
enum class SyncFailure {
    /** The server could not be reached. The saved library is untouched and still usable. */
    Network,

    /** The server rejected the session. The sync stops and does not loop. */
    SessionExpired,

    /** The server answered, but not with something the sync could use. */
    Server,

    /** The server's answer looked wrong (for example an empty library that had items), so nothing was changed. */
    Suspect,
}

/** Where the saved library stands. */
sealed interface SyncState {
    /** Nothing has been saved for this account yet. */
    data object NeverSynced : SyncState

    /** The account's first sync is running: [copied] items are saved so far, of [total] once the first page said. */
    data class FirstSyncRunning(
        val copied: Int,
        val total: Int?,
    ) : SyncState

    /** The saved library is complete as of the last success, and nothing is syncing in a way the user should see. */
    data object Idle : SyncState

    /** The last sync stopped for [failure]. Whatever was saved before is still there. */
    data class LastSyncFailed(
        val failure: SyncFailure,
    ) : SyncState
}

/** [state] with the time of the last successful sync ([lastSuccess], null until one ends), which is kept offline. */
data class LibraryStatus(
    val state: SyncState,
    val lastSuccess: Instant?,
)

/**
 * What the sign-in and sign-out path tells the saved library (spec 06 task 2.12): who is signed in now, and who has
 * signed out. A [SessionRepository] with no cache uses [None].
 */
interface AccountCache {
    /** [account] is the one signed in now: every other account's saved library is deleted. Its own is kept. */
    suspend fun useAccount(account: LibraryAccount)

    /** [account] signed out: its saved library is deleted. */
    suspend fun forget(account: LibraryAccount)

    /** No cache to tell. */
    data object None : AccountCache {
        override suspend fun useAccount(account: LibraryAccount) = Unit

        override suspend fun forget(account: LibraryAccount) = Unit
    }
}

/**
 * The saved copy of the user's chosen libraries (F0.6): what the scheduler reads and what the sync keeps current. No
 * database, Room, WorkManager or Jellyfin type appears here (ADR-0004); `data:library` implements it. Nothing throws
 * for a missing account: an account with nothing saved reads as empty.
 *
 * All times are UTC instants (stored as milliseconds). The library stays readable with no network.
 */
interface LibraryRepository : AccountCache {
    /**
     * The state and the last-success time of [account]'s library, as a flow that emits the current value first and
     * again on every change (the first-sync count included).
     */
    fun status(account: LibraryAccount): Flow<LibraryStatus>

    /**
     * The status of the account signed in now (the one [AccountCache.useAccount] last named), or null while nobody is.
     * It follows a change of account, and ends up null again when the active account signs out.
     */
    fun activeStatus(): Flow<LibraryStatus?>

    /**
     * The usable items of the [libraries] created before [createdBefore], ordered by item ID. An item that is deleted
     * (a tombstone), has no creation date, or has a missing, zero or negative running time is left out (S0.6.3): the
     * scheduler only ever sees items it can trust. F1.1 passes `epochStart - grace`.
     */
    suspend fun snapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        createdBefore: Instant,
    ): List<MediaItem>

    /**
     * How many live items of the [libraries] a snapshot leaves out for a missing, zero or negative running time (the
     * diagnostics number). The rows stay stored.
     */
    suspend fun excludedCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Int

    /**
     * Makes [libraries] the chosen set (owner answer 2, from `ServerSettings.loadLibraries()`): the saved rows of any
     * library left out are deleted, and a library not saved before is marked pending, to be fetched.
     */
    suspend fun select(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    )

    /** The chosen libraries that have not been fully fetched yet. */
    suspend fun pendingLibraries(account: LibraryAccount): Set<ItemId>
}

/**
 * Brings the saved library in line with the libraries the user ticked, as `ServerSettings` stores them. Returns the
 * chosen set, or null when none is stored (a new sign-in clears the choice), in which case the saved library is left
 * alone.
 */
suspend fun LibraryRepository.followSelection(
    account: LibraryAccount,
    settings: ServerSettings,
): Set<ItemId>? {
    val chosen = settings.loadLibraries() ?: return null
    select(account, chosen)
    return chosen
}
