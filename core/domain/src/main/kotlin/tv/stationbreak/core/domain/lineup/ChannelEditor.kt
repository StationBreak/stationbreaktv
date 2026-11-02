// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId

/** One change the viewer makes to a channel of the lineup (spec 21), in terms features can build. */
sealed interface ChannelChange {
    val id: ChannelId

    /** Switches the channel on. [confirmedOptIn] must be true for an opt-in (After Hours) channel. */
    data class SwitchOn(
        override val id: ChannelId,
        val confirmedOptIn: Boolean = false,
    ) : ChannelChange

    /** Hides the channel. */
    data class SwitchOff(
        override val id: ChannelId,
    ) : ChannelChange

    /** Moves the channel to [number]; with [swapWith], that channel, which holds [number], takes its old number. */
    data class SetNumber(
        override val id: ChannelId,
        val number: Int,
        val swapWith: ChannelId? = null,
    ) : ChannelChange

    /** Back to the channel's default number; with [swapWith], that channel, which holds it, takes the current one. */
    data class ClearNumber(
        override val id: ChannelId,
        val swapWith: ChannelId? = null,
    ) : ChannelChange
}

/** What a [ChannelChange] did: [Done], or why it did nothing. */
sealed interface ChangeResult {
    data object Done : ChangeResult

    /** The lineup may not be written on this TV, or there is none. */
    data object NotEditable : ChangeResult

    /** The change could not be saved; nothing changed. */
    data object SaveFailed : ChangeResult

    data object UnknownChannel : ChangeResult

    data object NotSwitchable : ChangeResult

    data object OptInNotConfirmed : ChangeResult

    data object LastOn : ChangeResult

    data object NoFreeNumber : ChangeResult

    data object OutOfRange : ChangeResult

    data object KidsBandOnly : ChangeResult

    /** Another channel, [holder] (shown as [holderName]), has the number. */
    data class NumberTaken(
        val holder: ChannelId,
        val holderName: String,
    ) : ChangeResult
}

/** What a reset left: [NotChosenYet] is a new lineup with no channel on yet, which the planner finishes later. */
enum class ResetResult {
    Chosen,
    NotChosenYet,
    NotEditable,

    /** The change could not be saved; nothing changed. */
    SaveFailed,
}

/** How features change an account's channel lineup (spec 21). */
interface ChannelEditor {
    /** Whether [account]'s lineup can be changed on this TV: there is one and it may be written. Follows the lineup. */
    fun editable(account: LibraryAccount): Flow<Boolean>

    suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult

    /**
     * Replaces the lineup with a fresh choice from the library already on this TV, in one step: there is never a
     * moment with no lineup. NotChosenYet: the new lineup has no On channel yet (the planner finalises it later). A
     * failure to read the library propagates (it is not stored data), so callers catch it.
     */
    suspend fun reset(account: LibraryAccount): ResetResult
}
