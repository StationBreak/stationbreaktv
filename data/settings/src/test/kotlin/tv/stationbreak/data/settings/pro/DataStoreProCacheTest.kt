// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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
        return DataStoreProCache(DataStoreProCache.dataStore(scope) { File(folder.root, DataStoreProCache.FILE_NAME) })
    }

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
            assertFalse(open().readRefundPending())

            open().writeRefundPending(true)
            assertTrue(open().readRefundPending())

            open().writeRefundPending(false)
            assertFalse(open().readRefundPending())
        }

    @Test
    fun aCorruptFileGrantsNothing_andTheNextWriteWorks() =
        runBlocking {
            File(folder.root, DataStoreProCache.FILE_NAME).writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42))

            val cache = open()
            assertFalse("a corrupt cache never grants Pro", cache.read())
            assertFalse(cache.readRefundPending())

            cache.write(true)
            assertTrue(open().read())
        }
}
