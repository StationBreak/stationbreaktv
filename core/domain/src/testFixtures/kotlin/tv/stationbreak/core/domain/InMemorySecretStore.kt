// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * A [SecretStore] in memory for tests (ADR-0004). It can be told to fail, and it records which keys were written and
 * cleared, so a test can prove nothing else was touched. Follows F0.4's contract: it never throws for a fault.
 */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    var failLoad = false
    var failSave = false
    var failClear = false

    /** Keys of every save that succeeded, in order. */
    val writtenKeys = mutableListOf<String>()

    /** Keys of every clear that succeeded, in order. */
    val clearedKeys = mutableListOf<String>()

    fun snapshot(): Map<String, String> = values.toMap()

    override suspend fun save(
        key: String,
        value: String,
    ): SecretStoreResult<Unit> =
        if (failSave) {
            SecretStoreResult.Failure
        } else {
            values[key] = value
            writtenKeys += key
            SecretStoreResult.Success(Unit)
        }

    override suspend fun load(key: String): SecretStoreResult<String?> =
        if (failLoad) SecretStoreResult.Failure else SecretStoreResult.Success(values[key])

    override suspend fun clear(key: String): SecretStoreResult<Unit> =
        if (failClear) {
            SecretStoreResult.Failure
        } else {
            values.remove(key)
            clearedKeys += key
            SecretStoreResult.Success(Unit)
        }
}
