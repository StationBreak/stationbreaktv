// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem

/** Writes [items] as a page of [library] with no checkpoint (a changed-since page): the cheapest way to edit rows. */
internal suspend fun RoomLibraryRepository.put(
    account: LibraryAccount,
    library: ItemId,
    vararg items: MediaItem,
): PageWrite = upsertItems(account, library, items.toList())

/**
 * Spec 09 Vimes H1, for every item of [account]: its versions have strictly increasing, positive-length intervals
 * `[seenFromMs, supersededAtMs)` that touch end to start, the first one starts at the item's `firstSeenMs`, and the
 * current row starts where the last one ended (or is the only one).
 */
internal suspend fun RoomLibraryRepository.assertIntervalsContiguous(account: LibraryAccount) {
    val versions = versions(account).groupBy { it.itemId }
    currentRows(account).forEach { current ->
        val own = versions[current.itemId].orEmpty().sortedBy { it.seenFromMs }
        if (own.isEmpty()) {
            assertEquals("${current.itemId}: no versions, so it starts when first seen", current.firstSeenMs, current.seenFromMs)
        } else {
            assertEquals("${current.itemId}: the first version starts at firstSeenMs", current.firstSeenMs, own.first().seenFromMs)
            own.forEach { assertTrue("${current.itemId}: an empty interval $it", it.seenFromMs < it.supersededAtMs) }
            own.zipWithNext { a, b -> assertEquals("${current.itemId}: a gap or an overlap", a.supersededAtMs, b.seenFromMs) }
            assertEquals("${current.itemId}: the current row starts at the last cut", own.last().supersededAtMs, current.seenFromMs)
        }
        assertTrue("${current.itemId}: firstSeenMs is not after seenFromMs", current.firstSeenMs <= current.seenFromMs)
    }
}
