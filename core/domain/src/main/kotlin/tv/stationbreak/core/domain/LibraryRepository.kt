// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItem
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
@Suppress("TooManyFunctions") // One port by decision: the reads, the signals and the choice of libraries stay together.
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
     * The account signed in now (the one [AccountCache.useAccount] last named), or null while nobody is. It emits the
     * current value first and follows a change of account, and ends up null again when the active account signs out.
     */
    fun activeAccount(): Flow<LibraryAccount?>

    /**
     * The usable items of the [libraries] created before [createdBefore], ordered by item ID. An item that is deleted
     * (a tombstone), has no creation date, or has a missing, zero or negative running time is left out (S0.6.3): the
     * scheduler only ever sees items it can trust. F1.1 passes `epochStart - grace`.
     *
     * Not for the scheduler (spec 09, Vimes H4): it reads the current rows and hides tombstones, so an edit or a
     * deletion made after a day's cut-off would reshuffle that day. The scheduler reads [scheduleItems].
     */
    @Deprecated(
        "The scheduler must read scheduleItems, which holds the library as this TV saw it before the cut-off. " +
            "Kept only for its current tests; to be removed (spec 09, Granny M2).",
    )
    suspend fun snapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        createdBefore: Instant,
    ): List<MediaItem>

    /**
     * The library as this TV held it before [cutoff], in the form the scheduler takes (spec 09, Unit 2). The scheduler
     * passes `cutoff = S - G` for the day it builds, and every TV on the account then builds the same day, because an
     * edit seen at or after the cut-off is not in it (docs/rules-format, "Edits").
     *
     * For each item, in any of the [libraries], it returns the version held before the cut-off: the newest version this
     * TV first saw before it, or, when it has none that old (a new install, or an item first seen later), the oldest
     * version it holds. The libraries, the deletion time (kept until `deletedAt < cutoff`) and the checks below are all
     * applied to **that** version's fields, not to the item's current ones, so an item that moved library or came back
     * after the cut-off is judged as it was.
     *
     * It leaves out, and never rejects, what the scheduler would refuse (an unsupported kind, an item, series or
     * library ID that is not canonical, a missing runtime or creation time, a time beyond +-2^52 ms, a malformed
     * premiere date, a year outside
     * 1..9999, a negative season or episode, a movie with series data). The result is in canonical-ID order.
     *
     * [cutoff] must equal the `S - G` that `buildDay` uses for the day being built (the day's start minus its grace
     * window). Any other instant gives inputs that day was not built from, and TVs on the account can then differ.
     *
     * **The safe range is `cutoff >= now - 72 h`** (the longest epoch plus the largest grace window, Vimes L-1). The
     * saved library purges history more than 30 days old, so a cut-off older than that retention can find its held
     * version gone and is answered from a later copy (the current row, as for a new install). A coming day's cut-off is
     * always inside the range. It is a documented contract and not a `require`: the cut-off comes from the day being
     * built, and a read of a day long past should degrade to the newest copy, not throw on a device whose clock is off.
     * `PurgeSafetyPropertyTest` pins both sides of the range.
     *
     * **The scheduler must use this and never [snapshot]**, which hides deleted items and uses the current fields.
     */
    suspend fun scheduleItems(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): List<ScheduleItem>

    /**
     * The rule engine's input as this TV held it before [cutoff] (spec 10, Unit 3): the items, the series they belong
     * to and the collections, in one [RuleSnapshot]. This is the format's *library snapshot* (rules-format rule 5) as
     * held before `cutoff`. It is unrelated to the deprecated `snapshot`, which goes in #265.
     *
     * Everything is taken as held before the cut-off, with the rules of [scheduleItems]: the newest version this TV
     * first saw before it, or, when it has none that old, the oldest it holds. **The items are exactly what
     * [scheduleItems] returns for the same arguments**, in the same order, with the rating, genres, tags and season the
     * rules read beside them; an item the scheduler would refuse is not in it. The series are those the returned
     * episodes point to, as held before the cut-off (a tombstoned one is kept until `deletedAt < cutoff`; an episode
     * whose series is unknown has no entry). The collections are those not deleted before the cut-off, with the
     * members held before it, **whatever the [libraries] are**, because their members are IDs. `collectionsSynced`
     * says whether a full pass over the account's collections has succeeded yet. A series or collection whose own ID
     * is not canonical, and a collection member whose ID is not, are skipped.
     *
     * It is one read of one state: a write that lands while it runs is not seen. A channel that needs the scheduler's
     * items takes them from `RuleSnapshot.items` and does not call [scheduleItems] again for the same day.
     *
     * [cutoff] must equal the `S - G` of the day being built, as for [scheduleItems]. **The safe range is
     * `cutoff >= now - 72 h`**, the same documented contract (Vimes L-1) and not a `require`: an older cut-off degrades
     * to the newest copy and does not throw.
     *
     * A **planning read** (spec 12: choosing which channels a lineup holds) may pass `cutoff = now`, which is inside
     * the safe range. Such a snapshot is never used to build a day: a day is built only from the `S - G` cut-off.
     */
    suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot

    /**
     * The current names of [account]'s collections that are not deleted (spec 12), by ID. A [RuleSnapshot] holds no
     * names, so this is where a collection hint is matched. Each ID is normalised with `CanonicalId.parseOrNull`, and
     * only text that is not an ID at all is left out (the first name wins if two spellings give one ID). This is
     * the current state, not an as-of read, so it is for planning a lineup and never for building a day.
     */
    suspend fun collectionNames(account: LibraryAccount): CollectionNames

    /**
     * Signals that a whole collection pass of [account] completed. The value is the **start** time of the most recent
     * completed pass, and null until one has. It emits the current value first. It also emits null when the account's
     * saved data is cleared (it signed out, or another account became active), and Room may re-emit the same value,
     * for instance on a write to another account's rows. So a non-null value not seen before means a completed pass;
     * null, or a value seen before, is never a trigger. A consumer acts only on a non-null value that differs from the
     * last one it acted on, and never compares the value with `lastSuccess` or with now as an end time. The sync engine
     * finishes the items (and so [status]'s `lastSuccess` moves) *before* it runs the collection pass, so a reader that
     * needs the collections waits for this and not for `lastSuccess`. A pass that stops part-way does not signal.
     */
    fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?>

    /**
     * A count that rises each time a sync writes a change to [account]'s saved items, series or collections, or to
     * which libraries are chosen: an item or series or collection that is new, edited, deleted or back. A sync that
     * finds nothing to write, as most opens do, leaves it where it was. It emits the current value first. The count
     * is kept for the process only, so it starts from zero again after a restart: a reader compares what it reads
     * with what it read before, and never with a value from an earlier run. It says the library **could** differ from
     * what a reader last saw; it never says how.
     */
    fun contentChanges(account: LibraryAccount): Flow<Long>

    /**
     * [account]'s content generation: a number that is kept with the saved library and only goes up for that account.
     * It changes exactly when a committed write changed that account's library rows (an item, series or collection
     * that is new, edited, deleted or back, or the chosen libraries): the bump is in the same transaction as the write,
     * so a write that rolls back or finds everything as stored leaves it alone, and another account's writes never move
     * it. Because it is durable it can be compared across process restarts: a reader keeps the value it planned from
     * and finds the library changed since when the two differ, even if the process died before it could act. A cache
     * rebuild may reset it to zero, and when it does the comparison simply reads as "changed". It says the library
     * differs, never how. A suspend read and not a flow, since the reader decides at a trigger or at start.
     */
    suspend fun contentGeneration(account: LibraryAccount): Long

    /**
     * How many live items of the [libraries] a snapshot leaves out for a missing, zero or negative running time (the
     * diagnostics number). The rows stay stored.
     */
    @Deprecated(
        "Counts what snapshot leaves out. scheduleItems has its own drop counts, held internally for now (spec 09).",
    )
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
