// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId

/** One change the viewer makes to a channel of the lineup (spec 21), in terms features can build. */
sealed interface ChannelChange {
    /** Switches the channel on. [confirmedOptIn] must be true for an opt-in (After Hours) channel. */
    data class SwitchOn(
        val id: ChannelId,
        val confirmedOptIn: Boolean = false,
    ) : ChannelChange

    /** Hides the channel. */
    data class SwitchOff(
        val id: ChannelId,
    ) : ChannelChange

    /** Moves the channel to [number]; with [swapWith], that channel, which holds [number], takes its old number. */
    data class SetNumber(
        val id: ChannelId,
        val number: Int,
        val swapWith: ChannelId? = null,
    ) : ChannelChange

    /** Back to the channel's default number; with [swapWith], that channel, which holds it, takes the current one. */
    data class ClearNumber(
        val id: ChannelId,
        val swapWith: ChannelId? = null,
    ) : ChannelChange
}

/** Why a change was not made. */
sealed interface ChangeRefusal {
    data object UnknownChannel : ChangeRefusal

    data object NotSwitchable : ChangeRefusal

    data object OptInNotConfirmed : ChangeRefusal

    data object LastOn : ChangeRefusal

    data object NoFreeNumber : ChangeRefusal

    data object OutOfRange : ChangeRefusal

    data object KidsBandOnly : ChangeRefusal

    /** Another channel, [holder], has the number (its name is in the channel list). */
    data class NumberTaken(
        val holder: ChannelId,
    ) : ChangeRefusal

    /** The change would break the lineup's rules, which the other refusals should have caught. */
    data object Invalid : ChangeRefusal
}

/** What a [ChannelChange] did. */
sealed interface ChangeResult {
    data object Done : ChangeResult

    /** The lineup may not be written on this TV, or there is none. */
    data object NotEditable : ChangeResult

    /** The change could not be saved; nothing changed. */
    data object SaveFailed : ChangeResult

    /** The change was not made, for [reason]; nothing changed. */
    data class Refused(
        val reason: ChangeRefusal,
    ) : ChangeResult
}

/** What a reset left: [NotChosenYet] is a new lineup with no On channel yet, which the planner finishes later. */
enum class ResetResult {
    Chosen,
    NotChosenYet,
    NotEditable,

    /** The change could not be saved; nothing changed. */
    SaveFailed,

    /** No library is chosen, or the choice could not be read just now; nothing changed. */
    LibrariesUnavailable,
}

/** Whether a lineup can be changed on this TV. */
enum class LineupAccess {
    /** No lineup is published yet. */
    Loading,

    /** There is a lineup and it may be written. */
    Editable,

    /** There is a lineup but it may not be written here (a newer app's, or a file that could not be read). */
    ReadOnly,
}

/** How features change an account's channel lineup (spec 21). None of it throws for an expected failure. */
interface ChannelEditor {
    /** [account]'s lineup access, following the lineup. */
    fun access(account: LibraryAccount): Flow<LineupAccess>

    suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult

    /**
     * Replaces the lineup with a fresh choice from the library already on this TV, in one step: there is never a
     * moment with no lineup. NotChosenYet: the new lineup has no On channel yet (the planner finalises it later).
     * LibrariesUnavailable: no library is chosen or the choice or library could not be read, and nothing changed.
     */
    suspend fun reset(account: LibraryAccount): ResetResult
}
