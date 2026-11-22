// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.pro

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Pro cache on a real DataStore in a temporary folder, reopened to read back what reached the disk. */
@Suppress("InjectDispatcher") // Real files on the real IO dispatcher, as the app's cache uses.
class DataStoreProCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun open(): DataStoreProCache {
        close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DataStoreProCache.inDir(scope, folder.root)
    }

    private fun corrupt(name: String) = File(folder.root, name).writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42))

    @After
    fun close() =
        runBlocking {
            scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
            scopes.clear()
        }

    @Test
    fun nothingStoredReadsAsNotOwned() = runBlocking { assertFalse(open().read()) }

    @Test
    fun aVerifiedPurchaseSurvivesARelaunch_andARevokeClearsIt() =
        runBlocking {
            open().write(true)
            assertTrue(open().read())

            open().write(false)
            assertFalse(open().read())
        }

    @Test
    fun anOwedRefundResetSurvivesARelaunch_untilItIsCleared() =
        runBlocking {
            assertEquals(false, open().readRefundPending())

            open().writeRefundPending(true)
            assertEquals(true, open().readRefundPending())

            open().writeRefundPending(false)
            assertEquals(false, open().readRefundPending())
        }

    @Test
    fun theHandledAccountsSurviveARelaunch_andEndWithTheRefund() =
        runBlocking {
            open().writeRefundPending(true)
            open().addRefundHandled("a")
            open().addRefundHandled("b")
            assertEquals(setOf("a", "b"), open().readRefundHandled())

            open().writeRefundPending(false)
            assertEquals(emptySet<String>(), open().readRefundHandled())
        }

    @Test
    fun aCorruptFileGrantsNothing_andTheNextWriteWorks() =
        runBlocking {
            corrupt(DataStoreProCache.FILE_NAME)

            val cache = open()
            assertFalse("a corrupt cache never grants Pro", cache.read())
            assertEquals(false, cache.readRefundPending())

            cache.write(true)
            assertTrue(open().read())
        }

    @Test
    fun aCorruptCacheFileKeepsTheOwedRefund() =
        runBlocking {
            open().writeRefundPending(true)
            open().addRefundHandled("a")
            corrupt(DataStoreProCache.FILE_NAME)

            val cache = open()
            assertFalse(cache.read())
            assertEquals("the refund is in a file of its own", true, cache.readRefundPending())
            assertEquals(setOf("a"), cache.readRefundHandled())
        }

    @Test
    fun aCorruptRefundFileReadsAsUnknownWithNoAccountHandled_untilAVerifiedAnswerWrites() =
        runBlocking {
            open().writeRefundPending(true)
            open().addRefundHandled("a")
            corrupt(DataStoreProCache.REFUND_FILE_NAME)

            val cache = open()
            assertNull("neither owed nor clear", cache.readRefundPending())
            assertEquals(emptySet<String>(), cache.readRefundHandled())
            assertNull("kept across a relaunch", open().readRefundPending())

            open().writeRefundPending(false)
            assertEquals(false, open().readRefundPending())
        }

    @Test
    fun anUnreadableRefundFileReadsAsUnknown() =
        runBlocking {
            File(folder.root, DataStoreProCache.REFUND_FILE_NAME).mkdirs()

            assertNull(open().readRefundPending())
        }

    @Test
    fun anOwedRefundInTheOldFileMovesToTheRefundFile() =
        runBlocking {
            // The #1063 layout: the refund and its handled accounts beside the owned answer in the one cache file.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            DataStoreProCache.dataStore(scope) { File(folder.root, DataStoreProCache.FILE_NAME) }.edit {
                it[booleanPreferencesKey("owned")] = false
                it[booleanPreferencesKey("refund_pending")] = true
                it[stringSetPreferencesKey("refund_handled")] = setOf("a")
            }

            val cache = open()
            assertEquals(true, cache.readRefundPending())
            assertEquals(setOf("a"), cache.readRefundHandled())

            corrupt(DataStoreProCache.FILE_NAME)
            assertEquals("moved, not only read through", true, open().readRefundPending())
            assertEquals(setOf("a"), open().readRefundHandled())
        }
}
