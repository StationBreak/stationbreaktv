// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    // A failure to load (a store that throws, though none should) is shown as ReadOnly: nothing can be changed then.
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
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            service.reportEvent(LineupEvent.AccessFailed)
            emit(LineupAccess.ReadOnly)
        }.distinctUntilChanged()

    // Any failure other than cancellation (a store that throws, though none should) is Failed, and reported: the
    // service saves before it publishes, so nothing it has not saved is ever shown.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult {
        val saved =
            try {
                service.edit(account, change.toEdit())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                service.reportEvent(LineupEvent.EditFailed)
                return ChangeResult.Failed
            }
        return resultOf(saved)
    }

    private fun resultOf(saved: Saved<EditOutcome>?): ChangeResult =
        when (saved) {
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
    // may try again. So is a failure of the plan or the save itself: it is reported (ResetFailed) and is
    // [ResetResult.Failed]. That "nothing changed" holds because of LineupService.reset's order: all that can throw
    // (the plan, the encoding, the store's save) comes before anything is published or forgotten, and what follows a
    // save that succeeded (forgetting the derived days, publishing) does not throw. A store whose save throws after
    // writing is the one gap: the file then holds the new lineup and memory the old, until the next load.
    // Only cancellation goes through.
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

    // Only the service's call is guarded: the mapping of its result cannot fail.
    @Suppress("TooGenericExceptionCaught") // Any failure of the plan or the save: see [reset].
    private suspend fun resetFrom(
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): ResetResult {
        val saved =
            try {
                service.reset(account, chosen)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                service.reportEvent(LineupEvent.ResetFailed)
                return ResetResult.Failed
            }
        return when (saved) {
            null -> ResetResult.NotEditable
            Saved.Failed, Saved.Invalid -> ResetResult.SaveFailed
            Saved.Unavailable -> ResetResult.LibrariesUnavailable
            is Saved.Ok -> if (saved.value.hasOn()) ResetResult.Chosen else ResetResult.NotChosenYet
        }
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
