// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceIdTest {
    private val store = InMemorySecretStore()
    private var counter = 0
    private val provider = StoredDeviceIdProvider(store) { "generated-${++counter}" }

    @Test
    fun theFirstCallGeneratesAndStoresItUnderItsOwnKey() =
        runBlocking {
            val id = provider.deviceId()
            assertEquals("generated-1", id)
            assertEquals(SecretStoreResult.Success(id), store.load(StoredDeviceIdProvider.KEY))
            assertNotEquals(SessionRepository.SESSION_KEY, StoredDeviceIdProvider.KEY)
        }

    @Test
    fun laterCallsReturnTheSameValueEvenFromAnotherProvider() =
        runBlocking {
            val first = provider.deviceId()
            assertEquals(first, provider.deviceId())
            assertEquals(first, StoredDeviceIdProvider(store) { "never" }.deviceId())
        }

    @Test
    fun clearingTheSessionKeyLeavesIt() =
        runBlocking {
            val id = provider.deviceId()
            store.clear(SessionRepository.SESSION_KEY)
            assertEquals(id, StoredDeviceIdProvider(store) { "never" }.deviceId())
        }

    @Test
    fun aFailedLoadGeneratesOneInMemoryAndTriesToSaveWithoutCrashing() =
        runBlocking {
            store.failLoad = true
            store.failSave = true
            val id = provider.deviceId()
            assertEquals("generated-1", id)
            // Stable for the life of the provider, even though nothing could be stored.
            assertEquals(id, provider.deviceId())
            assertEquals(emptyList(), store.writtenKeys)
        }

    @Test
    fun aFailedSaveAloneKeepsTheIdForTheProviderAndStoresNothing() =
        runBlocking {
            // The load succeeds and finds nothing, then the save fails.
            store.failSave = true
            val id = provider.deviceId()
            assertEquals("generated-1", id)
            assertEquals(id, provider.deviceId())
            assertEquals(1, counter)
            assertEquals(emptyList(), store.writtenKeys)
            assertEquals(SecretStoreResult.Success(null), store.load(StoredDeviceIdProvider.KEY))
        }

    @Test
    fun aFailedLoadNeverOverwritesTheStoredId() =
        runBlocking {
            val stored = provider.deviceId()
            store.failLoad = true
            val other = StoredDeviceIdProvider(store) { "generated-in-memory" }
            assertEquals("generated-in-memory", other.deviceId())
            store.failLoad = false
            assertEquals(SecretStoreResult.Success(stored), store.load(StoredDeviceIdProvider.KEY))
            assertEquals(listOf(StoredDeviceIdProvider.KEY), store.writtenKeys)
        }

    @Test
    fun theDefaultGeneratorIsARandomUuidNotAHardwareIdentifier() =
        runBlocking {
            val id = StoredDeviceIdProvider(InMemorySecretStore()).deviceId()
            assertNotNull(UUID.fromString(id))
            assertTrue(id != StoredDeviceIdProvider(InMemorySecretStore()).deviceId())
        }

    @Test
    fun twoSimultaneousFirstCallsReturnOneIdAndSaveOnce() =
        runBlocking {
            // A slow store, so the second caller arrives while the first is still between its load and its save.
            val slow =
                object : SecretStore by store {
                    override suspend fun load(key: String): SecretStoreResult<String?> {
                        val answer = store.load(key)
                        delay(50)
                        return answer
                    }
                }
            val shared = StoredDeviceIdProvider(slow) { "generated-${++counter}" }
            val ids = (1..2).map { async { shared.deviceId() } }.awaitAll()
            assertEquals(1, ids.toSet().size)
            assertEquals(listOf(StoredDeviceIdProvider.KEY), store.writtenKeys)
            assertEquals(1, counter)
        }
}
