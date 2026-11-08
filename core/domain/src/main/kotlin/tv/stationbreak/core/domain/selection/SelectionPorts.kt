// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library

/** What a landing of an added library came to, for the add pass. */
enum class LandResult {
    /** The library is chosen now, and its rows are a day's inputs. */
    Landed,

    /** The stamp is not the one being added (a newer tick, an untick or a setup came between): nothing to land. */
    NotCurrent,

    /** The cache holds no proof that the add's read under this stamp ended: the add pass reads it again. */
    Unproven,

    /**
     * Not landed now, nothing written: the save did not commit (`Unsaved`, `UndoFailed`, `Refused` by the record), the
     * lineup or the record moved twice running (`Stale`), the saved selection could not be read (`SelectionUnknown`),
     * or the seq had already been saved (`AlreadySaved`). Not every one of those is a passing fault: the add pass caps
     * its retries (Task 4.4b).
     */
    Retry,
}

/**
 * The add pass's side of the selection (spec 26, Part 4 §2): it lands an add once its read has ended, and asks for
 * the library cache to be put in line with the stored selection after a write the cache refused.
 */
interface AddLandings {
    /** Lands the add of [id] under [stamp] for [account]: saved as chosen, then landed in the cache. */
    suspend fun passLanded(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
    ): LandResult

    /** Puts the library cache in line with the stored selection. */
    suspend fun reconcile()
}

/** The first sync's side of the selection: the library set it writes and reads, under the selection's lock. */
fun interface FirstSyncLibraries {
    /** Writes [account]'s library set from the stored selection and gives the chosen libraries, which it reads. */
    suspend fun firstSyncLibraries(account: LibraryAccount): Set<ItemId>
}

/** Stay current's side of the selection: each listing of the server's libraries it reads (the two-read rule). */
fun interface ServerListings {
    /** Follows one listing of [account]'s libraries. */
    suspend fun followServer(
        account: LibraryAccount,
        listed: ServerResult<List<Library>>,
    )
}

/** The app start's side of the selection: the one resume, run before anything else reads or writes it. */
fun interface SelectionResume {
    /** Resumes the stored selection; it completes the selection's [SelectionReady] however it ends. */
    suspend fun resumeAtStart()
}
