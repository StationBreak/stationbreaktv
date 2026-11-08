// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.model.ItemId

// A setup's save, for the tests (spec 26: the store has no setup write of its own; the selection makes it): the next
// seq, which is also the reset seq. False with no record, an empty choice or a failed write.
internal suspend fun SelectionStore.storeChoice(ids: Set<ItemId>): Boolean {
    val seq = (loadSelectionSeq() ?: return false) + 1
    // A stored choice's listing count carries over; after a clear it cannot be read, and starts again.
    val listings = loadSelection()?.listings ?: 0L
    return saveSelection(StoredSelection(ids, seq = seq, listings = listings, resetSeq = seq), setup = true)
}
