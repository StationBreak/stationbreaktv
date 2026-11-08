// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId
import java.util.Objects

/**
 * The TV's saved library selection and the seq saved with it, in one write (spec 26): [seq] is the selection change
 * whose repair shows ([BuiltInScheduleSource]). [seq] is always the seq actually saved, never a stand-in: 0 only for
 * a selection saved with no seq (before Part 4 stores one). There is no default, so every provider says which.
 *
 * [playable] are the chosen libraries a repair of lost blocks may match or fill from now (those whose rows are fetched
 * and current: never more than [libraries], never one being added), or null when the selection is gone (the repair
 * then fills from the recorded tail only). It travels in the same read as [libraries] and [seq], so the repairer reads
 * all three under its own lock (`TodayRepairer.repairLost`). Like [seq], it has no default. A playable library
 * outside [libraries] is dropped (the playable set is always within the chosen one).
 */
class SavedSelection(
    val libraries: Set<ItemId>,
    val seq: Long,
    playable: Set<ItemId>?,
) {
    /**
     * The playable libraries as given, less any that is not in [libraries]: this is read on the tune path, so a
     * provider that gives more is corrected here, never thrown on.
     */
    val playable: Set<ItemId>? = playable?.let { if (libraries.containsAll(it)) it else it intersect libraries }

    override fun equals(other: Any?): Boolean =
        other is SavedSelection && libraries == other.libraries && seq == other.seq && playable == other.playable

    override fun hashCode(): Int = Objects.hash(libraries, seq, playable)

    override fun toString(): String = "SavedSelection(libraries=$libraries, seq=$seq, playable=$playable)"
}
