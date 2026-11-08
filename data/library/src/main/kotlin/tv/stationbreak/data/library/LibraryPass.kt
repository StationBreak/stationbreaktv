// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.Session
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How one walk of a library ended (see [LibraryPass]). Nothing here carries a cause, an address or a token. */
internal sealed interface PassEnd {
    /** The walk reached its end. [run] is when it began (its checkpoint's, for a walk resumed): for the end mark. */
    data class Walked(
        val run: Long,
    ) : PassEnd

    /** The library row refused a write ([result]): nothing of it was written, and the walk stopped there. */
    data class Refused(
        val result: PageResult,
    ) : PassEnd

    /** The server failed the walk with [error]. What was saved stays, and so does the checkpoint. */
    data class Failed(
        val error: MediaServerError,
    ) : PassEnd
}

/** The items that may be skipped for failing to decode across the walks that share it, before they give up. */
internal class SkipBudget(
    private val max: Int,
) {
    private var skipped = 0

    /** Counts one more item skipped; false once that is more than the budget allows. */
    fun skip(): Boolean = ++skipped <= max
}

/**
 * One walk of a library (spec 26, Part 4 §4): its pages from the checkpoint to the page that comes back empty, the
 * catch-up of what changed since the walk began (less [margin]), its series (the walk and the catch-up), and the
 * series its episodes name that the series list leaves out. Every write goes through the cache's guarded writes under
 * [stamp] (the add's stamp for an add walk, null for the first sync's walk and the rewalk), with [rewalk] set for a
 * rewalk's, so a library that was dropped, held or stamped since stops at once with nothing written.
 *
 * The walk writes no end mark of its own: the first sync's walk ends with `markWalked`, the add walk with its proof
 * (`markAddWalked`), the rewalk with `finishRewalk`. It records no failure in the account's sync state either: what a
 * failure means is the caller's.
 */
@Suppress("LongParameterList", "ReturnCount") // The walk's own knobs; each early return is where it stopped.
internal class LibraryPass private constructor(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val session: Session,
    private val account: LibraryAccount,
    private val stamp: Long?,
    private val rewalk: Boolean,
    private val pageSize: Int,
    private val margin: Duration,
    private val skips: SkipBudget,
    private val report: (String) -> Unit = {},
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /**
     * Walks [library] from its checkpoint. [total] is the library's count when the caller knows it (the first sync
     * counts each library first): each checkpoint saves the server's own count on the first page and [total] on the
     * others, as the first sync always has. With no [total] (the add walk, the rewalk), the count this walk has seen
     * goes on instead (the checkpoint's, then the first page's), so no later checkpoint saves none. [started] is when
     * this run began: the walk's start for a library with no checkpoint.
     */
    suspend fun walk(
        library: Library,
        total: Int?,
        started: Instant,
    ): PassEnd {
        val checkpoint = repository.checkpoint(account, library.id)
        // The walk's start is the first run's, however many times it was started again (a resume keeps it).
        val run = checkpoint?.run ?: started.toEpochMilliseconds()
        var seen = checkpoint?.totalCount
        val limit = PageLimit(pageSize)
        read(library, checkpoint?.nextStartIndex ?: 0, null, limit) { items, next, firstTotal ->
            if (firstTotal != null) seen = firstTotal
            repository.writePage(account, library.id, stamp, rewalk, items, next, firstTotal ?: total ?: seen, run)
        }?.let { return it }
        val since = Instant.fromEpochMilliseconds(run) - margin
        read(library, 0, since, limit) { items, _, _ ->
            repository.upsertItems(account, library.id, stamp, rewalk, items)
        }?.let { return it }
        // The series follow the library's items, through the same page loop and from the same cursor: the walk's
        // start less the margin. They are written by upsert, so a resume simply reads them again from the start.
        readSeries(library, null, limit)?.let { return it }
        readSeries(library, since, limit)?.let { return it }
        readHiddenSeries(library)?.let { return it }
        return PassEnd.Walked(run)
    }

    // The series the library's episodes name that the server's series list left out (issue 604): read by ID, once
    // each, after the list's own. A series the server fails on is only counted and left for a later pass; only a
    // session-level failure (an expired session, a server that cannot be reached) fails the walk.
    private suspend fun readHiddenSeries(library: Library): PassEnd? {
        val missing = repository.unstoredReferencedSeries(account, library)
        val read =
            server.readHiddenSeries(
                session,
                repository,
                account,
                library,
                missing,
                HiddenSeriesMemory(),
                confirmAbsent = false,
                stamp = stamp,
                rewalk = rewalk,
            )
        if (read.asked > 0) report(read.line())
        read.failure?.let { return PassEnd.Failed(it) }
        return read.refused?.let { PassEnd.Refused(it) }
    }

    /**
     * Reads [library] from [startAt] to the page that comes back empty, giving each page to [write] with the position
     * after it and, for the first page, the library's total. Null when it reached the end.
     */
    private suspend fun read(
        library: Library,
        startAt: Int,
        changedSince: Instant?,
        limit: PageLimit,
        write: suspend (items: List<MediaItem>, next: Int, firstTotal: Int?) -> PageResult,
    ): PassEnd? {
        var next = startAt
        while (true) {
            when (val fetched = server.fetchPage(session, library, next, changedSince, limit)) {
                is Fetched.Failed -> {
                    return PassEnd.Failed(fetched.error)
                }

                Fetched.Malformed -> {
                    if (!skips.skip()) return PassEnd.Failed(MediaServerError.NotJellyfin())
                    next += 1
                    refusal(write(emptyList(), next, null))?.let { return it }
                }

                is Fetched.Page -> {
                    if (fetched.items.isEmpty()) return null
                    next += fetched.items.size
                    val firstTotal = if (fetched.startIndex == 0) fetched.totalCount else null
                    refusal(write(fetched.items, next, firstTotal))?.let { return it }
                }
            }
        }
    }

    /** Reads [library]'s series from the start, saving each page. Null when it reached the end. */
    private suspend fun readSeries(
        library: Library,
        changedSince: Instant?,
        limit: PageLimit,
    ): PassEnd? {
        var next = 0
        while (true) {
            when (val fetched = server.fetchSeriesPage(session, library, next, changedSince, limit)) {
                is Fetched.Failed -> {
                    return PassEnd.Failed(fetched.error)
                }

                Fetched.Malformed -> {
                    if (!skips.skip()) return PassEnd.Failed(MediaServerError.NotJellyfin())
                    next += 1
                }

                is Fetched.Page -> {
                    if (fetched.items.isEmpty()) return null
                    next += fetched.items.size
                    val written = repository.upsertSeries(account, library.id, stamp, rewalk, fetched.items)
                    refusal(written)?.let { return it }
                }
            }
        }
    }

    // A write the library row refused, as the walk's end; null for one it took.
    private fun refusal(result: PageResult): PassEnd? {
        if (result is PageResult.Written) return null
        return PassEnd.Refused(result)
    }

    companion object {
        /** The safety margin of the changed-since pass, for clock skew and items saved while the walk ran. */
        val DEFAULT_MARGIN: Duration = 10.minutes

        /** Items that may be skipped for failing to decode before a walk gives up on the server. */
        const val MAX_SKIPPED_ITEMS = 20

        /** The first sync's walk: no stamp, with the run's page size, margin and shared skip budget. */
        @Suppress("LongParameterList") // The first sync's own knobs, one for one.
        fun firstSync(
            repository: RoomLibraryRepository,
            server: MediaServer,
            session: Session,
            account: LibraryAccount,
            pageSize: Int,
            margin: Duration,
            skips: SkipBudget,
            report: (String) -> Unit,
        ): LibraryPass =
            LibraryPass(
                repository = repository,
                server = server,
                session = session,
                account = account,
                stamp = null,
                rewalk = false,
                pageSize = pageSize,
                margin = margin,
                skips = skips,
                report = report,
            )

        /** The add walk of a library being added under [stamp]. */
        fun add(
            repository: RoomLibraryRepository,
            server: MediaServer,
            session: Session,
            account: LibraryAccount,
            stamp: Long,
            report: (String) -> Unit,
        ): LibraryPass = background(repository, server, session, account, stamp, rewalk = false, report)

        /** The rewalk of a chosen library marked for one: no stamp, and every write checks no first sync runs. */
        fun rewalk(
            repository: RoomLibraryRepository,
            server: MediaServer,
            session: Session,
            account: LibraryAccount,
            report: (String) -> Unit,
        ): LibraryPass = background(repository, server, session, account, null, rewalk = true, report)

        // A walk of the background passes: the largest page, the standard margin, a skip budget of its own.
        @Suppress("LongParameterList") // The walk's guard and what it reads with.
        private fun background(
            repository: RoomLibraryRepository,
            server: MediaServer,
            session: Session,
            account: LibraryAccount,
            stamp: Long?,
            rewalk: Boolean,
            report: (String) -> Unit,
        ): LibraryPass {
            val skips = SkipBudget(MAX_SKIPPED_ITEMS)
            val size = MediaServer.MAX_PAGE_SIZE
            return LibraryPass(repository, server, session, account, stamp, rewalk, size, DEFAULT_MARGIN, skips, report)
        }
    }
}
