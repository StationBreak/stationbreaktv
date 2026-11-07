// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId

/**
 * The TV's saved library selection and the seq saved with it, in one write (spec 26): [seq] is the selection change
 * whose repair shows ([BuiltInScheduleSource]). [seq] is always the seq actually saved, never a stand-in: 0 only for
 * a selection saved with no seq (before Part 4 stores one). There is no default, so every provider says which.
 */
data class SavedSelection(
    val libraries: Set<ItemId>,
    val seq: Long,
)
