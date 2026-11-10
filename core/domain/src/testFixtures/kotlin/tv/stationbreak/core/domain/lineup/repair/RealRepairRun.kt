// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.util.Collections
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The real selection-change repair (#684) over any [LibraryRepository], for the heap test on a device that cannot see
 * core:domain's internals: a lineup planned over the library (every starter the fixture can switch on), and the
 * repairer's own turn (`TodayRepairer.selectionChanged`, so `SelectionRepair.plan` with its lanes) for each untick. A
 * first untick repairs the days; a second then plans on repaired days with a removed library, so the plan walks the new,
 * the current and the removed selections.
 */
class RealRepairRun(
    library: LibraryRepository,
    private val account: LibraryAccount,
) {
    /** How many selections each `ruleSnapshots` walk read, in order. */
    val walkSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    private val counted =
        object : LibraryRepository by library {
            override suspend fun ruleSnapshots(
                account: LibraryAccount,
                selections: List<Set<ItemId>>,
                cutoff: Instant,
            ): List<RuleSnapshot> {
                walkSizes += selections.size
                return library.ruleSnapshots(account, selections, cutoff)
            }
        }
    private val service = LineupService(InMemoryLineupStore(), counted, Clock.System, { 0 }, {})
    private var chosen: Set<ItemId> = emptySet()
    private var applied = 0L
    private val source =
        BuiltInScheduleSource(service, counted) { SavedSelection(chosen, applied, chosen, 0L) }
    private val repairer = TodayRepairer(service, source, counted) { applied }
    private val atMs = Clock.System.now().toEpochMilliseconds()

    /** How many channels the prepared lineup has on, and how many it has. */
    var channelsOn = 0
        private set
    var channels = 0
        private set

    /** Plans the lineup over [libraries] and switches every starter on that can be. */
    suspend fun prepare(libraries: Set<ItemId>) {
        chosen = libraries
        service.refresh(account, libraries)
        val lineup = checkNotNull(service.loaded(account)).lineup
        for (channel in lineup.channels) {
            if (channel.state != ChannelState.On && channel.source is ChannelSource.Starter) {
                service.edit(account, LineupEdit.SwitchOn(channel.id, confirmedOptIn = true), libraries)
            }
        }
        val planned = checkNotNull(service.loaded(account)).lineup.channels
        channels = planned.size
        channelsOn = planned.count { it.state == ChannelState.On }
    }

    /** Unticks [removed] through the repairer's turn, as the selection's change does; true when it was saved. */
    suspend fun untick(removed: Set<ItemId>): Boolean {
        val after = chosen - removed
        repairer.warmDays(account, SavedSelection(chosen, applied, chosen, 0L), atMs)
        val outcome =
            repairer.selectionChanged(account, after, atMs, 0L) { seq ->
                chosen = after
                applied = seq
                true
            }
        return outcome is RepairOutcome.Saved
    }

    /** How many channel days have a selection repair recorded. */
    suspend fun repairs(): Int = checkNotNull(service.loaded(account)).repairs.selection.size
}
