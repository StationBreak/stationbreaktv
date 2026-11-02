// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock

/**
 * Changes a lineup through [LineupService], under its lock (spec 21): the engine's edits stay on this side of the
 * port. [libraries] is the libraries the viewer chose, read when a reset plans a new lineup. [clock] times the edits
 * (a channel switched on is on since then).
 */
class ChannelManager(
    private val service: LineupService,
    private val libraries: suspend () -> Set<ItemId>?,
    private val clock: Clock,
) : ChannelEditor {
    override fun editable(account: LibraryAccount): Flow<Boolean> =
        flow {
            service.loaded(account)
            emitAll(service.lineup(account))
        }.map { it?.writable == true }.distinctUntilChanged()

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult =
        when (val outcome = service.edit(account, change.toEdit(), clock.now().toEpochMilliseconds())) {
            null -> ChangeResult.NotEditable
            is EditOutcome.Applied -> ChangeResult.Done
            is EditOutcome.Refused -> outcome.refusal.toResult(account)
        }

    override suspend fun reset(account: LibraryAccount): ResetResult {
        val fresh = service.reset(account, libraries()) ?: return ResetResult.NotEditable
        return if (fresh.channels.any { it.state == ChannelState.On }) ResetResult.Chosen else ResetResult.NotChosenYet
    }

    private suspend fun EditRefusal.toResult(account: LibraryAccount): ChangeResult =
        when (this) {
            EditRefusal.UnknownChannel -> ChangeResult.UnknownChannel
            EditRefusal.NotSwitchable -> ChangeResult.NotSwitchable
            EditRefusal.OptInNotConfirmed -> ChangeResult.OptInNotConfirmed
            EditRefusal.LastOn -> ChangeResult.LastOn
            EditRefusal.NoFreeNumber -> ChangeResult.NoFreeNumber
            EditRefusal.OutOfRange -> ChangeResult.OutOfRange
            EditRefusal.KidsBandOnly -> ChangeResult.KidsBandOnly
            is EditRefusal.NumberTaken -> ChangeResult.NumberTaken(holder, nameOf(account, holder))
        }

    // A refusal changed nothing, so the lineup held now is the one the holder was found in.
    private suspend fun nameOf(
        account: LibraryAccount,
        holder: ChannelId,
    ): String =
        service
            .loaded(account)
            ?.lineup
            ?.channels
            ?.firstOrNull { it.id == holder }
            ?.displayName(service.catalog)
            ?: holder.value
}

private fun ChannelChange.toEdit(): LineupEdit =
    when (this) {
        is ChannelChange.SwitchOn -> LineupEdit.SwitchOn(id, confirmedOptIn)
        is ChannelChange.SwitchOff -> LineupEdit.SwitchOff(id)
        is ChannelChange.SetNumber -> LineupEdit.SetNumber(id, number, swapWith)
        is ChannelChange.ClearNumber -> LineupEdit.ClearNumber(id, swapWith)
    }
