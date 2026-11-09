// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.selection.SelectionAllocation.Allocated
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock

/**
 * The server's listing as the selection follows it (spec 26, Part 4 §2): the libraries the last counted listing gave,
 * which a change may add from, and the two-read rule over the stored selection (a miss noted, cleared or confirmed).
 *
 * Part of [LibrarySelection], which builds it: it takes no lock of its own and runs only inside the selection's lock
 * (`locked {}`), after the selection has checked the listing counts and the choice is the account's own.
 */
internal class SelectionFollower(
    private val config: SelectionConfig,
    private val clock: Clock,
    private val ownership: SelectionOwnership,
    private val allocation: SelectionAllocation,
    private val reconciler: SelectionReconciler,
) {
    // The libraries the last counted listing gave: a library may be added only if it was listed.
    @Volatile private var lastListed: Set<ItemId>? = null

    // A counted listing gave [ids].
    fun listed(ids: Set<ItemId>) {
        lastListed = ids
    }

    // No listing counts any more (a setup or a clear): the next change adds nothing until one does.
    fun forget() {
        lastListed = null
    }

    // Whether the last counted listing gave every one of [ids]; false when none has counted.
    fun lists(ids: Set<ItemId>): Boolean {
        val listed = lastListed
        return listed != null && listed.containsAll(ids)
    }

    // One counted listing of [ids] over [stored], [account]'s own choice (see [LibrarySelection.followServer]).
    suspend fun follow(
        account: LibraryAccount,
        stored: StoredSelection,
        ids: Set<ItemId>,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val listings = stored.listings + 1
        // A miss from the future (the clock went back) is counted again from now.
        val missing = stored.missing.filterValues { it.sinceMs <= now }.toMutableMap()
        for (id in stored.chosen + stored.adding.keys) {
            if (id in ids) missing.remove(id) else missing.putIfAbsent(id, Missing(now, listings))
        }
        val after = config.missingConfirmAfter.inWholeMilliseconds
        val confirmed = missing.filterValues { listings > it.sincePass && now - it.sinceMs >= after }.keys
        val left = stored.chosen - confirmed
        val gone = left.isEmpty() || (stored.gone && stored.chosen.none { it in ids })
        val chosen = left.ifEmpty { stored.chosen }
        val adding = stored.adding - confirmed
        val counted = stored.copy(missing = missing, listings = listings)
        if (chosen == stored.chosen && adding == stored.adding && gone == stored.gone) {
            // Bookkeeping only: the same seq, as nothing that plays changes.
            if (allocation.write(counted, account)) ownership.publish(counted)
            return
        }
        val next = { seq: Long ->
            counted.copy(chosen = chosen, seq = seq, adding = adding, gone = gone).withMissingKept()
        }
        val outcome = allocation.allocate(account, stored, chosen.takeIf { it != stored.chosen }, next)
        if (outcome is Allocated.Saved) reconciler.afterSave(account, stored, next(outcome.seq))
    }
}

// A miss is kept only for a library still chosen or being added.
internal fun StoredSelection.withMissingKept(): StoredSelection {
    val kept = missing.filterKeys { it in chosen || it in adding }
    return copy(missing = kept)
}
