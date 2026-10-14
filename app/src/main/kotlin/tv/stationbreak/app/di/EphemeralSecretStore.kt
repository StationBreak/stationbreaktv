// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SecretStoreResult
import java.util.concurrent.ConcurrentHashMap

/**
 * A [SecretStore] that lives only as long as the process, so the graph resolves before F0.4 builds the Keystore-backed
 * one. Nothing is written to disk, which is the safe failure: the device ID is regenerated on the next launch and no
 * session survives a restart. F0.4 replaces the binding in [MediaServerModule], and nothing else changes.
 */
internal class EphemeralSecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ): SecretStoreResult<Unit> {
        values[key] = value
        return SecretStoreResult.Success(Unit)
    }

    override suspend fun load(key: String): SecretStoreResult<String?> = SecretStoreResult.Success(values[key])

    override suspend fun clear(key: String): SecretStoreResult<Unit> {
        values.remove(key)
        return SecretStoreResult.Success(Unit)
    }
}
