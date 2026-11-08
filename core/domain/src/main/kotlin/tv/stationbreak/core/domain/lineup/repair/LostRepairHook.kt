// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.LineupEvent

/**
 * What runs after a sync, and once after the refresher's start check (spec 26, #633): the repair of today's blocks
 * whose item left the library, through [repair] (`TodayRepairer.repairLost`) at the time [nowMs] gives. The repair
 * reads the playable libraries itself, under its own lock, from the saved selection.
 *
 * A [RepairOutcome.Stale] is tried once more at once, as the call reads the lineup afresh: the refresher publishes a
 * new revision on the same generation move that asks for this repair. [RepairOutcome.Saved],
 * [RepairOutcome.SelectionUnknown] and [RepairOutcome.NoLineup] end it; every other outcome (a second stale included)
 * is reported ([LineupEvent.LostRepairNotSaved]) and nothing changed. It throws only what [repair] throws, for the
 * caller (the refresher) to report.
 */
class LostRepairHook(
    private val repair: suspend (LibraryAccount, Long) -> RepairOutcome<Int>,
    private val nowMs: () -> Long,
    private val report: (LineupEvent) -> Unit,
) {
    suspend operator fun invoke(account: LibraryAccount) {
        var outcome = repair(account, nowMs())
        if (outcome is RepairOutcome.Stale) outcome = repair(account, nowMs())
        // Every case by name, so a new outcome must be placed here.
        when (outcome) {
            is RepairOutcome.Saved, RepairOutcome.SelectionUnknown, RepairOutcome.NoLineup -> Unit

            RepairOutcome.Stale, RepairOutcome.Refused, RepairOutcome.Unsaved, RepairOutcome.UndoFailed,
            RepairOutcome.AlreadySaved,
            -> report(LineupEvent.LostRepairNotSaved)
        }
    }
}
