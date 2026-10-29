// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredBytes
import java.util.concurrent.ConcurrentHashMap

/** A [DayStore] in memory, with faults to switch on. */
internal class MemoryDayStore : DayStore {
    val files = ConcurrentHashMap<Pair<LibraryAccount, String>, ByteArray>()

    @Volatile var failWrites = false

    @Volatile var failReads = false

    override suspend fun read(
        account: LibraryAccount,
        name: String,
    ): StoredBytes {
        val bytes = files[account to name] ?: return StoredBytes.Absent
        return if (failReads) StoredBytes.Failed else StoredBytes.Present(bytes)
    }

    override suspend fun write(
        account: LibraryAccount,
        name: String,
        bytes: ByteArray,
    ): Boolean {
        if (failWrites) return false
        files[account to name] = bytes
        return true
    }

    override suspend fun names(account: LibraryAccount): List<String> =
        files.keys
            .filter { it.first == account }
            .map { it.second }
            .sorted()

    override suspend fun delete(
        account: LibraryAccount,
        name: String,
    ): Boolean {
        files.remove(account to name)
        return true
    }

    fun only(): Pair<Pair<LibraryAccount, String>, ByteArray> = files.entries.single().let { it.key to it.value }
}
