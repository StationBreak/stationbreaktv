// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId

/**
 * Channels the viewer switched on mid-day that are still getting ready, and the notice that says they are ready
 * (#776). What features and the app's shell read: they cannot change it.
 *
 * A channel is *ready* when its day has something to air (it has no [HiddenReason]). Each switched-on channel ends one
 * way: ready (it is in [notice]), or dropped without a word: it came back hidden (the Channels list shows its reason),
 * it is off or gone, the account changed, or its day could not be built. The state lives as long as the process: it is
 * never saved, so a restart forgets what was getting ready, and a day that rolls over while a channel is pending
 * answers for the new day.
 */
interface ChannelReadiness {
    /** The channels getting ready now, for the active account; empty when none. */
    val pending: Flow<Set<ChannelId>>

    /** The notice to show now, or null. It clears itself, and carries no events: a recreated screen reads it again. */
    val notice: StateFlow<ReadyNotice?>
}

/**
 * Channels that are ready, to tell the viewer. [id] is new for each notice, so a screen can tell a notice that grew
 * from the last one.
 */
data class ReadyNotice(
    val id: Long,
    val channels: List<ReadyChannel>,
)

/** A channel that is ready, as the notice names it. */
data class ReadyChannel(
    val number: Int,
    val name: String,
)

/**
 * The side that tells [ChannelReadiness] of a switch-on. Only the channel manager has it, so no feature can raise a
 * notice of its own.
 */
fun interface ChannelSwitchOns {
    /** [id] was switched off and the viewer has just switched it on, in [account]'s lineup. */
    fun switchedOn(
        account: LibraryAccount,
        id: ChannelId,
    )
}
