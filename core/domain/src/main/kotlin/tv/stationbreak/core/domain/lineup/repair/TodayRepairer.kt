// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineDispatcher
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
 * - [dropSelection] takes a selection change's repairs back out, for a selection that could not be saved.
 *
 * Each call plans from the record it read and writes only if the lineup's revision and the record's selection repairs
 * are still those ([RepairOutcome.Stale] otherwise). A selection change saves the new selection, with its seq, only
 * after [selectionChanged] gave [RepairOutcome.Saved]: until then the schedule source holds the repair back.
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
     * the libraries the schedule source reads now to [after]. The change's seq is worked out here, in one place
     * (`TodayRepairs.nextSeq`): one above both the record's highest and the seq saved with the selection now (the
     * schedule source's `appliedSeq`), so it is above every seq a shown repair has used. Each keeps what airs from the
     * half hour at or before [atMs] to the end of the programme on air (the pin) and lays out the rest of its day
     * again: the day's pool as built today without the removed libraries' items (on a day already repaired, the
     * record's tail pool and what the channel admits of the current selection, less what has aired), plus the added
     * titles where the build would home them, under today's rules and holds. A day hidden at the change has no pin: its
     * rest is laid out from the half hour, or the whole day stays hidden with the new selection's reason. It runs while
     * the removed libraries' rows still exist: after a [RepairOutcome.Saved] the caller saves the selection with its
     * [SelectionRepaired.seq] (Part 4), and only then does the repair show.
     */
    suspend fun selectionChanged(
        account: LibraryAccount,
        after: Set<ItemId>,
        atMs: Long,
    ): RepairOutcome<SelectionRepaired> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val seq = held.repairs.nextSeq(source.appliedSeqNow())
                val (before, today) = source.chosenDays(account, Instant.fromEpochMilliseconds(atMs))
                val change = SelectionChange(before, after, seq, atMs)
                val planned = SelectionRepair(days, library, account, held, change).plan(today)
                record(account, held, SelectionRepaired(seq, planned.pins)) { planned.into(it) }
            }
        }

    /**
     * Fixes every gone block from the one on air at [atMs] to each channel day's end, over the days a tune sees (the
     * days of the source's chosen libraries), and records the pieces with where the check ran (`LostCheck`: the
     * library's content generation, and the end of the earliest of those days). Does nothing (`Saved(0)`) when the
     * record's check already covers that generation at [atMs], or when no day is on the air. [playable] are the chosen
     * libraries a match or a fill may come from now (those whose rows are fetched and current; never more than the
     * chosen set); null when the selection is gone, and then nothing is taken from the libraries. The result of
     * [RepairOutcome.Saved] is how many blocks were fixed.
     */
    suspend fun repairLost(
        account: LibraryAccount,
        playable: Set<ItemId>?,
        atMs: Long,
    ): RepairOutcome<Int> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val generation = library.contentGeneration(account)
                if (held.repairs.lostCheck?.covers(generation, atMs) == true) return@withLock RepairOutcome.Saved(0)
                val today = source.currentDays(account, Instant.fromEpochMilliseconds(atMs))
                if (today.isEmpty()) return@withLock RepairOutcome.Saved(0)
                val fixes = LostRepair(days, library, successors, account, held, playable, atMs).fixes(today)
                val check = LostCheck(generation, today.minOf { it.epochEndMs })
                record(account, held, fixes.size) { it.withFixes(fixes, check) }
            }
        }

    /**
     * Takes the selection repairs of [seq] back out of the record (a selection change whose save failed, Part 4): those
     * days are built again as the build makes them, from the selection that is saved. The fixes stay. [seq] may be
     * used again, as nothing of it is left to show. The result is whether any repair had that seq.
     */
    suspend fun dropSelection(
        account: LibraryAccount,
        seq: Long,
    ): RepairOutcome<Boolean> =
        withContext(dispatcher) {
            turns.withLock {
                val held = service.loaded(account) ?: return@withLock RepairOutcome.NoLineup
                val had = held.repairs.selection.any { it.seq == seq }
                record(account, held, had) { it.withoutSelection(seq) }
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
