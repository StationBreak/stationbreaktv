// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.io.IOException

/** The stored record behind the [tv.stationbreak.core.domain.ServerSettings] port, with the file faked. */
class FileServerSettingsTest {
    private class MemoryFile : RecordFile {
        var text: String? = null
        var failWrites = false
        var writes = 0

        override fun read(): String? = text

        override fun write(text: String) {
            if (failWrites) throw IOException("disk full")
            writes++
            this.text = text
        }
    }

    private val file = MemoryFile()
    private val settings = FileServerSettings(file, Dispatchers.Unconfined)
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 11 })

    @Test
    fun nothingStoredLoadsAsNull() = runBlocking { assertNull(settings.load()) }

    @Test
    fun aSavedRecordLoadsBackThroughANewStoreOnTheSameFile() =
        runBlocking {
            assertTrue(settings.save(ServerRecord(address, pin)))
            val rebuilt = FileServerSettings(file, Dispatchers.Unconfined)
            assertEquals(ServerRecord(address, pin), rebuilt.load())
        }

    @Test
    fun theAddressAndThePinAreOneWrite() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            assertEquals(1, file.writes)
        }

    @Test
    fun clearingThePinKeepsTheAddressInOneWrite() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            assertTrue(settings.clearPin())
            assertEquals(ServerRecord(address, null), settings.load())
            assertEquals(2, file.writes)
        }

    @Test
    fun clearingWithNoPinOrNoRecordWritesNothing() =
        runBlocking {
            assertTrue(settings.clearPin())
            settings.save(ServerRecord(address))
            val before = file.writes
            assertTrue(settings.clearPin())
            assertEquals(before, file.writes)
        }

    @Test
    fun aFailedWriteReportsFalseAndKeepsTheOldRecord() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            file.failWrites = true
            assertFalse(settings.save(ServerRecord(address)))
            assertFalse(settings.clearPin())
            assertEquals(ServerRecord(address, pin), settings.load())
        }

    @Test
    fun anUnreadableFileLoadsAsNullAndIsReplacedBySave() =
        runBlocking {
            file.text = "not a record"
            assertNull(settings.load())
            assertTrue(settings.save(ServerRecord(address)))
            assertEquals(ServerRecord(address), settings.load())
        }

    @Test
    fun theStoreShowsNothingInToString() {
        assertEquals("FileServerSettings", settings.toString())
    }
}
