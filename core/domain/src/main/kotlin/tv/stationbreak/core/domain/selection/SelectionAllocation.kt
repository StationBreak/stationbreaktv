// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.RecordReset
import tv.stationbreak.core.domain.lineup.repair.RepairOutcome
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock

/**
 * The seq of the library selection and its writes to the settings (spec 26, Part 4 §2): every seq is allocated here,
 * through the repairer (a change, a save with no repair, the setup's reset) or as a plain save when no lineup exists,
 * floored one above the highest add stamp the cache holds; and every write of the selection goes through [write].
 *
 * Part of [LibrarySelection], which builds it: it takes no lock of its own and runs only inside the selection's lock
 * (`locked {}` or the resume), so the floor it reads and the seq it saves are the lock's.
 */
internal class SelectionAllocation(
    private val store: SelectionStore,
    private val library: LibraryRepository,
    private val repairer: TodayRepairer,
    private val service: LineupService,
    private val clock: Clock,
) {
    // How a seq-moving save went.
    sealed interface Allocated {
        data class Saved(
            val seq: Long,
        ) : Allocated

        data object Stale : Allocated

        // [terminal]: trying again in this process would not change it (the saved selection could not be read, the
        // seq was already saved). A refused record may pass (a save that failed), so it is not terminal.
        data class Failed(
            val terminal: Boolean,
        ) : Allocated
    }

    // A save that moves the seq, through the repairer: with today's repair for the change to [after] when given, else
    // with none. Before any lineup exists there is nothing to repair, and it is a plain save. A change's [steps] time
    // the wait for the repairer's turn and each save.
    suspend fun allocate(
        account: LibraryAccount,
        stored: StoredSelection,
        after: Set<ItemId>?,
        next: (Long) -> StoredSelection,
        steps: ChangeSteps? = null,
    ): Allocated {
        val atLeast = floorOf(library.storedLibraries(account))
        val save: suspend (Long) -> Boolean = { seq -> timed(steps) { write(next(seq), account) } }
        val held = { steps?.turnHeld() ?: Unit }
        val allocated =
            if (after != null) {
                val atMs = nowMs()
                // Today's days are built before the turn is asked for, from the saved selection the repair reads, so
                // the turn never builds them.
                val warm: suspend () -> Unit = { repairer.warmDays(account, atMs) }
                if (steps == null) warm() else steps.warming(warm)
                allocated(repairer.selectionChanged(account, after, atMs, atLeast, held, steps?.repair, save)) {
                    it.seq
                }
            } else {
                allocated(repairer.saveWithoutRepair(account, atLeast, held, save)) { it }
            }
        // Null: no lineup, so nothing to repair.
        return allocated ?: plain(account, stored, atLeast, next, steps)
    }

    // The setup's save: the record is emptied first; the save is the setup's, with nothing being added, gone or
    // missed. True when it saved.
    suspend fun reset(
        account: LibraryAccount,
        ids: Set<ItemId>,
    ): Boolean {
        val atLeast = floorOf(library.storedLibraries(account))
        val outcome =
            repairer.resetSelection(account, atLeast) { seq ->
                write(StoredSelection(ids, seq, resetSeq = seq), account, setup = true)
            }
        return outcome is RepairOutcome.Saved
    }

    // The resume's raise of [stored] above the highest stamp in the cache: null when its seq is above them all
    // already (nothing to raise), else true when it saved.
    suspend fun raise(
        account: LibraryAccount,
        stored: StoredSelection,
    ): Boolean? {
        val top = maxStamp(library.storedLibraries(account))
        if (top < stored.seq) return null
        return when (val floor = service.floorRecord(account)) {
            RecordReset.Failed -> {
                false
            }

            is RecordReset.Floor -> {
                if (floor.record) {
                    // Through the repairer, and only over a lineup it may write.
                    service.loaded(account)?.writable == true &&
                        repairer.saveWithoutRepair(account, maxOf(top, floor.seq) + 1) { seq ->
                            write(stored.copy(seq = seq), account)
                        } is RepairOutcome.Saved
                } else {
                    // No record this build could show: a plain save, which over a newer app's file also hides
                    // that file's repairs from any build that reads the reset seq (D17). There it skips a seq,
                    // so a newer build's hidden repair at the next one stays below the reset seq.
                    val seq = maxOf(stored.seq, top) + 1 + if (floor.newer) 1 else 0
                    val raisedTo = stored.copy(seq = seq, resetSeq = if (floor.newer) seq else stored.resetSeq)
                    commit(seq, raisedTo, account)
                }
            }
        }
    }

    // A repairer outcome as an allocation, with [seqOf] its saved seq; null for no lineup.
    private inline fun <T> allocated(
        outcome: RepairOutcome<T>,
        seqOf: (T) -> Long,
    ): Allocated? =
        when (outcome) {
            is RepairOutcome.Saved -> Allocated.Saved(seqOf(outcome.result))

            RepairOutcome.Stale -> Allocated.Stale

            RepairOutcome.NoLineup -> null

            // A refused record is a save that failed as well as one that does not fit: LineupService does not tell the
            // two apart, so it may pass, and the add pass's cap bounds what trying again costs.
            RepairOutcome.Refused,
            RepairOutcome.Unsaved,
            RepairOutcome.UndoFailed,
            -> Allocated.Failed(terminal = false)

            RepairOutcome.SelectionUnknown,
            RepairOutcome.AlreadySaved,
            -> Allocated.Failed(terminal = true)
        }

    // A save with no lineup to repair: only when the disk holds no record this build could show (none yet, one set
    // aside, or a newer app's, whose repairs the reset seq then hides). One above the stored seq and [atLeast].
    private suspend fun plain(
        account: LibraryAccount,
        stored: StoredSelection,
        atLeast: Long,
        next: (Long) -> StoredSelection,
        steps: ChangeSteps?,
    ): Allocated {
        // The record could not be read, or exists and is not loaded as writable yet: both may pass.
        val floor = service.floorRecord(account) as? RecordReset.Floor
        if (floor == null || floor.record) return Allocated.Failed(terminal = false)
        // Over a newer app's file, one seq is skipped, as in the resume's raise (D17).
        val seq = maxOf(stored.seq + 1, atLeast, floor.seq + 1) + if (floor.newer) 1 else 0
        val selection = next(seq).let { if (floor.newer) it.copy(resetSeq = seq) else it }
        val committed = timed(steps) { commit(seq, selection, account) }
        return if (committed) Allocated.Saved(seq) else Allocated.Failed(terminal = false)
    }

    // A save, timed when a change's [steps] are given.
    private suspend fun timed(
        steps: ChangeSteps?,
        save: suspend () -> Boolean,
    ): Boolean = steps?.saving(save) ?: save()

    // One above the highest add stamp in the cache: no seq may equal a stamp left there.
    private fun floorOf(rows: List<StoredLibrary>): Long = maxStamp(rows) + 1

    private fun maxStamp(rows: List<StoredLibrary>): Long = rows.maxOfOrNull { it.addStamp ?: 0L } ?: 0L

    // One write of the selection, as [owner]'s (every save says whose it is; [setup] only from the setup). The
    // settings never throw by contract; a write that did is read back by the caller.
    @Suppress("TooGenericExceptionCaught") // Whatever a write throws, it is not known to be stored.
    suspend fun write(
        selection: StoredSelection,
        owner: LibraryAccount,
        setup: Boolean = false,
    ): Boolean =
        try {
            store.saveSelection(selection.copy(owner = owner), setup)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    // A direct save of [seq]: committed when it returned true, or when the stored seq reads as [seq] after it.
    private suspend fun commit(
        seq: Long,
        selection: StoredSelection,
        owner: LibraryAccount,
    ): Boolean = write(selection, owner) || store.loadSelection()?.seq == seq

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()
}
