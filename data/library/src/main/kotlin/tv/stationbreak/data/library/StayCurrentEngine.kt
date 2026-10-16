// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** What a pass did with the items it was given: new rows, rows that differed, and rows that were already identical. */
internal data class PassSummary(
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
) {
    operator fun plus(write: PageWrite): PassSummary {
        val added = inserted + write.inserted
        return PassSummary(added, updated + write.updated, unchanged + write.unchanged)
    }
}

/**
 * Keeps the saved library current after the first sync (spec 06, Unit 4): the incremental pass picks up what changed on
 * the server, quietly. Nothing here shows a screen or a message; the outcome is state in the saved library only.
 *
 * The incremental pass asks for the items saved since the last pass **began**, less [margin]. Jellyfin never sends a
 * per-item save time, so the moment a pass records is its own start on our clock, taken before the first request and
 * recorded only if the pass succeeds: whatever the server saved while the pass ran is fetched next time, and a pass
 * that dies leaves the window as it was. Pages are written with the same idempotent upsert the first sync uses, so an
 * item that comes back unchanged is not rewritten. Nothing here takes a token apart: the [Session] is only handed to
 * the [MediaServer].
 */
internal class StayCurrentEngine(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = FirstSyncEngine.DEFAULT_MARGIN,
    private val maxSkippedItems: Int = FirstSyncEngine.MAX_SKIPPED_ITEMS,
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /** What the last incremental pass wrote. For tests and diagnostics: nothing is shown to the user. */
    var summary = PassSummary()
        private set

    /**
     * Fetches what changed in [chosen] since the last successful pass (less the margin) for [account] and saves it.
     * Ends as [SyncOutcome.Cancelled] when there is nothing to top up (the first sync has not finished) or the account
     * stopped being the signed-in one.
     */
    suspend fun incremental(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): SyncOutcome =
        try {
            val last = repository.lastSuccess(account)
            if (last == null) SyncOutcome.Cancelled else Pass(session, account, clock.now()).incremental(chosen, last)
        } catch (_: AccountNotActiveException) {
            SyncOutcome.Cancelled
        }

    @Suppress("ReturnCount") // Early returns carry a failure out of the loops.
    private inner class Pass(
        private val session: Session,
        private val account: LibraryAccount,
        private val started: Instant,
    ) {
        private var skipped = 0

        suspend fun incremental(
            chosen: Set<ItemId>,
            last: Instant,
        ): SyncOutcome {
            summary = PassSummary()
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in chosen }
                    is ServerResult.Failure -> return fail(listed.error)
                }
            val since = last - margin
            for (library in libraries) {
                changedSince(library, since)?.let { return it }
            }
            repository.finishSync(account, started)
            return SyncOutcome.Completed
        }

        private suspend fun changedSince(
            library: Library,
            since: Instant,
        ): SyncOutcome? {
            val limit = PageLimit(pageSize)
            var next = 0
            while (true) {
                when (val fetched = server.fetchPage(session, library, next, since, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        if (++skipped > maxSkippedItems) return fail(MediaServerError.NotJellyfin())
                        next += 1
                    }

                    is Fetched.Page -> {
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        summary += repository.upsertItems(account, library.id, fetched.items)
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
}
