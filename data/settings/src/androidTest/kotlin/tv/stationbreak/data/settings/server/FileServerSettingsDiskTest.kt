// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.io.File
import java.util.UUID

/** The record on a real disk on the API 25 emulator: it survives a rebuilt store, and a rewrite is a replace. */
@RunWith(AndroidJUnit4::class)
class FileServerSettingsDiskTest {
    private lateinit var dir: File
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 21 })

    @Before
    fun makeDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dir = File(context.cacheDir, "settings-test-${UUID.randomUUID()}")
    }

    @After
    fun removeDirectory() {
        dir.deleteRecursively()
    }

    private fun store() = FileServerSettings(DiskRecordFile(File(dir, "server")))

    @Test
    fun theAddressAndPinSurviveARebuiltStore() =
        runBlocking {
            assertNull(store().load())
            assertTrue(store().save(ServerRecord(address, pin)))
            assertEquals(ServerRecord(address, pin), store().load())
        }

    @Test
    fun clearingThePinSurvivesARebuiltStoreAndKeepsTheAddress() =
        runBlocking {
            store().save(ServerRecord(address, pin))
            assertTrue(store().clearPin())
            assertEquals(ServerRecord(address, null), store().load())
        }

    @Test
    fun aSecondSaveReplacesTheFirstWholeAndLeavesNoTemporaryFile() =
        runBlocking {
            val other = ServerAddress(Scheme.Https, "other.example.test", 443)
            store().save(ServerRecord(address, pin))
            store().save(ServerRecord(other))
            assertEquals(ServerRecord(other, null), store().load())
            assertEquals(listOf("server"), dir.list().orEmpty().toList())
        }

    @Test
    fun aTornFileFromAnInterruptedWriteIsNotRead() =
        runBlocking {
            dir.mkdirs()
            File(dir, "server").writeText("version=1\nscheme=https\nho")
            assertNull(store().load())
            assertTrue(store().save(ServerRecord(address)))
            assertEquals(ServerRecord(address), store().load())
        }
}
