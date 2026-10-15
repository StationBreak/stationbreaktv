// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.net

import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients

/**
 * Keeps the pinned client in step with the stored record (spec 05, Unit 3, Angua (Security)). Whatever the record says
 * about the pin is true of the client before the call returns, so a request made right after a save or a clear never
 * runs under the old trust. A cleared pin is taken out of the client first, whether or not the write reaches disk:
 * trust is withdrawn before it is forgotten. The first [load] applies the stored pin, so a caller that reads the
 * settings before it connects (launch routing does) has the pin in force by the time it does.
 */
internal class PinSyncingServerSettings(
    private val stored: ServerSettings,
    private val clients: PinnedHttpClients,
) : ServerSettings {
    override suspend fun load(): ServerRecord? = stored.load().also(::apply)

    override suspend fun save(record: ServerRecord): Boolean {
        val written = stored.save(record)
        apply(record)
        return written
    }

    override suspend fun clearPin(): Boolean {
        clients.setPins(emptyMap())
        return stored.clearPin()
    }

    private fun apply(record: ServerRecord?) {
        val pin = record?.pin
        clients.setPins(if (record != null && pin != null) mapOf(record.address to pin) else emptyMap())
    }

    // A constant: nothing about the record may reach a log through this.
    override fun toString(): String = "PinSyncingServerSettings"
}
