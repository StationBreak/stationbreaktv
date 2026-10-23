// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ChannelId

/** A [LastChannelStore] in memory for tests. It can be told to fail a save, and never throws for a fault. */
class InMemoryLastChannelStore : LastChannelStore {
    private val channels = mutableMapOf<LibraryAccount, ChannelId>()

    var failSave = false

    override suspend fun load(account: LibraryAccount): ChannelId? = channels[account]

    override suspend fun save(
        account: LibraryAccount,
        channel: ChannelId,
    ): Boolean {
        if (failSave) return false
        channels[account] = channel
        return true
    }
}
