// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.model.ItemId

/**
 * Changes a lineup through [LineupService], under its lock (spec 21): the engine's edits stay on this side of the
 * port. [libraries] is the libraries the viewer chose, read when a reset plans a new lineup; null means none chosen
 * or unreadable.
 */
class ChannelManager(
    private val service: LineupService,
    private val libraries: suspend () -> Set<ItemId>?,
) : ChannelEditor {
    override fun access(account: LibraryAccount): Flow<LineupAccess> =
        flow {
            service.loaded(account)
            emitAll(service.lineup(account))
        }.map {
            when {
                it == null -> LineupAccess.Loading
                it.writable -> LineupAccess.Editable
                else -> LineupAccess.ReadOnly
            }
        }.distinctUntilChanged()

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult =
        when (val saved = service.edit(account, change.toEdit())) {
            null -> {
                ChangeResult.NotEditable
            }

            Saved.Failed, Saved.Unavailable -> {
                ChangeResult.SaveFailed
            }

            Saved.Invalid -> {
                ChangeResult.Refused(ChangeRefusal.Invalid)
            }

            is Saved.Ok -> {
                when (val outcome = saved.value) {
                    is EditOutcome.Applied -> ChangeResult.Done
                    is EditOutcome.Refused -> ChangeResult.Refused(outcome.refusal.toRefusal())
                }
            }
        }

    // A choice that cannot be read is an expected failure here, as is none chosen: nothing is written, and the caller
    // may try again. So is a failure of the plan or the save itself: it is reported as a refresh's would be, and is
    // [ResetResult.Failed] (the service changes nothing until the new lineup is saved). Only cancellation goes through.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun reset(account: LibraryAccount): ResetResult {
        val chosen =
            try {
                libraries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                service.reportEvent(LineupEvent.ResetLibrariesUnavailable)
                null
            }
        return if (chosen.isNullOrEmpty()) ResetResult.LibrariesUnavailable else resetFrom(account, chosen)
    }

    @Suppress("TooGenericExceptionCaught") // Any failure of the plan or the save: see [reset].
    private suspend fun resetFrom(
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): ResetResult =
        try {
            when (val saved = service.reset(account, chosen)) {
                null -> ResetResult.NotEditable
                Saved.Failed, Saved.Invalid -> ResetResult.SaveFailed
                Saved.Unavailable -> ResetResult.LibrariesUnavailable
                is Saved.Ok -> if (saved.value.hasOn()) ResetResult.Chosen else ResetResult.NotChosenYet
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            service.reportEvent(LineupEvent.RefreshFailed)
            ResetResult.Failed
        }
}

private fun EditRefusal.toRefusal(): ChangeRefusal =
    when (this) {
        EditRefusal.UnknownChannel -> ChangeRefusal.UnknownChannel
        EditRefusal.NotSwitchable -> ChangeRefusal.NotSwitchable
        EditRefusal.OptInNotConfirmed -> ChangeRefusal.OptInNotConfirmed
        EditRefusal.LastOn -> ChangeRefusal.LastOn
        EditRefusal.NoFreeNumber -> ChangeRefusal.NoFreeNumber
        EditRefusal.OutOfRange -> ChangeRefusal.OutOfRange
        EditRefusal.KidsBandOnly -> ChangeRefusal.KidsBandOnly
        is EditRefusal.NumberTaken -> ChangeRefusal.NumberTaken(holder)
    }

private fun Lineup.hasOn() = channels.any { it.state == ChannelState.On }

private fun ChannelChange.toEdit(): LineupEdit =
    when (this) {
        is ChannelChange.SwitchOn -> LineupEdit.SwitchOn(id, confirmedOptIn)
        is ChannelChange.SwitchOff -> LineupEdit.SwitchOff(id)
        is ChannelChange.SetNumber -> LineupEdit.SetNumber(id, number, swapWith)
        is ChannelChange.ClearNumber -> LineupEdit.ClearNumber(id, swapWith)
    }
