// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.io.IOException
import java.util.concurrent.Executor

/** The stored record behind the [tv.stationbreak.core.domain.ServerSettings] port, with the file faked. */
class FileServerSettingsTest {
    private class MemoryFile : RecordFile {
        var text: String? = null
        var failWrites = false
        var failReads = false
        var writes = 0

        override fun read(): String? {
            if (failReads) throw IOException("unreadable")
            return text
        }

        override fun write(text: String) {
            if (failWrites) throw IOException("disk full")
            writes++
            this.text = text
        }
    }

    // The caller's own thread: the faked file does no I/O.
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val file = MemoryFile()
    private val settings = FileServerSettings(file, inPlace)
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 11 })

    @Test
    fun nothingStoredLoadsAsNull() = runBlocking { assertNull(settings.load()) }

    @Test
    fun aSavedRecordLoadsBackThroughANewStoreOnTheSameFile() =
        runBlocking {
            assertTrue(settings.save(ServerRecord(address, pin)))
            val rebuilt = FileServerSettings(file, inPlace)
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
    fun clearingWhenTheFileCannotBeReadOrParsedReportsFalseNotSuccess() =
        runBlocking {
            file.text = "version=1\nscheme=https\nho"
            assertFalse(settings.clearPin())
            file.text = null
            file.failReads = true
            assertFalse(settings.clearPin())
        }

    private val movies = ItemId("movies")
    private val shows = ItemId("shows")

    @Test
    fun theSelectionIsOneWriteAndLoadsBackThroughANewStoreOnTheSameFile() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val before = file.writes
            assertTrue(settings.saveLibraries(setOf(movies, shows)))
            assertEquals(before + 1, file.writes)
            val rebuilt = FileServerSettings(file, inPlace)
            assertEquals(setOf(movies, shows), rebuilt.loadLibraries())
            assertEquals(ServerRecord(address, pin), rebuilt.load())
        }

    @Test
    fun aSelectionNeedsARecordAndIsNeverEmpty() =
        runBlocking {
            assertFalse(settings.saveLibraries(setOf(movies)))
            assertNull(file.text)
            settings.save(ServerRecord(address))
            assertFalse(settings.saveLibraries(emptySet()))
            assertNull(settings.loadLibraries())
        }

    @Test
    fun clearingThePinAndSavingTheSameAddressKeepTheSelection() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveLibraries(setOf(movies))
            assertTrue(settings.clearPin())
            assertEquals(setOf(movies), settings.loadLibraries())
            assertTrue(settings.save(ServerRecord(address, pin)))
            assertEquals(setOf(movies), settings.loadLibraries())
        }

    @Test
    fun savingAnotherAddressDropsTheSelectionInTheSameWrite() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveLibraries(setOf(movies))
            val before = file.writes
            val other = ServerAddress(Scheme.Https, "other.example.test", 443)
            assertTrue(settings.save(ServerRecord(other)))
            assertEquals(before + 1, file.writes)
            assertEquals(ServerRecord(other), settings.load())
            assertNull(settings.loadLibraries())
        }

    @Test
    fun aFailedSelectionWriteKeepsTheOldRecordAndSelection() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveLibraries(setOf(movies))
            file.failWrites = true
            assertFalse(settings.saveLibraries(setOf(shows)))
            assertEquals(setOf(movies), settings.loadLibraries())
            assertEquals(ServerRecord(address, pin), settings.load())
        }

    @Test
    fun aFileWrittenBeforeSelectionsExistedHasNoneAndKeepsItsRecord() =
        runBlocking {
            file.text = ServerRecordCodec.encode(ServerRecord(address, pin))
            assertNull(settings.loadLibraries())
            assertTrue(settings.saveLibraries(setOf(movies)))
            assertEquals(ServerRecord(address, pin), settings.load())
        }

    @Test
    fun theStoreShowsNothingInToString() {
        assertEquals("FileServerSettings", settings.toString())
    }

    @Test
    fun clearingTheSelectionIsOneWriteThatKeepsTheRecordAndPin() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveLibraries(setOf(movies))
            val before = file.writes

            assertTrue(settings.clearLibraries())

            assertEquals(before + 1, file.writes)
            assertNull(settings.loadLibraries())
            assertEquals(ServerRecord(address, pin), settings.load())
        }

    @Test
    fun clearingWithNothingToClearWritesNothing() =
        runBlocking {
            assertTrue(settings.clearLibraries())
            settings.save(ServerRecord(address))
            val before = file.writes
            assertTrue(settings.clearLibraries())
            assertEquals(before, file.writes)
        }

    @Test
    fun aFailedClearReportsFalseAndKeepsTheSelection() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveLibraries(setOf(movies))
            file.failWrites = true
            assertFalse(settings.clearLibraries())
            assertEquals(setOf(movies), settings.loadLibraries())
        }
}
