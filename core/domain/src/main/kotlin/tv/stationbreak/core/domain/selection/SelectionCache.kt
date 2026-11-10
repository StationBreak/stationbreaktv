// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock

/**
 * The library cache's side of the selection (spec 26, Part 4 §2): the cache's library rows follow the stored selection
 * through here, the stamped chosen rows are branched (landed or set back), the holds of the pins on air are kept, and
 * a cache write that failed after a committed save leaves [cacheBehind] set for the next operation.
 *
 * Part of [LibrarySelection], which builds it: it takes no lock of its own and runs only inside the selection's lock
 * (`locked {}` or the resume), after the save it follows.
 */
internal class SelectionCache(
    private val library: LibraryRepository,
    private val repairer: TodayRepairer,
    private val stayCurrent: StayCurrentRequests,
    private val clock: Clock,
    private val report: (SelectionEvent) -> Unit,
) {
    // Set until a resume or a catch-up has put the cache in line, and again when a cache write failed after its save
    // committed: the next operation reconciles the cache first. It starts set, so an instance that never resumed (one
    // built after the start's resume failed) catches up on its first operation.
    @Volatile var cacheBehind = true
        private set

    // The cache was left behind (a cache write after a committed save failed, or the resume failed).
    fun markBehind() {
        cacheBehind = true
    }

    // The cache is in line with the stored selection (or there is nothing of this account's to put in line).
    fun markInLine() {
        cacheBehind = false
    }

    // The two branches of a stamped chosen row: a proven one lands, an unproven one is set back, and stay current is
    // asked to read it again. True when either changed the cache.
    suspend fun branches(
        account: LibraryAccount,
        ids: Set<ItemId>,
        rows: List<StoredLibrary>,
    ): Boolean {
        var changed = false
        for (row in rows) {
            val stamp = row.addStamp
            if (row.id !in ids || stamp == null || row.heldUntilMs != null) continue
            changed = branch(account, row.id, stamp, proven = row.walkedStamp == stamp) || changed
        }
        return changed
    }

    // One stamped chosen row: landed when proven, else set back. A set-back that loses its compare-and-set to the add
    // walk's proof (the add pass does not hold this lock) reads the row again and lands it when it is proven now.
    private suspend fun branch(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
        proven: Boolean,
    ): Boolean =
        when {
            proven -> {
                library.landAdd(account, id, stamp)
            }

            library.revertUnproven(account, id, stamp) -> {
                report(SelectionEvent.UnprovenReverted)
                stayCurrent.request()
                true
            }

            else -> {
                val now = library.storedLibraries(account).firstOrNull { it.id == id }
                val provenNow = now?.addStamp == stamp && now.walkedStamp == stamp && now.heldUntilMs == null
                provenNow && library.landAdd(account, id, stamp)
            }
        }

    // The cache write of a selection: the chosen and the adding libraries kept, every add with its stamp, and the
    // holds of the pins on air. The cache refuses a new stamp on a row with neither a hold nor a stamp (one set back
    // to be read again, or one whose untick never reached the cache), so such a row is held first, in a write of its
    // own, and then stamped: no add is ever dropped or left unstamped for that (D1). [pins] false is the expiry of
    // holds (D12): no new hold is asked for, and a held row keeps the one it has until that ends.
    suspend fun applyToCache(
        account: LibraryAccount,
        saved: StoredSelection,
        rows: List<StoredLibrary>,
        pins: Boolean = true,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val keep = saved.chosen + saved.adding.keys
        // A hold matters only for a library that leaves: with none leaving, the repairer's turn is not taken.
        val holds = if (pins && rows.any { it.id !in keep }) holdsFor(account, rows, now) else emptyMap()
        val byId = rows.associateBy { it.id }
        val refused = saved.adding.keys.filterTo(HashSet()) { byId[it]?.refusesAStamp() == true }
        if (refused.isNotEmpty()) {
            report(SelectionEvent.HeldBeforeStamp)
            // At least a millisecond ahead: a hold at or before now would delete the row instead (NIT, a zero pause).
            val until = now + maxOf(repairer.pauseHold.inWholeMilliseconds, 1L)
            val first = holds + refused.associateWith { maxOf(holds[it] ?: until, until) }
            library.applySelection(account, keep - refused, saved.adding - refused, first, now)
        }
        library.applySelection(account, keep, saved.adding, holds, now)
    }

    // The cache's rule (LibraryDao.applySelection): a new stamp only for no row, or a row with a hold or a stamp.
    private fun StoredLibrary.refusesAStamp(): Boolean = heldUntilMs == null && addStamp == null

    // The holds of the libraries pinned on air, each never below the hold its row already has.
    private suspend fun holdsFor(
        account: LibraryAccount,
        rows: List<StoredLibrary>,
        now: Long,
    ): Map<ItemId, Long> {
        val existing = rows.associate { it.id to it.heldUntilMs }
        return repairer.heldPins(account, now).mapValues { (id, until) -> maxOf(until, existing[id] ?: until) }
    }

    // A cache write after a committed save: a failure is reported, and the next operation catches up (D13).
    @Suppress("TooGenericExceptionCaught") // Whatever the cache throws, the save stands.
    suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markBehind()
            report(SelectionEvent.RoomBehind)
        }
    }
}
