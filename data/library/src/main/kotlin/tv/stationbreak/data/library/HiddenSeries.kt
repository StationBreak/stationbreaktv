// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.Session

/**
 * The most series one pass reads directly by ID (issue 604). A library that names more unlisted series than this is
 * not a split-folder oddity but something wrong with the listing; the rest wait for the next pass, and the log says so.
 */
internal const val HIDDEN_LOOKUPS_PER_PASS = 200

/**
 * What reading the unlisted series of one library came to. [resolved] were found and stored; [absent] the server says
 * do not exist (a series deleted while episodes still name it). Anything asked for and in neither (a failed read, or
 * past the cap) is unknown, and an unknown series is never tombstoned for it. [failure] is the error that stopped the
 * reads.
 */
internal class HiddenSeriesRead(
    val asked: Int,
    val resolved: Set<ItemId>,
    val absent: Set<ItemId>,
    val failure: MediaServerError?,
    val capped: Boolean,
) {
    /** The series a reconcile must not tombstone for not being listed: found, or not known to be gone. */
    fun keep(candidates: Set<ItemId>): Set<ItemId> = candidates - absent

    /** The counts as a log line: numbers only, no name or ID. */
    fun line(): String =
        "HiddenSeries found=$asked resolved=${resolved.size} absent=${absent.size} " +
            "failed=${failure != null} capped=$capped"

    companion object {
        val NONE = HiddenSeriesRead(0, emptySet(), emptySet(), null, false)
    }
}

/**
 * Reads each of [candidates] (series that live episodes of [library] name and the server's series list did not return)
 * once, directly by ID, and stores the ones that exist in [library]. Sequential, one request per distinct ID and never
 * one per episode, at most [HIDDEN_LOOKUPS_PER_PASS] in a fixed order. A failed read stops the reads and is returned in
 * [HiddenSeriesRead.failure]; what was found before it is stored all the same.
 */
internal suspend fun MediaServer.readHiddenSeries(
    session: Session,
    repository: RoomLibraryRepository,
    account: LibraryAccount,
    library: Library,
    candidates: Set<ItemId>,
): HiddenSeriesRead {
    if (candidates.isEmpty()) return HiddenSeriesRead.NONE
    val ordered = candidates.sortedBy { it.value }
    val wanted = ordered.take(HIDDEN_LOOKUPS_PER_PASS)
    val found = ArrayList<SeriesInfo>()
    val resolved = LinkedHashSet<ItemId>()
    val absent = LinkedHashSet<ItemId>()
    var failure: MediaServerError? = null
    for (id in wanted) {
        when (val read = seriesById(session, id)) {
            is ServerResult.Failure -> {
                failure = read.error
                break
            }

            is ServerResult.Success -> {
                val info = read.value
                if (info == null) {
                    absent += id
                } else {
                    found += info
                    resolved += id
                }
            }
        }
    }
    if (found.isNotEmpty()) repository.upsertSeries(account, library.id, found)
    return HiddenSeriesRead(wanted.size, resolved, absent, failure, capped = ordered.size > wanted.size)
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
 * The series [library]'s live episodes name that the server's series list did not return ([listed]) and that no other
 * library holds live: what a reconcile has to confirm one by one (the ones already stored included, because that is
 * what keeps them from being tombstoned for not being listed).
 */
internal suspend fun RoomLibraryRepository.unlistedReferencedSeries(
    account: LibraryAccount,
    library: Library,
    listed: Set<ItemId>,
): Set<ItemId> {
    val elsewhere = liveSeriesIdsAnywhere(account).toSet() - liveSeriesIds(account, library.id).toSet()
    return referencedSeriesIds(account, library.id).toSet() - listed - elsewhere
}
