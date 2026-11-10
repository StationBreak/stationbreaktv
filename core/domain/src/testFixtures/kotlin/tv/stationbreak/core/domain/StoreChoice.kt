// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId

/**
 * Stores [ids] as a setup would, for a test that needs a choice in place (spec 26): the next seq, which is also the
 * reset seq, with nothing being added, gone or missed. No repair record is reset: that is `LibrarySelection.choose`'s.
 * False when there is no record, [ids] is empty, or the write fails.
 */
suspend fun SelectionStore.storeChoice(ids: Set<ItemId>): Boolean {
    val seq = (loadSelectionSeq() ?: return false) + 1
    // A stored choice's listing count carries over; after a clear it cannot be read, and starts again.
    val listings = loadSelection()?.listings ?: 0L
    return saveSelection(StoredSelection(ids, seq = seq, listings = listings, resetSeq = seq), setup = true)
}

/**
 * A stored selection for a test, through the store's own writes, as production makes one: a setup first (the store
 * takes only a setup's save while nothing is chosen), then [selection] over it. Its seq must be above 1, as a real one
 * after a setup is.
 */
suspend fun SelectionStore.seed(selection: StoredSelection): Boolean {
    val setup = minOf(selection.resetSeq, selection.seq - 1)
    if (setup < 1) return false
    val first = StoredSelection(selection.chosen, seq = setup, listings = selection.listings, resetSeq = setup)
    return saveSelection(first, setup = true) && saveSelection(selection)
}
