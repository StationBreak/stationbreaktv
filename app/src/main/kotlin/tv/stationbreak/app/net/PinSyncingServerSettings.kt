// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients

/**
 * Keeps the pinned client in step with the stored record (spec 05, Unit 3, Angua (Security)). After every operation the
 * client holds exactly the pin of the record that is **stored**, read back inside one lock, so two operations racing
 * can't leave the client trusting what the disk no longer says. A clear withdraws trust from the client first, whether
 * or not the write reaches disk: trust is withdrawn before it is forgotten. A [load] applies the stored pin, so a caller
 * that reads the settings before it connects has the pin in force by then. (Nothing in main code reads the settings
 * yet: launch routing, task 4.0, will.)
 */
internal class PinSyncingServerSettings(
    private val stored: ServerSettings,
    private val clients: PinnedHttpClients,
) : ServerSettings {
    private val lock = Mutex()

    override suspend fun load(): ServerRecord? = lock.withLock { stored.load().also(::apply) }

    override suspend fun save(record: ServerRecord): Boolean =
        lock.withLock {
            val written = stored.save(record)
            // The record actually stored: after a failed write that is the old one, and the new pin is not trusted.
            apply(stored.load())
            written
        }

    override suspend fun clearPin(): Boolean =
        lock.withLock {
            clients.setPins(emptyMap())
            stored.clearPin()
        }

    private fun apply(record: ServerRecord?) {
        val pin = record?.pin
        clients.setPins(if (record != null && pin != null) mapOf(record.address to pin) else emptyMap())
    }

    // A constant: nothing about the record may reach a log through this.
    override fun toString(): String = "PinSyncingServerSettings"
}
