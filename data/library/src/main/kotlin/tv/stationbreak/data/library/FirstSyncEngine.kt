// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.selection.FirstSyncLibraries
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How a run of the first sync ended. A closed set: nothing here carries a cause, an address or a token. */
internal sealed interface SyncOutcome {
    /** Every chosen library is saved and the account is idle. */
    data object Completed : SyncOutcome

    /** The sync stopped for [failure]. Whatever was saved stays, and so does the checkpoint. */
    data class Failed(
        val failure: SyncFailure,
    ) : SyncOutcome

    /** The account was signed out or replaced while the sync ran. Nothing more was written. */
    data object Cancelled : SyncOutcome
}

/**
 * The first sync (spec 06, Unit 3): copies the libraries the user ticked into the saved library, one page in memory at
 * a time. Each page and its checkpoint are written in one transaction, so a run that dies anywhere continues from the
 * last saved page and ends with the same data as one that did not. Every write is an idempotent upsert, so a replayed
 * page changes nothing.
 *
 * The walk reads on until a page comes back empty, because `libraryItems` is stable only for a library that does not
 * change. A post-walk pass then asks for what changed since the walk began (less [margin]) and upserts it, which
 * catches items added or renamed while the walk was in flight. Nothing here takes a token apart: the [Session] is only
 * handed to the [MediaServer].
 *
 * A page the server refuses as too large is asked for again at half the limit (and the smaller limit is kept for the
 * rest of the library). A page that will not decode is halved until it is one item, and that item is skipped, up to
 * [maxSkippedItems]: decoding is strict by design, so one malformed item must not stop a library.
 */
@Suppress("LongParameterList") // The sync's knobs, and where its counts-only log lines go.
internal class FirstSyncEngine(
    internal val repository: RoomLibraryRepository,
    private val server: MediaServer,
    internal val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = DEFAULT_MARGIN,
    private val maxSkippedItems: Int = MAX_SKIPPED_ITEMS,
    private val report: (String) -> Unit = {},
    private val librarySetReads: Int = LIBRARY_SET_READS,
) {
    /** How the collection pass at the end of the last run ended, or null if it did not get that far. */
    internal var lastCollectionPass: CollectionResult? = null
        private set

    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
        require(librarySetReads >= 1) { "librarySetReads must be at least 1" }
    }

    /**
     * Syncs [account]'s chosen libraries, using [session] to ask the server. The library set is the selection's
     * ([libraries], spec 26 Part 4 §2): it writes the cache's library rows under its own lock and gives the chosen
     * libraries. The account must be the signed-in one; if it stops being so, the run ends as [SyncOutcome.Cancelled]
     * with nothing more written.
     *
     * An empty set (no choice of this account's: none, or another account's left by a clear that failed) ends the run
     * as [SyncOutcome.Cancelled] with nothing written: no first sync begins and no success is recorded, so the setup
     * that replaces that choice asks for a first sync that reads the new libraries. An empty set read again after a
     * refusal, once the first sync began, ends it as a server failure instead, so it is not left running. A chosen
     * library whose write the cache refused (it was unticked, held or stamped meanwhile) is read again from a fresh
     * library set, at most [librarySetReads] sets in all; when it is still chosen and refused after that, the run
     * records a server failure and no success (the worker retries it, then the screen offers Retry).
     */
    suspend fun run(
        session: Session,
        account: LibraryAccount,
        libraries: FirstSyncLibraries,
    ): SyncOutcome =
        try {
            Run(session, account, clock.now()).sync(libraries)
        } catch (_: AccountNotActiveException) {
            SyncOutcome.Cancelled
        }

    // Early returns carry a failure out of the loops; an exception for flow would hide where the sync stopped.
    @Suppress("ReturnCount")
    private inner class Run(
        private val session: Session,
        private val account: LibraryAccount,
        private val started: Instant,
    ) {
        // The items skipped for failing to decode, across every library of this run.
        private val skips = SkipBudget(maxSkippedItems)

        // The earliest walk start across the libraries: what the next incremental pass counts from (a long walk, or a
        // resume, must not push it past changes made while an early library was being read).
        private var earliestWalk = started

        // One library's walk with no stamp (spec 26, Part 4 §4: LibraryPass(null)).
        private fun pass(): LibraryPass {
            val stamp: Long? = null
            return LibraryPass(repository, server, session, account, stamp, false, pageSize, margin, skips, report)
        }

        suspend fun sync(set: FirstSyncLibraries): SyncOutcome {
            val listed =
                when (val result = server.libraries(session)) {
                    is ServerResult.Success -> result.value
                    is ServerResult.Failure -> return fail(result.error)
                }
            var reads = 0
            var walking: List<Library>
            do {
                // The one selection writer (spec 26): the chosen and the adds kept, the rest held or gone.
                val chosen = set.firstSyncLibraries(account)
                // Nothing chosen is this account's: no first sync begins, and no success is recorded. Once one began
                // (the set read again after a refusal), it is not left running: the worker's retry reads the next one.
                if (chosen.isEmpty()) return if (reads == 0) SyncOutcome.Cancelled else failUnread()
                if (reads++ == 0) repository.beginFirstSync(account)
                walking = listed.filter { it.id in chosen }
                val refused =
                    when (val round = walk(walking)) {
                        is Round.Stopped -> return round.outcome
                        is Round.Walked -> round.refused
                    }
                if (refused.isEmpty()) break
                // A chosen library refused a write: read the set again, as long as there are reads left.
                if (reads == librarySetReads) return failUnread()
            } while (true)
            // A library the user ticked that the server no longer lists has nothing to fetch. One being added, held or
            // marked for a rewalk stays pending (markUnlisted leaves it): only the add itself lands an add (spec 26),
            // and only a walk's end clears a mark.
            val pending = repository.pendingLibraries(account)
            (pending - walking.map { it.id }.toSet()).forEach { repository.markUnlisted(account, it) }
            // Recorded as when the walk began, never when it ended: changes made during it are fetched next time.
            repository.finishSync(account, earliestWalk)
            collections()
            return SyncOutcome.Completed
        }

        // Walks the pending ones of [libraries] (the listed chosen ones): the libraries a write was refused for, or the
        // run's failure (recorded) when the server failed it.
        private suspend fun walk(libraries: List<Library>): Round {
            // The total is every library's count, known before the first page, so the bar never jumps back.
            val totals = HashMap<ItemId, Int>()
            for (library in libraries) {
                // The count the checkpoint saved, or else the ID-only call's (cheap, and it never decodes an item).
                totals[library.id] =
                    repository.checkpoint(account, library.id)?.totalCount
                        ?: when (val counted = server.libraryItemIds(session, library, 0, 1)) {
                            is ServerResult.Success -> counted.value.totalCount ?: 0
                            is ServerResult.Failure -> return Round.Stopped(fail(counted.error))
                        }
            }
            repository.setTotal(account, totals.values.sum())

            val pending = repository.pendingLibraries(account)
            val refused = HashSet<ItemId>()
            for (library in libraries) {
                // A library fetched by an earlier run keeps the start of its walk in its checkpoint.
                repository.checkpoint(account, library.id)?.let {
                    earliestWalk = minOf(earliestWalk, Instant.fromEpochMilliseconds(it.run))
                }
                if (library.id !in pending) continue
                when (val end = pass().walk(library, totals.getValue(library.id), started)) {
                    is PassEnd.Walked -> repository.markWalked(account, library.id)

                    // The library row refused a write: that library stops, pending, and the sync goes on.
                    is PassEnd.Refused -> refused += library.id

                    is PassEnd.Failed -> return Round.Stopped(fail(end.error))
                }
            }
            return Round.Walked(refused)
        }

        // The collections follow the library (spec 10, Unit 2). They are not part of "the first sync finished": a
        // failed pass is kept in [lastCollectionPass], leaves collectionsSynced false, and the periodic job retries it
        // (no re-list has completed, so its pass is a full one). Cancellation and a sign-out pass through.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun collections() {
            lastCollectionPass =
                try {
                    CollectionPass(repository, server, clock, pageSize, margin, repository.collectionRelist.interval)
                        .run(session, account)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (signedOut: AccountNotActiveException) {
                    throw signedOut
                } catch (_: Exception) {
                    CollectionResult(SyncOutcome.Failed(SyncFailure.Server), CollectionSummary())
                }
        }

        private suspend fun fail(error: MediaServerError): SyncOutcome {
            val failure = error.toSyncFailure()
            repository.failSync(account, failure)
            return SyncOutcome.Failed(failure)
        }

        // A chosen library the cache kept refusing, or a choice gone while the set was read again (spec 26, Task 4.4b):
        // not a success. Recorded as a server failure, so the worker retries it a few times and the screen then offers
        // Retry.
        private suspend fun failUnread(): SyncOutcome {
            repository.failSync(account, SyncFailure.Server)
            return SyncOutcome.Failed(SyncFailure.Server)
        }
    }

    // How one read of the library set went: walked, with the libraries whose writes were refused, or stopped.
    private sealed interface Round {
        class Walked(
            val refused: Set<ItemId>,
        ) : Round

        class Stopped(
            val outcome: SyncOutcome,
        ) : Round
    }

    companion object {
        /** The safety margin of the changed-since pass, for clock skew and items saved while the walk ran. */
        val DEFAULT_MARGIN: Duration = 10.minutes

        /** Items that may be skipped for failing to decode before the sync gives up on the server. */
        const val MAX_SKIPPED_ITEMS = 20

        /** How many times one run reads its library set while a chosen library's writes are refused. */
        const val LIBRARY_SET_READS = 3
    }
}

/** What went wrong, in the closed set the saved library reports. */
internal fun MediaServerError.toSyncFailure(): SyncFailure =
    when (this) {
        is MediaServerError.SessionExpired,
        is MediaServerError.ServerMismatch,
        is MediaServerError.HttpUnauthorized,
        -> {
            SyncFailure.SessionExpired
        }

        is MediaServerError.DnsFailure,
        is MediaServerError.ConnectionRefused,
        is MediaServerError.NetworkUnreachable,
        is MediaServerError.Timeout,
        is MediaServerError.TlsUntrusted,
        is MediaServerError.TlsHostnameMismatch,
        is MediaServerError.TlsCertificateExpired,
        is MediaServerError.TlsCertificateNotYetValid,
        is MediaServerError.TlsCertificateChanged,
        is MediaServerError.TlsHandshakeFailed,
        is MediaServerError.NotTls,
        -> {
            SyncFailure.Network
        }

        else -> {
            SyncFailure.Server
        }
    }
