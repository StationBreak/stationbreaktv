// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.Session
import java.util.concurrent.ConcurrentHashMap

/**
 * The most series one pass reads directly by ID (issue 604). A library that names more unlisted series than this is
 * not a split-folder oddity but something wrong with the listing; the rest wait for the next pass, and the log says so.
 */
internal const val HIDDEN_LOOKUPS_PER_PASS = 200

/**
 * What reading the unlisted series of one library came to. [resolved] were found and stored; [absent] the server twice
 * said do not exist (only a reconcile asks twice, and only then are they tombstoned). [unknown] could not be read for a
 * reason of that one series (the server's own error for it): nothing is stored for it and any stored copy is kept.
 * Anything asked for and in none of those (past the cap, or after a [failure]) is unknown too. [failure] is a
 * session-level error (an expired session, a server that cannot be reached) that stopped the reads: it is the only
 * thing that fails a pass. [suspect] is set when some came back absent and none was
 * found at all: more likely a server fault than a library of deleted shows, so none is called absent. A timeout fails
 * a pass only as the second read in a row. [refused] is set when the library row refused the write of what was found
 * (spec 26, schema 11: [PageResult.StampMismatch] or [PageResult.FirstSyncRunning]): nothing was stored, and the
 * caller stops that library's walk as it does for a refused page.
 */
@Suppress("LongParameterList") // One field per count of the log line.
internal class HiddenSeriesRead(
    val asked: Int,
    val resolved: Set<ItemId>,
    val absent: Set<ItemId>,
    val unknown: Set<ItemId>,
    val failure: MediaServerError?,
    val capped: Boolean,
    val suspect: Boolean = false,
    val refused: PageResult? = null,
) {
    /** The series a reconcile must not tombstone for not being listed: found, or not known to be gone. */
    fun keep(candidates: Set<ItemId>): Set<ItemId> = candidates - absent

    /** The counts as a log line: numbers only, no name or ID. */
    fun line(): String =
        "HiddenSeries found=$asked resolved=${resolved.size} absent=${absent.size} unknown=${unknown.size} " +
            "failed=${failure != null} capped=$capped suspect=$suspect"

    companion object {
        val NONE = HiddenSeriesRead(0, emptySet(), emptySet(), emptySet(), null, false)
    }
}

/**
 * What a pass remembers, in this process only, about the series it read by ID: which ones the server said were absent
 * (so an incremental pass does not ask again; a reconcile forgets them and asks afresh), and where the next pass's
 * capped batch starts, so a library with more unlisted series than the cap is covered over several passes.
 */
internal class HiddenSeriesMemory {
    private val absent = ConcurrentHashMap<Pair<LibraryAccount, ItemId>, Set<ItemId>>()
    private val offsets = ConcurrentHashMap<Pair<LibraryAccount, ItemId>, Int>()

    fun absent(
        account: LibraryAccount,
        library: ItemId,
    ): Set<ItemId> = absent[account to library].orEmpty()

    fun remember(
        account: LibraryAccount,
        library: ItemId,
        ids: Set<ItemId>,
    ) {
        absent[account to library] = ids
    }

    fun offset(
        account: LibraryAccount,
        library: ItemId,
    ): Int = offsets[account to library] ?: 0

    fun moveOffset(
        account: LibraryAccount,
        library: ItemId,
        to: Int,
    ) {
        if (to == 0) offsets.remove(account to library) else offsets[account to library] = to
    }
}

// An error about the session or the connection stops a pass, as it does the item walks; anything else is about the one
// series asked for (the server's own error, a reply that will not decode) and must never stop the sync.
private fun MediaServerError.isSessionLevel(): Boolean = toSyncFailure() != SyncFailure.Server

/**
 * Reads each of [candidates] (series that live episodes of [library] name and the server's series list did not return)
 * directly by ID, and stores the ones that exist in [library]. Sequential, one request per distinct ID and never one
 * per episode, at most [HIDDEN_LOOKUPS_PER_PASS] in a fixed order whose start moves on each pass that hit the cap
 * ([memory]). With [confirmAbsent] (a reconcile) a series that is not there is asked about a second time before it is
 * called absent, and if every one asked about is absent none is. Without it, ones the server already said were absent
 * are not asked again. A failure about one series makes it unknown and the reads go on; a session-level failure stops
 * them and is returned in [HiddenSeriesRead.failure]. What was found is stored all the same, under the walk's [stamp]
 * and [rewalk] (the guard of [RoomLibraryRepository.upsertSeries]); a refused write is [HiddenSeriesRead.refused].
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal suspend fun MediaServer.readHiddenSeries(
    session: Session,
    repository: RoomLibraryRepository,
    account: LibraryAccount,
    library: Library,
    candidates: Set<ItemId>,
    memory: HiddenSeriesMemory,
    confirmAbsent: Boolean,
    stamp: Long?,
    rewalk: Boolean,
): HiddenSeriesRead {
    if (confirmAbsent) memory.remember(account, library.id, emptySet())
    val pool = if (confirmAbsent) candidates else candidates - memory.absent(account, library.id)
    if (pool.isEmpty()) return HiddenSeriesRead.NONE
    val sorted = pool.sortedBy { it.value }
    val start = memory.offset(account, library.id) % sorted.size
    val ordered = sorted.drop(start) + sorted.take(start)
    val wanted = ordered.take(HIDDEN_LOOKUPS_PER_PASS)
    val capped = ordered.size > wanted.size
    memory.moveOffset(account, library.id, if (capped) (start + wanted.size) % sorted.size else 0)
    val found = ArrayList<SeriesInfo>()
    val resolved = LinkedHashSet<ItemId>()
    val unknown = LinkedHashSet<ItemId>()
    var failure: MediaServerError? = null
    var timeouts = 0

    // One round of reads: the IDs the server answered null for. A series-level failure makes the ID unknown.
    suspend fun round(ids: Collection<ItemId>): LinkedHashSet<ItemId> {
        val missing = LinkedHashSet<ItemId>()
        for (id in ids) {
            if (failure != null) break
            when (val read = seriesById(session, id)) {
                is ServerResult.Failure -> {
                    val error = read.error
                    if (error !is MediaServerError.Timeout) timeouts = 0
                    when {
                        // One slow read is that series' problem; a second in a row says the server is not answering.
                        error is MediaServerError.Timeout && ++timeouts < 2 -> unknown += id

                        error.isSessionLevel() -> failure = error

                        else -> unknown += id
                    }
                }

                is ServerResult.Success -> {
                    timeouts = 0
                    val info = read.value
                    if (info == null) {
                        missing += id
                    } else {
                        found += info
                        resolved += id
                    }
                }
            }
        }
        return missing
    }
    val first = round(wanted)
    val missing = if (confirmAbsent && first.isNotEmpty()) round(first) else first
    val written = if (found.isNotEmpty()) repository.upsertSeries(account, library.id, stamp, rewalk, found) else null
    val refused = written?.takeIf { it !is PageResult.Written }
    // Only a pair of reads that both found nothing makes a series absent; one that was not read twice is unknown.
    val settled = if (failure == null) missing else emptySet()
    val suspect = confirmAbsent && settled.isNotEmpty() && resolved.isEmpty()
    val absent = if (suspect) emptySet() else settled
    val remembered = if (confirmAbsent) absent else absent + memory.absent(account, library.id)
    memory.remember(account, library.id, remembered)
    return HiddenSeriesRead(wanted.size, resolved, absent, unknown, failure, capped, suspect, refused)
}

/**
 * The series [library]'s live episodes name that neither the series list returned nor the account already holds live:
 * what a first sync or an incremental pass still has to find.
 */
internal suspend fun RoomLibraryRepository.unstoredReferencedSeries(
    account: LibraryAccount,
    library: Library,
): Set<ItemId> = referencedSeriesIds(account, library.id).toSet() - liveSeriesIdsAnywhere(account).toSet()

/**
 * The series [library]'s live episodes name that the server's series list did not return ([listed]) and that none of
 * the [others] (the libraries reconciled alongside) holds live: what a reconcile has to confirm one by one (the ones
 * already stored included, because that is what keeps them from being tombstoned for not being listed).
 */
internal suspend fun RoomLibraryRepository.unlistedReferencedSeries(
    account: LibraryAccount,
    library: Library,
    listed: Set<ItemId>,
    others: List<Library>,
): Set<ItemId> {
    // Held live by another library this pass reconciles: that library's own list or reads keep it. A series held by a
    // library that is not reconciled (one the server dropped) is read again and moves here.
    val elsewhere = others.filter { it.id != library.id }.flatMapTo(HashSet()) { liveSeriesIds(account, it.id) }
    return referencedSeriesIds(account, library.id).toSet() - listed - elsewhere
}
