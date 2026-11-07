// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BackgroundSaves
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What a call of [TodayRepairer] did (spec 26). Only [Saved] changed anything, and then the record holds the repair
 * (written now, or already so). A caller retries [Stale] once, after a fresh read: the lineup or its selection repairs
 * moved on while the repair ran.
 */
sealed interface RepairOutcome<out T> {
    /** The record holds the repair; [result] is what it found (the pins, or how many blocks were fixed). */
    data class Saved<out T>(
        val result: T,
    ) : RepairOutcome<T>

    /** The lineup or its selection repairs moved on while the repair ran: nothing was written. */
    data object Stale : RepairOutcome<Nothing>

    /** The record could not be saved (it cannot be made to fit the file, or the save failed): nothing changed. */
    data object Refused : RepairOutcome<Nothing>

    /** There is no lineup this TV may write: nothing changed. */
    data object NoLineup : RepairOutcome<Nothing>

    /** The selection could not be saved: its repair was taken back out, and the record is as it was. */
    data object Unsaved : RepairOutcome<Nothing>
}

/**
 * A selection change as recorded: its [seq], which the caller saves with the new selection (in one write) for the
 * repair to show, and the [pins] whose programme is of a library the change removes.
 */
data class SelectionRepaired(
    val seq: Long,
    val pins: List<Pin>,
)

/**
 * The repair engine (spec 26): it writes the per-TV repair record (`LineupService.recordRepairs`) that the day build
 * follows. The tune path never runs it and reads only the record it writes. Use one instance per process: its calls
 * take turns (one `Mutex` around them all), and their work runs on [dispatcher] (the background-priority thread the
 * day saves use), never on the caller's.
 *
 * - [selectionChanged] pins every channel's programme on air and records the rest of its day laid out again for the
 *   new selection.
 * - [repairLost] fixes the blocks whose item has left the library and records the pieces.
 * - [dropSelection] takes a selection change's repairs back out (Part 4's start-up resume, for a change a crash left
 *   recorded but not saved).
 *
 * Each call plans from the record it read and writes only if the lineup's revision and the record's selection repairs
 * are still those ([RepairOutcome.Stale] otherwise). [selectionChanged] owns the whole of a change: record, save (the
 * caller's write of the selection and its seq), and on a failed save the undo, all in one turn, so a second change
 * never starts before the first is saved. Each write also prunes the repairs a saved later seq has superseded.
 */
class TodayRepairer internal constructor(
    private val service: LineupService,
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val successors: Successors,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(
        service: LineupService,
        source: BuiltInScheduleSource,
        library: LibraryRepository,
        successors: Successors = Successors.None,
    ) : this(service, source, library, successors, BackgroundSaves.dispatcher)

    // The service's own: one instance of the tunables for the record's saves and for the repair.
    internal val config: RepairConfig get() = service.repairConfig

    /** How long a library stays held after its last pinned programme ends ([Pin]), so a paused one can be resumed. */
    val pauseHold: Duration get() = config.pauseHold

    private val days = LineupDays(service.catalog)
    private val turns = Mutex()

    /**
     * Records a `ChannelRepair` for every switched-on channel's day on the air at [atMs], as the selection goes from
     * the saved one (the schedule source's [SavedSelection]) to [after], then calls [save] with the change's seq and
     * undoes the repair when it fails. [save] is the caller's one write of the new selection and that seq (Part 4); it
     * must not call this repairer (the turn is held). Until it returns true the change's repair is held back (the
     * schedule source shows the repair of the saved seq), and when it returns false or throws the record is put back as
     * it was ([RepairOutcome.Unsaved], or the exception after the undo). The seq is worked out here, in one place
     * (`TodayRepairs.nextSeq`): one above both the record's highest and the saved seq.
     *
     * Each day keeps what airs from the half hour at or before [atMs] to the end of the programme on air (the pin) and
     * lays out the rest of its day again: the day's pool as built today without the removed libraries' items (on a day
     * already repaired, the record's tail pool and what the channel admits of the current selection, less what has
     * aired), plus the added titles where the build would home them, under today's rules and holds. A day hidden at
     * the change has no pin: its rest is laid out from the half hour, or the whole day stays hidden with the new
     * selection's reason. It runs while the removed libraries' rows still exist. The result of [RepairOutcome.Saved]
     * is the seq and the pins whose programme is of a library the change removes.
     */
    suspend fun selectionChanged(
        account: LibraryAccount,
        after: Set<ItemId>,
        atMs: Long,
        save: suspend (seq: Long) -> Boolean,
    ): RepairOutcome<SelectionRepaired> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val (current, today) = source.chosenDays(account, Instant.fromEpochMilliseconds(atMs))
                val seq = held.repairs.nextSeq(current.seq)
                val change = SelectionChange(current.libraries, after, seq, atMs)
                val shared: SharedRead = { libraries, cuts -> source.sharedSnapshotsOf(account, libraries, cuts) }
                val planned = SelectionRepair(days, library, account, held, change, shared).plan(today)
                val repaired = SelectionRepaired(seq, planned.pins)
                val recorded = record(account, held, repaired) { planned.into(it, current.seq) }
                if (recorded !is RepairOutcome.Saved) return@withLock recorded
                val written = service.loaded(account)?.repairs
                var saved = false
                try {
                    saved = save(seq)
                } finally {
                    if (!saved) withContext(NonCancellable) { undo(account, held.repairs, written, seq) }
                }
                if (saved) recorded else RepairOutcome.Unsaved
            }
        }

    // Puts the record back as it was before the change of [seq] ([before]) when it is still what the change wrote
    // ([written]); otherwise (a roll or a trim came between) takes that change's repairs out of what is there now.
    private suspend fun undo(
        account: LibraryAccount,
        before: TodayRepairs,
        written: TodayRepairs?,
        seq: Long,
    ) {
        service.recordRepairsOutcome(account) { now ->
            if (now.repairs == written) before else now.repairs.withoutSelection(seq)
        }
    }

    /**
     * Fixes every gone block from the one on air at [atMs] to each channel day's end, over the days a tune sees (the
     * days of the source's chosen libraries), and records the pieces with where the check ran (`LostCheck`: the
     * library's content generation, and the end of the earliest of those days). Does nothing (`Saved(0)`) when the
     * record's check already covers that generation at [atMs], when no day is on the air, or while the record holds a
     * selection repair above the saved seq (a change not saved yet: the days it would show are not those checked).
     * [playable] are the chosen libraries a match or a fill may come from now (those whose rows are fetched and
     * current; never more than the chosen set); null when the selection is gone, and then nothing is taken from the
     * libraries. The result of [RepairOutcome.Saved] is how many blocks were fixed.
     */
    suspend fun repairLost(
        account: LibraryAccount,
        playable: Set<ItemId>?,
        atMs: Long,
    ): RepairOutcome<Int> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val applied = source.savedSelection()?.seq ?: 0L
                if (held.repairs.selection.any { it.seq > applied }) return@withLock RepairOutcome.Saved(0)
                val generation = library.contentGeneration(account)
                if (held.repairs.lostCheck?.covers(generation, atMs) == true) return@withLock RepairOutcome.Saved(0)
                val today = source.currentDays(account, Instant.fromEpochMilliseconds(atMs))
                if (today.isEmpty()) return@withLock RepairOutcome.Saved(0)
                val fixes = LostRepair(days, library, successors, account, held, playable, atMs).fixes(today)
                val check = LostCheck(generation, today.minOf { it.epochEndMs })
                record(account, held, fixes.size) { it.withFixes(fixes, check).pruned(applied) }
            }
        }

    /**
     * Takes the selection repairs of [seq] back out of the record (Part 4's start-up resume, for a change a crash left
     * recorded but not saved): each of those days shows again the repair it showed before, or its build when it had
     * none. The fixes stay. [seq] may be used again, as nothing of it is left to show. The result is whether any
     * repair had that seq.
     */
    suspend fun dropSelection(
        account: LibraryAccount,
        seq: Long,
    ): RepairOutcome<Boolean> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val had = held.repairs.selection.any { it.seq == seq }
                val applied = source.savedSelection()?.seq ?: 0L
                record(account, held, had) { it.withoutSelection(seq).pruned(applied) }
            }
        }

    // Writes what [change] makes of the record, if the lineup is still the one [held] was read from.
    private suspend fun <T> record(
        account: LibraryAccount,
        held: Revisioned,
        result: T,
        change: (TodayRepairs) -> TodayRepairs,
    ): RepairOutcome<T> {
        var stale = false
        val recorded =
            service.recordRepairsOutcome(account) { now ->
                if (now.isStill(held)) {
                    change(now.repairs)
                } else {
                    stale = true
                    now.repairs
                }
            }
        return when {
            recorded == LineupService.Recorded.NoLineup -> RepairOutcome.NoLineup
            recorded == LineupService.Recorded.Refused -> RepairOutcome.Refused
            stale -> RepairOutcome.Stale
            else -> RepairOutcome.Saved(result)
        }
    }
}

// Whether this is still the lineup [held] was read from: the same revision and the same selection repairs.
private fun Revisioned.isStill(held: Revisioned): Boolean {
    val sameRepairs = repairs.selection == held.repairs.selection
    return revision == held.revision && sameRepairs
}
