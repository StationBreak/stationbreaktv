// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.MediaItem
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
internal class FirstSyncEngine(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = DEFAULT_MARGIN,
    private val maxSkippedItems: Int = MAX_SKIPPED_ITEMS,
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /**
     * Syncs [chosen] (the ticked libraries) for [account], using [session] to ask the server. The account must be the
     * signed-in one; if it stops being so, the run ends as [SyncOutcome.Cancelled] with nothing more written.
     */
    suspend fun run(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): SyncOutcome =
        try {
            Run(session, account, clock.now()).sync(chosen)
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
        private var skipped = 0

        suspend fun sync(chosen: Set<ItemId>): SyncOutcome {
            val listed =
                when (val result = server.libraries(session)) {
                    is ServerResult.Success -> result.value
                    is ServerResult.Failure -> return fail(result.error)
                }
            val libraries = listed.filter { it.id in chosen }
            repository.select(account, chosen)
            repository.beginFirstSync(account)

            // The total is every library's count, known before the first page, so the bar never jumps back.
            val totals = HashMap<ItemId, Int>()
            for (library in libraries) {
                // The count the checkpoint saved, or else the ID-only call's (cheap, and it never decodes an item).
                totals[library.id] =
                    repository.checkpoint(account, library.id)?.totalCount
                        ?: when (val counted = server.libraryItemIds(session, library, 0, 1)) {
                            is ServerResult.Success -> counted.value.totalCount ?: 0
                            is ServerResult.Failure -> return fail(counted.error)
                        }
            }
            repository.setTotal(account, totals.values.sum())

            val pending = repository.pendingLibraries(account)
            for (library in libraries) {
                if (library.id !in pending) continue
                sync(library, totals.getValue(library.id))?.let { return it }
            }
            // A library the user ticked that the server no longer lists has nothing to fetch.
            (pending - libraries.map { it.id }.toSet()).forEach { repository.markFetched(account, it) }
            repository.finishSync(account)
            return SyncOutcome.Completed
        }

        private suspend fun sync(
            library: Library,
            total: Int,
        ): SyncOutcome? {
            val checkpoint = repository.checkpoint(account, library.id)
            // The walk's start is the first run's, however many times the sync was started again (a resume keeps it).
            val run = checkpoint?.run ?: started.toEpochMilliseconds()
            val limit = PageLimit(pageSize)
            val walked =
                read(library, checkpoint?.nextStartIndex ?: 0, null, limit) { items, next, firstTotal ->
                    repository.writePage(account, library.id, items, next, firstTotal ?: total, run)
                }
            if (walked != null) return walked
            val since = Instant.fromEpochMilliseconds(run) - margin
            val caught =
                read(library, 0, since, limit) { items, _, _ ->
                    repository.upsertItems(account, library.id, items)
                }
            if (caught != null) return caught
            repository.markFetched(account, library.id)
            return null
        }

        /**
         * Reads [library] from [startAt] to the page that comes back empty, giving each page to [onPage] with the
         * position after it and, for the first page, the library's total. Null when it reached the end.
         */
        private suspend fun read(
            library: Library,
            startAt: Int,
            changedSince: Instant?,
            limit: PageLimit,
            onPage: suspend (items: List<MediaItem>, next: Int, firstTotal: Int?) -> Unit,
        ): SyncOutcome? {
            var next = startAt
            while (true) {
                when (val fetched = fetch(library, next, changedSince, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        if (++skipped > maxSkippedItems) return fail(MediaServerError.NotJellyfin())
                        next += 1
                        onPage(emptyList(), next, null)
                    }

                    is Fetched.Page -> {
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        onPage(fetched.items, next, if (fetched.startIndex == 0) fetched.totalCount else null)
                    }
                }
            }
        }

        private suspend fun fetch(
            library: Library,
            startIndex: Int,
            changedSince: Instant?,
            limit: PageLimit,
        ): Fetched {
            var asking = limit.size
            while (true) {
                when (val result = server.libraryItems(session, library, startIndex, asking, changedSince)) {
                    is ServerResult.Success -> {
                        return Fetched.Page(startIndex, result.value.totalCount, result.value.items)
                    }

                    is ServerResult.Failure -> {
                        val error = result.error
                        when {
                            // Too big for the response limit: half the size, and keep it for the rest of the library.
                            error is MediaServerError.ServerError && asking > 1 -> {
                                asking /= 2
                                limit.size = asking
                            }

                            // Will not decode: halve to find the item, for this page only.
                            error is MediaServerError.NotJellyfin && asking > 1 -> {
                                asking /= 2
                            }

                            error is MediaServerError.NotJellyfin -> {
                                return Fetched.Malformed
                            }

                            else -> {
                                return Fetched.Failed(error)
                            }
                        }
                    }
                }
            }
        }

        private suspend fun fail(error: MediaServerError): SyncOutcome {
            val failure = error.toSyncFailure()
            repository.failSync(account, failure)
            return SyncOutcome.Failed(failure)
        }
    }

    /** The page size in force for one library: only a response-size error makes it smaller. */
    private class PageLimit(
        var size: Int,
    )

    private sealed interface Fetched {
        class Page(
            val startIndex: Int,
            val totalCount: Int?,
            val items: List<MediaItem>,
        ) : Fetched

        /** One item that will not decode, found by halving the page down to it. */
        data object Malformed : Fetched

        class Failed(
            val error: MediaServerError,
        ) : Fetched
    }

    companion object {
        /** The safety margin of the changed-since pass, for clock skew and items saved while the walk ran. */
        val DEFAULT_MARGIN: Duration = 10.minutes

        /** Items that may be skipped for failing to decode before the sync gives up on the server. */
        const val MAX_SKIPPED_ITEMS = 20
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
