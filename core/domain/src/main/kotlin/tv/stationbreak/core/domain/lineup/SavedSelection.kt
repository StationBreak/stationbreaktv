// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.StoredSelection
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
 *
 * [resetSeq] is the seq the last setup saved (the settings key `selectionResetSeq`), 0 for none: a selection repair of
 * a lower seq was recorded for a selection before that setup and never shows (`TodayRepairs.applied`). No default.
 *
 * [owner] is the account whose choice this is, or null when it is not known (a selection an older build saved: it is
 * taken as the active account's). A reader that has an account checks it before using the libraries (#305): the stored
 * selection can still be the previous account's for a moment after a switch. Never printed.
 */
class SavedSelection(
    val libraries: Set<ItemId>,
    val seq: Long,
    playable: Set<ItemId>?,
    val resetSeq: Long,
    val owner: LibraryAccount? = null,
) {
    /**
     * The playable libraries as given, less any that is not in [libraries]: this is read on the tune path, so a
     * provider that gives more is corrected here, never thrown on.
     */
    val playable: Set<ItemId>? = playable?.let { if (libraries.containsAll(it)) it else it intersect libraries }

    override fun equals(other: Any?): Boolean =
        other is SavedSelection &&
            libraries == other.libraries &&
            seq == other.seq &&
            playable == other.playable &&
            resetSeq == other.resetSeq &&
            owner == other.owner

    override fun hashCode(): Int = Objects.hash(libraries, seq, playable, resetSeq, owner)

    /** Counts only: never a library id (a log line may print a selection). */
    override fun toString(): String {
        val read = "libraries=${libraries.size}, seq=$seq, playable=${playable?.size ?: "none"}"
        return "SavedSelection($read, resetSeq=$resetSeq)"
    }
}

/**
 * The schedule source's saved selection over [settings] (spec 26 Part 4, §3): one lock-free read of the stored
 * selection ([ServerSettings.loadSelection], served from memory, so the tune path never waits on a writer) gives the
 * chosen libraries, their seq and the reset seq. The playable libraries are the chosen ones, or null when the selection
 * is gone; the libraries being added are never in either. Null when nothing is chosen.
 */
fun savedSelectionOf(settings: ServerSettings): suspend () -> SavedSelection? {
    val read: suspend () -> SavedSelection? = { settings.loadSelection()?.toSaved() }
    return read
}

/** [this] as the saved selection the schedule source's provider gives for it. */
internal fun StoredSelection.toSaved(): SavedSelection =
    SavedSelection(chosen, seq = seq, playable = if (gone) null else chosen, resetSeq = resetSeq, owner = owner)
