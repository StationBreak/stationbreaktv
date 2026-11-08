// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.io.File
import java.util.UUID

/**
 * The record and the library selection on a real disk on the API 25 emulator: both survive a rebuilt store, and a
 * rewrite is an atomic replace of the one file. Since spec 26 Part 4 the replace is `.new`, sync, rename and a sync of
 * the directory through `android.system.Os` (not AtomicFile), and an older build's `.bak` is put back once.
 */
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

    @Test
    fun theLibrarySelectionSurvivesARebuiltStoreBesideTheRecord() =
        runBlocking {
            store().save(ServerRecord(address, pin))
            assertTrue(store().saveLibraries(setOf(ItemId("movies"), ItemId("shows"))))
            assertEquals(setOf(ItemId("movies"), ItemId("shows")), store().loadLibraries())
            assertEquals(ServerRecord(address, pin), store().load())
        }

    @Test
    fun aSelectionWriteIsAnAtomicReplaceThatLeavesOneFileAndTheRecordWhole() =
        runBlocking {
            store().save(ServerRecord(address, pin))
            store().saveLibraries(setOf(ItemId("movies")))
            store().saveLibraries(setOf(ItemId("shows")))
            assertEquals(setOf(ItemId("shows")), store().loadLibraries())
            assertEquals(ServerRecord(address, pin), store().load())
            assertEquals(listOf("server"), dir.list().orEmpty().toList())
        }

    @Test
    fun aTornFileHasNoSelectionAndNoRecordUntilTheyAreSavedAgain() =
        runBlocking {
            dir.mkdirs()
            File(dir, "server").writeText("version=1\nscheme=https\nhost=nas.example.test")
            assertNull(store().load())
            assertNull(store().loadLibraries())
            assertTrue(store().save(ServerRecord(address)))
            assertTrue(store().saveLibraries(setOf(ItemId("movies"))))
            assertEquals(setOf(ItemId("movies")), store().loadLibraries())
        }

    private val selection =
        StoredSelection(
            chosen = setOf(ItemId("movies")),
            seq = 6,
            adding = mapOf(ItemId("shows") to 6),
            gone = true,
            missing = mapOf(ItemId("shows") to Missing(sinceMs = 1_000, sincePass = 2)),
            listings = 2,
            resetSeq = 3,
        )

    @Test
    fun theWholeStoredSelectionSurvivesARebuiltStore() =
        runBlocking {
            store().save(ServerRecord(address, pin))
            assertTrue(store().saveSelection(selection))
            assertEquals(selection, store().loadSelection())
            assertNull(store().loadPlayableLibraries())
            assertEquals(ServerRecord(address, pin), store().load())
            assertEquals(listOf("server"), dir.list().orEmpty().toList())
        }

    @Test
    fun aClearKeepsTheSeqOnDisk() =
        runBlocking {
            store().save(ServerRecord(address))
            store().saveSelection(selection)
            assertTrue(store().clearLibraries())
            assertNull(store().loadSelection())
            assertEquals(6L, store().loadSelectionSeq())
            assertTrue(store().saveLibraries(setOf(ItemId("shows"))))
            assertEquals(7L, store().loadSelection()?.seq)
        }

    @Test
    fun manyWritesLeaveOneFileAndNoStagedOrBackupCopy() =
        runBlocking {
            store().save(ServerRecord(address))
            val settings = store()
            for (seq in 1L..20L) assertTrue(settings.saveSelection(StoredSelection(setOf(ItemId("movies")), seq)))
            assertEquals(20L, store().loadSelection()?.seq)
            assertEquals(listOf("server"), dir.list().orEmpty().toList())
        }

    @Test
    fun anOlderBuildsBackupIsPutBackOverATornRecord() =
        runBlocking {
            store().save(ServerRecord(address, pin))
            // An AtomicFile write cut off: the good copy is in server.bak, server is torn.
            File(dir, "server").renameTo(File(dir, "server.bak"))
            File(dir, "server").writeText("version=1\nscheme=ht")
            assertEquals(ServerRecord(address, pin), store().load())
            assertFalse(File(dir, "server.bak").exists())
            assertTrue(store().save(ServerRecord(address)))
            assertEquals(ServerRecord(address), store().load())
        }

    @Test
    fun aStagedCopyLeftByAnInterruptedWriteIsNeverReadAndIsRemoved() =
        runBlocking {
            store().save(ServerRecord(address))
            File(dir, "server.new").writeText("version=1\nscheme=https\nhost=other.example.test\nport=443")
            assertEquals(ServerRecord(address), store().load())
            assertFalse(File(dir, "server.new").exists())
        }

    @Test
    fun anotherServerStartsWithoutTheOldSelectionOnDisk() =
        runBlocking {
            store().save(ServerRecord(address))
            store().saveLibraries(setOf(ItemId("movies")))
            store().save(ServerRecord(ServerAddress(Scheme.Https, "other.example.test", 443)))
            assertNull(store().loadLibraries())
        }
}
