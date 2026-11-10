// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.storeChoice
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The selection is read from memory after the first read (spec 26 Part 4, §3): the tune path never waits on a writer
 * or the disk, a writer refreshes the memory, and a failed write re-reads the file at once.
 */
class FileServerSettingsMemoTest {
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val file = MemoryRecordFile()
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 5 })
    private val alpha = ItemId("alpha")
    private val beta = ItemId("beta")
    private val first = StoredSelection(setOf(alpha), seq = 1)
    private val second = StoredSelection(setOf(alpha, beta), seq = 2)

    private fun seeded(): FileServerSettings {
        file.text = ServerRecordCodec.encode(StoredSettings(ServerRecord(address), setOf(alpha), seq = 1))
        return FileServerSettings(file, inPlace)
    }

    @Test
    fun reads_afterTheFirst_neverTouchTheFile() =
        runBlocking {
            val settings = seeded()
            assertEquals(first, settings.loadSelection())
            val reads = file.reads
            repeat(3) {
                assertEquals(first, settings.loadSelection())
                assertEquals(setOf(alpha), settings.loadPlayableLibraries())
                assertEquals(setOf(alpha), settings.loadLibraries())
                assertEquals(1L, settings.loadSelectionSeq())
                assertEquals(ServerRecord(address), settings.load())
            }
            assertEquals(reads, file.reads)
        }

    @Test
    fun aWrite_setsTheMemory_soTheNextReadIsFreshWithoutTheFile() =
        runBlocking {
            val settings = seeded()
            settings.loadSelection()
            assertTrue(settings.saveSelection(second))
            val reads = file.reads
            assertEquals(second, settings.loadSelection())
            settings.clearLibraries()
            assertNull(settings.loadSelection())
            assertEquals(2L, settings.loadSelectionSeq())
            assertTrue(settings.storeChoice(setOf(beta)))
            assertEquals(setOf(beta), settings.loadPlayableLibraries())
            // Only the writers' own read-modify-write reads touched the file.
            assertEquals(reads + 2, file.reads)
        }

    @Test
    fun aFailedWrite_reReadsTheFileAtOnce() =
        runBlocking {
            val settings = seeded()
            settings.loadSelection()
            // The write reached the disk but reported a fault: the memory must follow the disk, not the old value.
            file.landThenFail = true
            assertFalse(settings.saveSelection(second))
            file.landThenFail = false
            val reads = file.reads
            assertEquals(second, settings.loadSelection())
            assertEquals(reads, file.reads)
        }

    // Ruling for Task 4.3a fix round 1: a failed re-read keeps the last good selection, so the tune path stays
    // lock-free and off the disk; the next successful write (or a writer's own successful read) replaces it.
    @Test
    fun aFailedWrite_whoseReReadFails_keepsTheLastGoodSelection_offTheDisk() =
        runBlocking {
            val settings = seeded()
            settings.loadSelection()
            file.failWrites = true
            file.failReadsAfterAWrite = true
            assertFalse(settings.saveSelection(second))
            file.failReadsAfterAWrite = false
            file.failWrites = false
            val reads = file.reads
            // The disk still fails, yet the read is answered at once from the last good value.
            assertEquals(first, settings.loadSelection())
            assertEquals(setOf(alpha), settings.loadPlayableLibraries())
            assertEquals(reads, file.reads)

            file.failReads = false
            assertTrue(settings.saveSelection(second))
            assertEquals(second, settings.loadSelection())
        }

    @Test
    fun clearPin_setsTheMemory() =
        runBlocking {
            file.text = ServerRecordCodec.encode(StoredSettings(ServerRecord(address, pin), setOf(alpha), seq = 1))
            val settings = FileServerSettings(file, inPlace)
            assertEquals(ServerRecord(address, pin), settings.load())
            assertTrue(settings.clearPin())
            val reads = file.reads
            assertEquals(ServerRecord(address), settings.load())
            assertEquals(first, settings.loadSelection())
            assertEquals(reads, file.reads)
        }

    @Test
    fun aFailedWrite_keepsTheOldMemoryWhileItReReads() =
        runBlocking {
            val settings = seeded()
            settings.loadSelection()
            file.failWrites = true
            // The re-read runs under the writer's lock. A lock-free reader probing then must still find a memo; an
            // emptied one would send it to the lock, where it would wait (here: time out).
            val seen = mutableListOf<StoredSelection?>()
            file.onRead = { seen += runBlocking { withTimeoutOrNull(PROBE_MS) { settings.loadSelection() } } }
            assertFalse(settings.saveSelection(second))
            file.onRead = null
            // Two reads under the lock: the writer's own, then the re-read after the failed write.
            assertEquals(listOf(first, first), seen)
            assertEquals(first, settings.loadSelection())
        }

    @Test
    fun aReadFault_isNotRemembered() =
        runBlocking {
            val settings = seeded()
            file.failReads = true
            assertNull(settings.loadSelection())
            file.failReads = false
            assertEquals(first, settings.loadSelection())
        }

    @Test
    fun anUnreadableText_isRemembered_untilAWriteReplacesIt() =
        runBlocking {
            file.text = "not a record"
            val settings = FileServerSettings(file, inPlace)
            assertNull(settings.loadSelection())
            val reads = file.reads
            assertNull(settings.loadSelection())
            assertNull(settings.load())
            assertEquals(reads, file.reads)
            assertTrue(settings.save(ServerRecord(address)))
            assertEquals(ServerRecord(address), settings.load())
        }

    @Test
    fun aSelectionRead_doesNotWaitForAWriterHoldingTheLock() =
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { threads ->
            runBlocking { readWhileAWriterStalls(threads) }
        }

    private suspend fun readWhileAWriterStalls(threads: CoroutineDispatcher) =
        coroutineScope {
            val settings = FileServerSettings(file, threads)
            file.text = ServerRecordCodec.encode(StoredSettings(ServerRecord(address), setOf(alpha), seq = 1))
            assertEquals(first, settings.loadSelection())
            val stall = MemoryRecordFile.Stall()
            file.stall = stall
            val writing = async(threads) { settings.saveSelection(second) }
            assertTrue(stall.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            // The writer holds the lock inside its write; the reads still answer, with the value before the write.
            withTimeout(WAIT_MS) {
                assertEquals(first, settings.loadSelection())
                assertEquals(setOf(alpha), settings.loadPlayableLibraries())
                assertEquals(setOf(alpha), settings.loadLibraries())
            }
            stall.release.countDown()
            assertTrue(writing.await())
            assertEquals(second, settings.loadSelection())
        }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val WAIT_MS = 2_000L
        const val PROBE_MS = 500L
    }
}
