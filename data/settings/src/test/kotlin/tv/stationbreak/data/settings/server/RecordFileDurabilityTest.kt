// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * The record file on a real temporary directory (spec 26 Part 4, §3): a write goes to `server.new`, is synced, renamed
 * over `server`, and the directory is synced, as the lineup files do. `android.util.AtomicFile`'s `.bak` scheme is not
 * used, but a `.bak` an older build left is still honoured once.
 */
class RecordFileDurabilityTest {
    private val dir: File = Files.createTempDirectory("record").toFile()
    private val settingsDir = File(dir, "settings")
    private val base = File(settingsDir, "server")
    private val staged = File(settingsDir, "server.new")
    private val backup = File(settingsDir, "server.bak")
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)

    // Directories the file asked to be synced, with what each held at that moment (android.system.Os is not on the
    // JVM).
    private val synced = mutableListOf<Pair<File, List<String>>>()
    private val disk = DiskRecordFile(base) { synced += it to it.list().orEmpty().sorted() }

    @After
    fun removeDirectory() {
        dir.deleteRecursively()
    }

    @Test
    fun aWrite_leavesOnlyTheFile_andSyncsTheDirectoryAfterTheRename() {
        disk.write("one")
        disk.write("two")
        assertEquals("two", disk.read())
        assertEquals(listOf("server"), settingsDir.list()!!.toList())
        val (last, held) = synced.last()
        assertEquals(settingsDir, last)
        // At the last sync the rename had happened: the final name is there and the staged copy is not.
        assertEquals(listOf("server"), held)
    }

    @Test
    fun creatingTheDirectory_syncsItsParent() {
        disk.write("one")
        assertTrue(synced.toString(), synced.any { it.first == dir })
    }

    @Test
    fun noFile_readsAsNull() {
        assertNull(disk.read())
    }

    @Test
    fun anOrphanNewFile_aloneIsNoRecord_andIsDeletedOnRead() {
        settingsDir.mkdirs()
        staged.writeText("half a record")
        assertNull(disk.read())
        assertFalse(staged.exists())
    }

    @Test
    fun aLeftoverNewFile_besideTheRecord_isIgnoredAndDeletedOnRead() {
        disk.write("good")
        staged.writeText("half")
        assertEquals("good", disk.read())
        assertFalse(staged.exists())
    }

    @Test
    fun aLegacyBackup_winsOverATornRecord_asAtomicFileWouldHaveRead() {
        // An older build's AtomicFile was cut off mid-write: the backup is the last good copy.
        settingsDir.mkdirs()
        backup.writeText("good")
        base.writeText("to")
        assertEquals("good", disk.read())
        assertFalse(backup.exists())
        assertEquals("good", disk.read())
    }

    @Test
    fun aLegacyBackup_withNoRecord_isRestored() {
        settingsDir.mkdirs()
        backup.writeText("good")
        assertEquals("good", disk.read())
        assertEquals(listOf("server"), settingsDir.list()!!.toList())
    }

    @Test
    fun aWrite_withALegacyBackupLeft_neverLetsTheBackupComeBackLater() {
        settingsDir.mkdirs()
        backup.writeText("old")
        disk.write("new")
        assertFalse(backup.exists())
        assertEquals("new", disk.read())
        assertEquals("new", DiskRecordFile(base) {}.read())
    }

    @Test
    fun aWriteThatCannotReplace_throws_andLeavesNoStagedCopy() {
        // A non-empty directory where the record should be: the rename can't replace it.
        File(base, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        assertThrows(IOException::class.java) { disk.write("one") }
        assertFalse(staged.exists())
    }

    @Test
    fun aSyncFaultAfterTheRename_failsTheSave_butTheMemoryShowsTheNewText() =
        runBlocking {
            var armed = false
            val faulty = DiskRecordFile(base) { if (armed) throw IOException("sync refused") }
            val settings = FileServerSettings(faulty, inPlace)
            assertTrue(settings.save(ServerRecord(address)))
            assertTrue(settings.saveSelection(StoredSelection(setOf(ItemId("alpha")), seq = 1)))
            armed = true
            // The rename landed, then the follow-up threw: the caller hears "failed", the memory follows the disk.
            assertFalse(settings.saveSelection(StoredSelection(setOf(ItemId("beta")), seq = 2)))
            armed = false
            assertEquals(StoredSelection(setOf(ItemId("beta")), seq = 2), settings.loadSelection())
            assertEquals(2L, FileServerSettings(DiskRecordFile(base) {}, inPlace).loadSelectionSeq())
        }

    @Test
    fun aFailedBackupRestore_isAReadFault() {
        // A .bak that can't be renamed back (a non-empty directory sits where the record goes).
        File(base, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        backup.writeText("good")
        assertThrows(IOException::class.java) { disk.read() }
    }

    @Test
    fun aFailedBackupRestore_failsASave_andKeepsTheBackup() =
        runBlocking {
            File(base, "child").apply { parentFile!!.mkdirs() }.writeText("x")
            backup.writeText(ServerRecordCodec.encode(StoredSettings(ServerRecord(address), setOf(ItemId("alpha")), 5)))
            val settings = FileServerSettings(disk, inPlace)
            assertFalse(settings.save(ServerRecord(address)))
            assertTrue(backup.exists())
        }

    @Test
    fun aRecordThatIsADirectory_isAFault_notNoRecord() {
        base.mkdirs()
        assertThrows(IOException::class.java) { disk.read() }
    }
}
