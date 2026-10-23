// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ChannelId

/**
 * The channel an account last watched, so launch can reopen it (spec 14). One value per [LibraryAccount]: another
 * account on the same TV has its own. Faults are `null` and `false`, never exceptions, and nothing is logged.
 */
interface LastChannelStore {
    /** The account's last channel, or null when none is saved or the saved value can't be read. */
    suspend fun load(account: LibraryAccount): ChannelId?

    /** Remembers [channel] for [account], replacing any earlier one. False when it could not be stored. */
    suspend fun save(
        account: LibraryAccount,
        channel: ChannelId,
    ): Boolean
}
