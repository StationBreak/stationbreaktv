// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/** A [LineupStore] in memory for tests (ADR-0004). It can be told to fail writes; faults never throw. */
class InMemoryLineupStore : LineupStore {
    private val held = mutableMapOf<LibraryAccount, ByteArray>()
    private val aside = mutableMapOf<LibraryAccount, ByteArray>()

    var failWrites = false

    /** Makes a load of an existing lineup come back [StoredBytes.Failed]. */
    var failReads = false

    /** The set-aside copy for [account], if any. */
    fun setAsideCopy(account: LibraryAccount): ByteArray? = aside[account]

    override suspend fun load(account: LibraryAccount): StoredBytes {
        val bytes = held[account] ?: return StoredBytes.Absent
        return if (failReads) StoredBytes.Failed else StoredBytes.Present(bytes.copyOf())
    }

    override suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean {
        if (failWrites) return false
        held[account] = bytes.copyOf()
        return true
    }

    override suspend fun setAside(account: LibraryAccount): Boolean {
        val bytes = held[account] ?: return true
        if (failWrites) return false
        aside[account] = bytes
        held.remove(account)
        return true
    }

    override suspend fun forget(account: LibraryAccount): Boolean {
        if (failWrites) return false
        held.remove(account)
        return true
    }
}
