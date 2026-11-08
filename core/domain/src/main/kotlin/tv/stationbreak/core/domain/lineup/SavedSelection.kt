// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId

/**
 * The TV's saved library selection and the seq saved with it, in one write (spec 26): [seq] is the selection change
 * whose repair shows ([BuiltInScheduleSource]). [seq] is always the seq actually saved, never a stand-in: 0 only for
 * a selection saved with no seq (before Part 4 stores one). There is no default, so every provider says which.
 *
 * [playable] are the chosen libraries a repair of lost blocks may match or fill from now (those whose rows are fetched
 * and current: never more than [libraries], never one being added), or null when the selection is gone (the repair
 * then fills from the recorded tail only). It travels in the same read as [libraries] and [seq], so the repairer reads
 * all three under its own lock (`TodayRepairer.repairLost`). Like [seq], it has no default.
 */
data class SavedSelection(
    val libraries: Set<ItemId>,
    val seq: Long,
    val playable: Set<ItemId>?,
) {
    init {
        require(playable == null || libraries.containsAll(playable)) { "A playable library is always a chosen one" }
    }
}
