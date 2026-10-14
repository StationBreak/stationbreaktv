// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import java.util.UUID

/**
 * Where the adapter gets the Jellyfin device ID (R13). A port, so Phase 5's per-profile policy changes this and not
 * the adapter.
 */
fun interface DeviceIdProvider {
    suspend fun deviceId(): String
}

/**
 * One random UUID per install, kept under its own key in the [SecretStore] and never cleared by sign-out, so signing
 * in again replaces the token an earlier failed logout left behind (Jellyfin revokes the earlier token for the same
 * user and device, ADR-0006). Not derived from `ANDROID_ID` or any hardware identifier.
 * If the store can't be read, a new ID lives in memory for this run only and the stored one is never
 * overwritten. If it can't be written, the same.
 */
class StoredDeviceIdProvider(
    private val store: SecretStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DeviceIdProvider {
    private var cached: String? = null

    override suspend fun deviceId(): String {
        cached?.let { return it }
        // Only a store that answered "nothing here" may be written. A failed load leaves whatever it holds alone.
        val id =
            when (val loaded = store.load(KEY)) {
                is SecretStoreResult.Success -> loaded.value ?: newId().also { store.save(KEY, it) }
                SecretStoreResult.Failure -> newId()
            }
        cached = id
        return id
    }

    companion object {
        const val KEY = "device_id"
    }
}
