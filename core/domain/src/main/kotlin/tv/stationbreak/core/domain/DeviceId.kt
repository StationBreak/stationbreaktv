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
 * user and device, ADR-0006). Not derived from `ANDROID_ID` or any hardware identifier. If the store can't be read or
 * written, the ID lives in memory for this run only.
 */
class StoredDeviceIdProvider(
    private val store: SecretStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DeviceIdProvider {
    private var cached: String? = null

    override suspend fun deviceId(): String {
        val known = cached ?: existing() ?: newId().also { store.save(KEY, it) }
        cached = known
        return known
    }

    private suspend fun existing(): String? = (store.load(KEY) as? SecretStoreResult.Success)?.value

    companion object {
        const val KEY = "device_id"
    }
}
