// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretStoreTest {
    private val store = InMemorySecretStore()

    @Test
    fun savesLoadsAndClearsByKey() =
        runBlocking {
            assertEquals(SecretStoreResult.Success(null), store.load("a"))
            store.save("a", "one")
            store.save("b", "two")
            assertEquals(SecretStoreResult.Success("one"), store.load("a"))
            store.clear("a")
            assertEquals(SecretStoreResult.Success(null), store.load("a"))
            assertEquals(SecretStoreResult.Success("two"), store.load("b"))
        }

    @Test
    fun aStorageFaultIsAFailureArmNotAnException() =
        runBlocking {
            store.save("a", "one")
            store.failLoad = true
            assertEquals(SecretStoreResult.Failure, store.load("a"))
            store.failLoad = false
            store.failSave = true
            assertEquals(SecretStoreResult.Failure, store.save("a", "two"))
            assertEquals(SecretStoreResult.Success("one"), store.load("a"))
        }

    @Test
    fun recordsWhichKeysWereWritten() =
        runBlocking {
            store.save("a", "one")
            store.failSave = true
            store.save("b", "two")
            assertEquals(listOf("a"), store.writtenKeys)
            assertTrue(store.snapshot().keys == setOf("a"))
        }
}
