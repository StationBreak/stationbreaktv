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
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.isViewerCustom
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId

/**
 * Changes a lineup through [LineupService], under its lock (spec 21): the engine's edits stay on this side of the
 * port. [libraries] is the libraries the viewer chose, read when a reset plans a new lineup; null means none chosen
 * or unreadable. [switchOns] is told of each channel the viewer really switches on (one that was off), once it is
 * saved. Without Pro ([pro]), a change that touches a built-in channel (the planner's lineup) and a reset are refused
 * (#793): the viewer's own custom channels, and "use my collections", stay free.
 */
class ChannelManager(
    private val service: LineupService,
    private val pro: ProEntitlement,
    private val switchOns: ChannelSwitchOns,
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

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult {
        // "Done" also means "changed nothing", so a switch-on counts only for a channel known to be off before it.
        val wasOff = change is ChannelChange.SwitchOn && isOff(account, change.id)
        val result = attempt(account, change)
        if (wasOff && result == ChangeResult.Done && change is ChannelChange.SwitchOn) {
            switchOns.switchedOn(account, change.id)
        }
        return result
    }

    // Any failure other than cancellation (a store that throws, though none should) is Failed, and reported: the
    // service saves before it publishes, so nothing it has not saved is ever shown.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult =
        try {
            if (!pro.pro.value && touchesBuiltIn(account, change)) {
                ChangeResult.Refused(ChangeRefusal.NeedsPro)
            } else {
                resultOf(service.edit(account, change.toEdit(), librariesFor(change)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            service.reportEvent(LineupEvent.EditFailed)
            ChangeResult.Failed
        }

    // True when the change touches a channel that is not the viewer's own readable custom one, or swaps with one. An
    // unknown ID is left to the engine to refuse as unknown. "Use my collections" touches no channel.
    private suspend fun touchesBuiltIn(
        account: LibraryAccount,
        change: ChannelChange,
    ): Boolean {
        val touched =
            when (change) {
                is ChannelChange.SwitchOn -> listOf(change.id)
                is ChannelChange.SwitchOff -> listOf(change.id)
                is ChannelChange.SetNumber -> listOfNotNull(change.id, change.swapWith)
                is ChannelChange.ClearNumber -> listOfNotNull(change.id, change.swapWith)
                is ChannelChange.UseMyCollections -> emptyList()
            }
        // Fail closed: with no lineup to look at, a free viewer's change is gated.
        val channels = service.loaded(account)?.lineup?.channels
        return when {
            touched.isEmpty() -> false
            channels == null -> true
            else -> touched.any { id -> channels.firstOrNull { it.id == id }?.let { !it.isViewerCustom() } == true }
        }
    }

    private fun isOff(
        account: LibraryAccount,
        id: ChannelId,
    ): Boolean {
        val state =
            service
                .lineup(account)
                .value
                ?.lineup
                ?.channels
                ?.firstOrNull { it.id == id }
                ?.state
        return state != null && state != ChannelState.On
    }

    // Only the household switch plans again, so only it reads the viewer's libraries; none known saves the edit alone.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun librariesFor(change: ChannelChange): Set<ItemId>? =
        if (change !is ChannelChange.UseMyCollections) {
            null
        } else {
            try {
                libraries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                service.reportEvent(LineupEvent.ResetLibrariesUnavailable)
                null
            }
        }

    private fun resultOf(saved: Saved<EditOutcome>?): ChangeResult =
        saved.mapped(
            done = { ChangeResult.Done },
            refused = { ChangeResult.Refused(it.toRefusal()) },
        ) {
            when (it) {
                Unsaved.NotEditable -> ChangeResult.NotEditable
                Unsaved.SaveFailed -> ChangeResult.SaveFailed
                Unsaved.Invalid -> ChangeResult.Refused(ChangeRefusal.Invalid)
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
        if (!pro.pro.value) return ResetResult.NeedsPro
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

        // The custom-channel edits are not reachable from a ChannelChange.
        EditRefusal.NotCustom,
        EditRefusal.NotShuffle,
        EditRefusal.IdTaken,
        EditRefusal.CustomLimit,
        -> ChangeRefusal.Invalid
    }

private fun Lineup.hasOn() = channels.any { it.state == ChannelState.On }

private fun ChannelChange.toEdit(): LineupEdit =
    when (this) {
        is ChannelChange.SwitchOn -> LineupEdit.SwitchOn(id, confirmedOptIn)
        is ChannelChange.SwitchOff -> LineupEdit.SwitchOff(id)
        is ChannelChange.SetNumber -> LineupEdit.SetNumber(id, number, swapWith)
        is ChannelChange.ClearNumber -> LineupEdit.ClearNumber(id, swapWith)
        is ChannelChange.UseMyCollections -> LineupEdit.UseMyCollections(on)
    }

/** Why an edit through the service was not applied and not refused either. */
internal enum class Unsaved { NotEditable, SaveFailed, Invalid }

/**
 * What came of an edit through [LineupService.edit], in the caller's own words: [done] when it was applied (or changed
 * nothing), [refused] with the engine's reason, [other] when it was not made for any other reason. The one mapping of
 * `Saved`, shared by the channel and the builder managers.
 */
internal inline fun <R> Saved<EditOutcome>?.mapped(
    done: () -> R,
    refused: (EditRefusal) -> R,
    other: (Unsaved) -> R,
): R =
    when (this) {
        null -> {
            other(Unsaved.NotEditable)
        }

        Saved.Failed, Saved.Unavailable -> {
            other(Unsaved.SaveFailed)
        }

        Saved.Invalid -> {
            other(Unsaved.Invalid)
        }

        is Saved.Ok -> {
            when (val outcome = value) {
                is EditOutcome.Applied -> done()
                is EditOutcome.Refused -> refused(outcome.refusal)
            }
        }
    }
