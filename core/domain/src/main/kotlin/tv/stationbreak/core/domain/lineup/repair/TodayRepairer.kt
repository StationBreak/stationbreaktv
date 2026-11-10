// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BackgroundSaves
import tv.stationbreak.core.domain.lineup.BuildProbes
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.RecordReset
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.model.ItemId
import kotlin.coroutines.CoroutineContext
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

    /**
     * The selection could not be saved, and its repair could not be taken back out either (the undo's own write
     * failed, or the saved seq could not be read): the record may still hold it, above the saved seq, so it never
     * shows. It is reported (`LineupEvent.RepairUndoFailed`), and the next lost-block check drops it.
     */
    data object UndoFailed : RepairOutcome<Nothing>

    /** The saved selection could not be read (the provider gave none): nothing was changed, dropped or undone. */
    data object SelectionUnknown : RepairOutcome<Nothing>

    /** The seq given to `dropSelection` is saved (at or below the saved seq): its change committed, so nothing went. */
    data object AlreadySaved : RepairOutcome<Nothing>
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
 * The re-entry marker (spec 26, Part 4 §2): set in the coroutine context while a [TodayRepairer] turn is held, so a
 * call of the repairer (or of `LibrarySelection`) from inside it, from a save callback say, fails at once with
 * [IllegalStateException] instead of waiting for ever on the turn it is in.
 */
internal object InTurn : CoroutineContext.Element, CoroutineContext.Key<InTurn> {
    override val key: CoroutineContext.Key<*> get() = this
}

/** Fails with [IllegalStateException] when the caller is inside a [TodayRepairer] turn ([InTurn]). */
internal suspend fun refuseInTurn() {
    check(currentCoroutineContext()[InTurn] == null) { "Entered from inside the repairer's own turn" }
}

/**
 * The repair engine (spec 26): it writes the per-TV repair record (`LineupService.recordRepairs`) that the day build
 * follows. The tune path never runs it and reads only the record it writes. Use one instance per process: its calls
 * take turns (one `Mutex` around them all), and their work runs on [dispatcher] (the background-priority thread the
 * day saves use), never on the caller's, except a change's plan, which runs on [planning] (a small pool of its own,
 * at the configured priority).
 *
 * - [selectionChanged] pins every channel's programme on air and records the rest of its day laid out again for the
 *   new selection.
 * - [repairLost] fixes the blocks whose item has left the library and records the pieces.
 * - [dropSelection] takes a selection change's repairs back out (Part 4's start-up resume, for a change a crash left
 *   recorded but not saved).
 * - [saveWithoutRepair] saves a selection change that needs no repair under the next seq; [resetSelection] does a
 *   setup's save, with the record emptied first; [heldPins] gives the holds of the pins shown.
 *
 * A call made from inside a turn (a save callback calling back in) fails with [IllegalStateException] ([InTurn]).
 *
 * Each call plans from the record it read and writes only if the lineup's revision and the record's selection repairs
 * are still those ([RepairOutcome.Stale] otherwise). [selectionChanged] owns the whole of a change: record, save (the
 * caller's write of the selection and its seq), and on a save that did not commit the undo, all in one turn, so a
 * second change never starts before the first is saved. Each write also prunes the repairs a saved later seq has
 * superseded.
 */
@Suppress("TooManyFunctions", "LongParameterList") // A function per call; the constructor takes two dispatchers.
class TodayRepairer internal constructor(
    private val service: LineupService,
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val successors: Successors,
    private val storedSeq: suspend () -> Long?,
    private val dispatcher: CoroutineDispatcher,
    private val planning: CoroutineDispatcher,
) {
    /**
     * [storedSeq] is `ServerSettings.loadSelectionSeq`: the stored seq, which outlives the selection (a sign-out keeps
     * it), for [resetSelection]. Null when it cannot be read.
     */
    constructor(
        service: LineupService,
        source: BuiltInScheduleSource,
        library: LibraryRepository,
        successors: Successors = Successors.None,
        storedSeq: suspend () -> Long?,
    ) : this(
        service,
        source,
        library,
        successors,
        storedSeq,
        BackgroundSaves.dispatcher,
        // Made once per repairer: a pool of its own, at the configured priority, not the day-saves thread.
        planningDispatcher(service.repairConfig),
    )

    // The service's own: one instance of the tunables for the record's saves and for the repair.
    internal val config: RepairConfig get() = service.repairConfig

    /** How long a library stays held after its last pinned programme ends ([Pin]), so a paused one can be resumed. */
    val pauseHold: Duration get() = config.pauseHold

    private val days = LineupDays(service.catalog)
    private val turns = Mutex()

    // One turn: refused from inside a turn, then on [dispatcher], under [turns], with the marker set.
    private suspend fun <T> turn(block: suspend () -> T): T {
        refuseInTurn()
        return withContext(dispatcher) { turns.withLock { withContext(InTurn) { block() } } }
    }

    /**
     * Records a `ChannelRepair` for every switched-on channel's day on the air at [atMs], as the selection goes from
     * the saved one (the schedule source's [SavedSelection]) to [after], then calls [save] with the change's seq and
     * undoes the repair when the save did not commit. [save] is the caller's one write of the new selection and that
     * seq (Part 4); a call of this repairer from it fails ([InTurn]). Until the seq is saved the change's repair is
     * held back (the schedule source shows the repair of the saved seq, with its fixes).
     *
     * When [save] returns false or throws (a cancellation included), the saved seq is read again: a save whose write
     * committed (the saved seq is at least the change's) is never undone, and the result is [RepairOutcome.Saved]
     * when it returned false; else the record is put back as it was ([RepairOutcome.Unsaved]). It is
     * [RepairOutcome.UndoFailed], with nothing undone, when the undo's write fails or the saved seq cannot be read
     * again (it throws, or gives null). A throwable from [save] reaches the caller after this, as it was thrown. With
     * no saved selection read at the start nothing is recorded ([RepairOutcome.SelectionUnknown]). The seq is worked
     * out here, in one place (`TodayRepairs.nextSeq`): one above both the record's highest and the saved seq, and at
     * least [atLeast] (Part 4 passes one above the highest add stamp in the library, so no seq equals a stamp left
     * there). It may throw [ArithmeticException] when that would pass `Long.MAX_VALUE`, which no real count of changes
     * reaches.
     *
     * Each day keeps what airs from the half hour at or before [atMs] to the end of the programme on air (the pin) and
     * lays out the rest of its day again: the day's pool as built today without the removed libraries' items (on a day
     * already repaired, the record's tail pool and what the channel admits of the current selection, less what has
     * aired), plus the added titles where the build would home them, under today's rules and holds. A day hidden at
     * the change has no pin: its rest is laid out from the half hour, or the whole day stays hidden with the new
     * selection's reason. It runs while the removed libraries' rows still exist. The result of [RepairOutcome.Saved]
     * is the seq and the pins whose programme is of a library the change removes. [entered] is called first in the
     * turn, once it is held (a change times its wait for the turn). [clock], when given, times the repair's steps up
     * to the save, with the process's CPU time and the collections over them ([RepairPhases]); it changes nothing.
     *
     * A caller that has the saved selection at hand calls [warmDays] with it first, so the turn finds today's days
     * built.
     */
    @Suppress("LongParameterList") // The change, its moment and floor, the save, and the turn's timing hooks.
    suspend fun selectionChanged(
        account: LibraryAccount,
        after: Set<ItemId>,
        atMs: Long,
        atLeast: Long,
        entered: () -> Unit = {},
        clock: RepairClock? = null,
        save: suspend (seq: Long) -> Boolean,
    ): RepairOutcome<SelectionRepaired> =
        turn {
            entered()
            // Everything in the turn but the save is the repair's: what no step times is its other time.
            clock.time(RepairPhase.Other) { changeInTurn(account, after, atMs, atLeast, clock, save) }
        }

    // [selectionChanged] in its turn.
    @Suppress("LongParameterList") // The change, its moment and floor, the clock and the save.
    private suspend fun changeInTurn(
        account: LibraryAccount,
        after: Set<ItemId>,
        atMs: Long,
        atLeast: Long,
        clock: RepairClock?,
        save: suspend (seq: Long) -> Boolean,
    ): RepairOutcome<SelectionRepaired> =
        run {
            val held = service.loaded(account) ?: return@run RepairOutcome.NoLineup
            // With no saved selection read, the seq shown is unknown: recording would drop what it cannot place.
            source.savedSelection() ?: return@run RepairOutcome.SelectionUnknown
            val recorded =
                probed(clock, source.probes) {
                    val (current, today) =
                        clock.time(RepairPhase.Days) {
                            source.chosenDays(account, Instant.fromEpochMilliseconds(atMs))
                        }
                    val seq = maxOf(held.repairs.nextSeq(current.seq), atLeast)
                    val change = SelectionChange(current.libraries, after, seq, atMs)
                    val shared: SharedRead = { libraries, cuts ->
                        source.sharedSnapshotsOf(account, libraries, cuts)
                    }
                    // Only the planning leaves the turn's thread: it is a pure function of what it reads, and runs at
                    // normal priority. The record, the save and the settle stay on [dispatcher], so their order holds.
                    val planned =
                        withContext(planning) {
                            SelectionRepair(days, library, account, held, change, shared, clock, source.probes)
                                .plan(today)
                        }
                    val repaired = SelectionRepaired(seq, planned.pins)
                    clock.time(RepairPhase.Write) {
                        record(account, held, repaired) { planned.into(it, current.seq, current.resetSeq) }
                    }
                }
            if (recorded !is RepairOutcome.Saved) return@run recorded
            val seq = recorded.result.seq
            val written = service.loaded(account)?.repairs
            var saved = false
            var settled = Settled.Undone
            try {
                // A trim while the save runs counts this seq as shown (`LineupService.saving`).
                service.saving(seq)
                saved = clock.time(RepairPhase.Save) { save(seq) }
            } finally {
                service.saving(null)
                if (!saved) settled = withContext(NonCancellable) { settle(account, held.repairs, written, seq) }
            }
            when {
                saved || settled == Settled.Committed -> recorded
                settled == Settled.UndoFailed -> RepairOutcome.UndoFailed
                else -> RepairOutcome.Unsaved
            }
        }

    /**
     * Builds today's days at [atMs] under [saved] (the saved selection, as the schedule source's provider gives it)
     * if they are not built yet, outside the repairer's turn and without taking it, so a [selectionChanged] that
     * follows finds them built: after an earlier selection save (an add's, say) they are not, and their build would
     * hold the turn. Only a warm-up: it changes nothing, reads no saved selection, and a build that fails is left to
     * the turn's own read, which meets it as it would have. With no libraries chosen there are no days to build.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the build throws: the turn's own read meets it again.
    suspend fun warmDays(
        account: LibraryAccount,
        saved: SavedSelection,
        atMs: Long,
    ) {
        refuseInTurn()
        try {
            // The source's own warm-up: it waits on the source's pool, not on the repairer's low-priority thread, where
            // the wait would queue behind the day saves first.
            source.warmDays(account, saved, Instant.fromEpochMilliseconds(atMs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The turn reads the days again.
        }
    }

    // How a change whose save did not return true ended.
    private enum class Settled { Committed, Undone, UndoFailed }

    // After a save of [seq] that returned false or threw: committed when the saved seq is at least [seq] (the write
    // went through, so nothing is undone); else the change is undone. Nothing here throws, so the save's own
    // throwable is the one the caller gets; a read or an undo that fails is reported.
    @Suppress("TooGenericExceptionCaught") // Whatever the read or the undo throws: the next check cleans up instead.
    private suspend fun settle(
        account: LibraryAccount,
        before: TodayRepairs,
        written: TodayRepairs?,
        seq: Long,
    ): Settled {
        val settled =
            try {
                // No read (null) is no answer: nothing is undone on it.
                val savedSeq = source.savedSelection()?.seq
                when {
                    savedSeq == null -> Settled.UndoFailed
                    savedSeq >= seq -> Settled.Committed
                    undo(account, before, written, seq) == LineupService.Recorded.Saved -> Settled.Undone
                    else -> Settled.UndoFailed
                }
            } catch (_: Exception) {
                Settled.UndoFailed
            }
        if (settled == Settled.UndoFailed) service.reportEvent(LineupEvent.RepairUndoFailed)
        return settled
    }

    // Puts the record back as it was before the change of [seq] ([before]) when it is still what the change wrote
    // ([written]); otherwise (a roll or a trim came between) takes that change's repairs and their fixes out of what
    // is there now.
    private suspend fun undo(
        account: LibraryAccount,
        before: TodayRepairs,
        written: TodayRepairs?,
        seq: Long,
    ): LineupService.Recorded =
        service.recordRepairsOutcome(account) { now ->
            if (now.repairs == written) before else now.repairs.withoutSelection(seq)
        }

    /**
     * Fixes every gone block from the one on air at [atMs] to each channel day's end, over the days a tune sees (the
     * days of the source's chosen libraries, each with the repair it shows), and records the pieces as fixes of that
     * repair (seq 0 on a day as built) with where the check ran (`LostCheck`: the library's content generation, and the
     * end of the earliest of those days). With no saved selection read (the provider gives null) it does nothing at
     * all ([RepairOutcome.SelectionUnknown]): no drop, no check. First, in the same turn, it drops every selection
     * repair above the saved seq with its fixes, and the lost-block check with them: no change is being saved outside a
     * turn, so those are left by a crash or by an undo that failed ([RepairOutcome.UndoFailed]); and so does every one
     * below the saved reset seq, which can never show again (its day shows its build, whose lost blocks this then
     * fixes). Then it does nothing
     * more (`Saved(0)`) when the record's check already covers that generation at [atMs], or when no day is on the air.
     *
     * The libraries a match or a fill may come from are the saved selection's [SavedSelection.playable], read in this
     * turn from the same provider read as the saved seq, so a selection that changes while the call waits for its turn
     * is the one it uses. Null there means the selection is gone, and then nothing is taken from the libraries (the
     * recorded tail only); that is not the same as no saved selection read at all. The result of [RepairOutcome.Saved]
     * is how many blocks were fixed.
     */
    suspend fun repairLost(
        account: LibraryAccount,
        atMs: Long,
    ): RepairOutcome<Int> =
        turn {
            run {
                var held = service.loaded(account) ?: return@run RepairOutcome.NoLineup
                // No saved selection read: nothing is dropped and nothing checked (the days it shows are unknown).
                val saved = source.savedSelection() ?: return@run RepairOutcome.SelectionUnknown
                val applied = saved.seq
                if (held.repairs.selection.any { it.seq > applied || it.seq < saved.resetSeq }) {
                    // What shows stays as it is; the check is made again all the same, over the days as they show.
                    val dropped =
                        record(account, held, 0) { it.applied(applied, saved.resetSeq).copy(lostCheck = null) }
                    if (dropped !is RepairOutcome.Saved) return@run dropped
                    held = service.loaded(account) ?: return@run RepairOutcome.NoLineup
                }
                val generation = library.contentGeneration(account)
                if (held.repairs.lostCheck?.covers(generation, atMs) == true) return@run RepairOutcome.Saved(0)
                // The days of the same read: the provider is read once in this turn.
                val today = source.currentDays(account, saved, Instant.fromEpochMilliseconds(atMs))
                if (today.isEmpty()) return@run RepairOutcome.Saved(0)
                val fixes = LostRepair(days, library, successors, account, held, saved.playable, atMs).fixes(today)
                val check = LostCheck(generation, today.minOf { it.epochEndMs })
                record(account, held, fixes.size) { it.withFixes(fixes, check).pruned(applied, saved.resetSeq) }
            }
        }

    /**
     * Takes the selection repairs of [seq] back out of the record (Part 4's start-up resume, for a change a crash left
     * recorded but not saved): each of those days shows again the repair it showed before, with that repair's fixes,
     * or its build when it had none. The change's own fixes go with it; every other fix stays. [seq] may be used
     * again, as nothing of it is left to show. The result is whether any repair had that seq.
     *
     * Nothing goes when [seq] is at or below the saved seq ([RepairOutcome.AlreadySaved]: that change committed and
     * its repair is the one shown, or one a later saved seq superseded), nor when the saved selection cannot be read
     * ([RepairOutcome.SelectionUnknown]).
     */
    suspend fun dropSelection(
        account: LibraryAccount,
        seq: Long,
    ): RepairOutcome<Boolean> =
        turn {
            run {
                val held = service.loaded(account) ?: return@run RepairOutcome.NoLineup
                val saved = source.savedSelection() ?: return@run RepairOutcome.SelectionUnknown
                val applied = saved.seq
                if (seq <= applied) return@run RepairOutcome.AlreadySaved
                val had = held.repairs.selection.any { it.seq == seq }
                record(account, held, had) { it.withoutSelection(seq).pruned(applied, saved.resetSeq) }
            }
        }

    /**
     * Saves a selection change that needs no repair of its own (Part 4: a tick that only adds, or a drop of an add)
     * under the next seq: one above the record's highest and the saved seq, and at least [atLeast]; the result of
     * [RepairOutcome.Saved] is that seq. First, in the same turn, every selection repair above the saved seq is
     * dropped with its fixes (and the lost-block check with them): no change is being saved outside a turn, so those
     * were left by a crash or a failed undo, and under the new seq they would show. Then [save] is the caller's one
     * write of the selection and that seq; it must not call this repairer (the turn is held).
     *
     * [RepairOutcome.SelectionUnknown] with no saved selection read, [RepairOutcome.NoLineup] with no lineup; a drop
     * that cannot be written gives its outcome ([RepairOutcome.Stale], [RepairOutcome.Refused] or
     * [RepairOutcome.NoLineup]) and nothing is saved. When [save] returns false the saved seq is read again: it is
     * [RepairOutcome.Saved] when that reached the seq (the write went through), else [RepairOutcome.Unsaved]. A
     * throwable from [save] reaches the caller as it was thrown. [entered] is called first in the turn, once it is held
     * (a change times its wait for the turn).
     */
    suspend fun saveWithoutRepair(
        account: LibraryAccount,
        atLeast: Long,
        entered: () -> Unit = {},
        save: suspend (seq: Long) -> Boolean,
    ): RepairOutcome<Long> =
        turn {
            entered()
            run {
                val saved = source.savedSelection() ?: return@run RepairOutcome.SelectionUnknown
                val held = service.loaded(account) ?: return@run RepairOutcome.NoLineup
                val seq = maxOf(held.repairs.nextSeq(saved.seq), atLeast)
                if (held.repairs.selection.any { it.seq > saved.seq }) {
                    val dropped =
                        record(account, held, seq) { it.applied(saved.seq, saved.resetSeq).copy(lostCheck = null) }
                    if (dropped !is RepairOutcome.Saved) return@run dropped
                }
                savedAt(seq, save) { source.savedSelection()?.seq }
            }
        }

    /**
     * A setup's save (Part 4 §2): empties the account's repair record through `LineupService.resetRecord`, which reads
     * the lineup file itself, then calls [save] with the seq `max(stored + 1, floor + 1, atLeast)`: one above the
     * stored seq ([storedSeq], which a sign-out keeps) and the latest seq the old record held (on disk or in memory),
     * and at least [atLeast]. The result of [RepairOutcome.Saved] is that seq, which the caller saves as the reset seq
     * too. A record of another account, or one that cannot be read, is set aside; a newer app's is left as it is (its
     * seqs are hidden by the reset seq).
     *
     * The emptied record keeps no `lastSeq`. So when the save then fails, the next setup may take a seq at or below
     * the old record's latest seq again. That is harmless: nothing holds those seqs any more (the record was emptied
     * before the save), so no repair of them can show.
     *
     * [RepairOutcome.SelectionUnknown], with nothing done, when the stored seq cannot be read;
     * [RepairOutcome.Refused], with nothing saved, when the file cannot be read, set aside or written. A false or a
     * throw from [save] is handled as in [saveWithoutRepair], with the stored seq read again.
     */
    suspend fun resetSelection(
        account: LibraryAccount,
        atLeast: Long,
        save: suspend (seq: Long) -> Boolean,
    ): RepairOutcome<Long> =
        turn {
            run {
                val stored = storedSeq() ?: return@run RepairOutcome.SelectionUnknown
                val floor =
                    when (val reset = service.resetRecord(account)) {
                        is RecordReset.Floor -> reset.seq
                        RecordReset.Failed -> return@run RepairOutcome.Refused
                    }
                val seq = maxOf(Math.addExact(stored, 1L), Math.addExact(floor, 1L), atLeast)
                savedAt(seq, save) { storedSeq() }
            }
        }

    /**
     * The holds of the libraries pinned on air (Part 4: the `holds` of a selection write), by library: the latest of
     * its shown repairs' pin ends plus [pauseHold], only those still ahead of [atMs]. Shown is at the saved
     * selection's seq and reset seq; with no saved selection read, every repair's pin counts (a longer hold is
     * harmless, a missing one drops a paused programme's rows). Empty with no lineup.
     */
    suspend fun heldPins(
        account: LibraryAccount,
        atMs: Long,
    ): Map<ItemId, Long> =
        turn {
            val repairs = service.loaded(account)?.repairs ?: TodayRepairs.NONE
            val saved = source.savedSelection()
            val shown = if (saved == null) repairs else repairs.applied(saved.seq, saved.resetSeq)
            val hold = pauseHold.inWholeMilliseconds
            val held = HashMap<ItemId, Long>()
            for (repair in shown.selection) {
                val library = repair.pinLibrary ?: continue
                val until = repair.pinEndMs + hold
                if (until > atMs) held[library] = maxOf(held[library] ?: until, until)
            }
            held
        }

    // [save] of [seq]: true, or false with the seq read again at or above it, is saved.
    private suspend fun savedAt(
        seq: Long,
        save: suspend (seq: Long) -> Boolean,
        savedSeq: suspend () -> Long?,
    ): RepairOutcome<Long> {
        if (save(seq)) return RepairOutcome.Saved(seq)
        val now = savedSeq()
        return if (now != null && now >= seq) RepairOutcome.Saved(seq) else RepairOutcome.Unsaved
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

// [block], with the process's CPU time and collections over it (read from [probes]) added to [clock] when there is
// one. The process's, not the thread's: the plan runs on another thread than the turn's.
private inline fun <T> probed(
    clock: RepairClock?,
    probes: BuildProbes,
    block: () -> T,
): T {
    if (clock == null) return block()
    val cpu = probes.processCpuMs()
    val count = probes.gcCount()
    val blocked = probes.gcBlockMs()
    try {
        return block()
    } finally {
        clock.addProbes(
            cpu = delta(cpu, probes.processCpuMs()),
            count = delta(count, probes.gcCount()),
            blocked = delta(blocked, probes.gcBlockMs()),
        )
    }
}

// A reading's change, or -1 when either end is unknown.
private fun delta(
    before: Long,
    after: Long,
): Long = if (before < 0 || after < before) -1 else after - before

// Whether this is still the lineup [held] was read from: the same revision and the same selection repairs.
private fun Revisioned.isStill(held: Revisioned): Boolean {
    val sameRepairs = repairs.selection == held.repairs.selection
    return revision == held.revision && sameRepairs
}
