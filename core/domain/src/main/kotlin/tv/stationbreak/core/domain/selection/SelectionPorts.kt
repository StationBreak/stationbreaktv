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
     * Not landed now, nothing written, for a fault that may pass: the save did not commit (`Unsaved`, `UndoFailed`, a
     * plain save that did not commit, a record that could not be read), the record refused the write (`Refused`: a
     * save that failed, or a record that does not fit, which the record does not tell apart), or the lineup or the
     * record moved twice running (`Stale`). The add pass tries again, a few times per stamp, waiting longer each time
     * (Task 4.4b).
     */
    Retry,

    /**
     * Not landed, nothing written, and trying again in this process would not change that: the saved selection could
     * not be read (`SelectionUnknown`), or the seq had already been saved (`AlreadySaved`). The add pass stops trying
     * this stamp; the next start's resume asks again.
     */
    Failed,
}

/**
 * The add pass's side of the selection (spec 26, Part 4 §2): it reads the adds, lands an add once its read has ended,
 * and asks for the library cache to be put in line with the stored selection after a write the cache refused. Only
 * the add pass calls these, from inside its own worker, so none of them ever asks for the add pass again (that would
 * replace the very worker that called); the worker reads the adds again itself.
 */
interface AddLandings {
    /** The libraries [account] is adding, each with its stamp; empty when the stored choice is not its own. */
    suspend fun pendingAdds(account: LibraryAccount): Map<ItemId, Long>

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

/**
 * Stay current's side of the selection: the one listing of the server's libraries each run reads (the two-read rule).
 */
fun interface ServerListings {
    /** Follows one listing of [account]'s libraries: stay current hands over one listing per run. */
    suspend fun followServer(
        account: LibraryAccount,
        listed: ServerResult<List<Library>>,
    )
}

/**
 * Stay current's side of the holds (spec 26, Part 4 D12): the only dedicated expiry of holds. After a run whose
 * incremental pass completed, the libraries whose hold has ended leave the cache. The selection writes the cache from
 * the stored selection with no new hold, under its lock, so the same move code runs as for an untick. Every other
 * cache write of the selection (`applySelection`, the resume's included) also lets an ended hold go, and its
 * held-before-stamp path (D1) can still place one.
 *
 * The cache trusts the clock for this only within an hour (the pause hold) of the account's last successful sync: past
 * that, no hold expires in the call. So in practice holds expire in a run whose incremental pass completed (that pass
 * records the success just before), and the resume's expiry acts only within that hour. While the selection is gone no
 * pass runs, and none of these expiries do either.
 */
fun interface HoldExpiry {
    /** Expires [account]'s holds that have ended; nothing for an account that is not the signed-in one. */
    suspend fun expireHolds(account: LibraryAccount)
}

/** The app start's side of the selection: the one resume, run before anything else reads or writes it. */
fun interface SelectionResume {
    /** Resumes the stored selection; it completes the selection's [SelectionReady] however it ends. */
    suspend fun resumeAtStart()
}

/**
 * The background read of the libraries being added (spec 26, Part 4). [restart] only asks for it: it replaces any read
 * under way with one that reads the libraries being added as they are stored now, and never waits for it.
 */
fun interface LibraryPasses {
    fun restart()

    companion object {
        /** Nothing reads the added libraries: for a build without the add pass. */
        val None: LibraryPasses = LibraryPasses {}
    }
}
