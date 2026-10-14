// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/** What a [SecretStore] call returns: a value, or [Failure], which carries nothing (there is nothing safe to say). */
sealed interface SecretStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : SecretStoreResult<T>

    data object Failure : SecretStoreResult<Nothing>
}

/**
 * A minimal async store of opaque secret strings by key (F0.3 S0.3.2). The Keystore-backed implementation is F0.4's.
 *
 * F0.4's contract: no call throws for a storage fault; it returns [SecretStoreResult.Failure], which callers read as
 * "treat as signed out for now" and never as a reason to wipe. Any failure routes to sign in again. There is no
 * plaintext fallback: an implementation that can't encrypt fails instead of writing the value some other way.
 * Values are secrets: nothing may log them.
 */
interface SecretStore {
    suspend fun save(
        key: String,
        value: String,
    ): SecretStoreResult<Unit>

    /** [SecretStoreResult.Success] with null when the key holds nothing. */
    suspend fun load(key: String): SecretStoreResult<String?>

    suspend fun clear(key: String): SecretStoreResult<Unit>
}
