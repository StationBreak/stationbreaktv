// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.Revisioned
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * The repair engine (spec 26): it writes the per-TV repair record (`LineupService.recordRepairs`) that the day build
 * follows. Its calls are for background callers, off the main thread; the tune path never runs them and reads only
 * the record they write.
 *
 * - [selectionChanged] pins every channel's programme on air and records the rest of its day laid out again for the
 *   new selection.
 * - [repairLost] fixes the blocks whose item has left the library and records the pieces.
 *
 * [config] holds the repair's tunables (the pause hold a caller adds to a [Pin]'s end).
 *
 * Callers must serialise [selectionChanged] and [repairLost] (one `Mutex`, Part 3): each plans from the record it
 * read and writes only if the lineup's revision and the record's selection repairs are still those, so two at once
 * would make one of them write nothing. A selection change saves the new selection only after [selectionChanged]
 * returned non-null.
 */
class TodayRepairer internal constructor(
    private val service: LineupService,
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val successors: Successors,
    internal val config: RepairConfig,
) {
    constructor(
        service: LineupService,
        source: BuiltInScheduleSource,
        library: LibraryRepository,
        successors: Successors = Successors.None,
    ) : this(service, source, library, successors, RepairConfig())

    private val days = LineupDays(service.catalog)

    /**
     * Records a `ChannelRepair` for every channel day on the air at [atMs], as the selection goes from [before] to
     * [after] with the change's [seq] (stored, never worked out here). Each keeps what airs from the half hour at or
     * before [atMs] to the end of the programme on air (the pin) and lays out the rest of its day again: the day's
     * pool as built today without the removed libraries' items (on a day already repaired, the record's tail not yet
     * aired), plus the added titles where the build would home them, under today's rules and holds. It runs while the
     * removed libraries' rows still exist: the caller saves the selection after. Returns the pins whose programme is of
     * a library the change removes, or null when nothing was saved (no lineup, a lineup that moved on meanwhile, or a
     * record that cannot be saved): then nothing changed.
     */
    suspend fun selectionChanged(
        account: LibraryAccount,
        before: Set<ItemId>,
        after: Set<ItemId>,
        seq: Long,
        atMs: Long,
    ): List<Pin>? {
        val held = service.loaded(account) ?: return null
        val today = source.channelDays(account, before, Instant.fromEpochMilliseconds(atMs))
        val change = SelectionChange(before, after, seq, atMs)
        val planned = SelectionRepair(days, library, account, held, change).plan(today)
        var stale = false
        val saved =
            planned.repairs.isEmpty() ||
                service.recordRepairs(account) { now ->
                    if (now.isStill(held)) {
                        planned.into(now.repairs)
                    } else {
                        stale = true
                        now.repairs
                    }
                }
        return planned.pins.takeIf { saved && !stale }
    }

    /**
     * Fixes every gone block from the one on air at [atMs] to each channel day's end, over the days a tune sees (the
     * days of the source's chosen libraries), and records the pieces with the library's content generation; does
     * nothing when the record already holds that generation, or when no day is on the air. [playable] are the chosen
     * libraries a match or a fill may come from now (those whose rows are fetched and current; never more than the
     * chosen set); null when the selection is gone, and then nothing is taken from the libraries. When the lineup or
     * the record's selection repairs moved on while it ran, it writes nothing and leaves the generation as it was.
     * Returns how many blocks were fixed (0 when nothing was saved).
     */
    suspend fun repairLost(
        account: LibraryAccount,
        playable: Set<ItemId>?,
        atMs: Long,
    ): Int {
        val held = service.loaded(account)
        val generation = library.contentGeneration(account)
        val checked = held == null || held.repairs.lostCheckedGeneration == generation
        val today = if (checked) emptyList() else source.currentDays(account, Instant.fromEpochMilliseconds(atMs))
        if (held == null || today.isEmpty()) return 0
        val fixes = LostRepair(days, library, successors, account, held, playable, atMs).fixes(today)
        var stale = false
        val saved =
            service.recordRepairs(account) { now ->
                if (now.isStill(held)) {
                    now.repairs.withFixes(fixes, generation)
                } else {
                    stale = true
                    now.repairs
                }
            }
        return if (saved && !stale) fixes.size else 0
    }
}

// Whether this is still the lineup [held] was read from: the same revision and the same selection repairs.
private fun Revisioned.isStill(held: Revisioned): Boolean {
    val sameRepairs = repairs.selection == held.repairs.selection
    return revision == held.revision && sameRepairs
}
