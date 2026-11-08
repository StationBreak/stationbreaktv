// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.model.ItemId

/**
 * What runs after a sync (spec 26, #633): the repair of today's blocks whose item left the library, through
 * [repair] (`TodayRepairer.repairLost`), with the playable libraries [playable] gives now (null: the selection is
 * gone, and the repair fills from the recorded tail only, so it is passed on as it is).
 *
 * A [RepairOutcome.Stale] is tried once more at once, as the call reads the lineup afresh: the refresher publishes a
 * new revision on the same generation move that asks for this repair. [RepairOutcome.Saved],
 * [RepairOutcome.SelectionUnknown] and [RepairOutcome.NoLineup] end it; anything else (including a second stale) is
 * reported ([LineupEvent.LostRepairNotSaved]) and nothing changed. It throws only what [repair] or [playable] throw,
 * for the caller (the refresher) to report.
 */
class LostRepairHook(
    private val repair: suspend (LibraryAccount, Set<ItemId>?, Long) -> RepairOutcome<Int>,
    private val playable: suspend () -> Set<ItemId>?,
    private val nowMs: () -> Long,
    private val report: (LineupEvent) -> Unit,
) {
    suspend operator fun invoke(account: LibraryAccount) {
        var outcome = attempt(account)
        if (outcome is RepairOutcome.Stale) outcome = attempt(account)
        when (outcome) {
            is RepairOutcome.Saved, RepairOutcome.SelectionUnknown, RepairOutcome.NoLineup -> Unit
            else -> report(LineupEvent.LostRepairNotSaved)
        }
    }

    private suspend fun attempt(account: LibraryAccount): RepairOutcome<Int> = repair(account, playable(), nowMs())
}
