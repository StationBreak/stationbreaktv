// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The blob files on a real temporary directory (#818): a write goes to `<name>.new`, is synced, renamed over `<name>`,
 * and the directory is synced, as `DiskRecordFile` does. `android.util.AtomicFile`'s `.bak` scheme is not used, because
 * on API 25 a failed write can leave the only copy truncated, but a `.bak` an older build left is still honoured once.
 */
class DiskBlobFilesDurabilityTest {
    private val root: File = Files.createTempDirectory("blobs").toFile()
    private val blobDir = File(root, "blobs")
    private val blob = File(blobDir, "session")
    private val staged = File(blobDir, "session.new")
    private val backup = File(blobDir, "session.bak")

    // Directories the files asked to be synced, with what each held at that moment (android.system.Os is not on the
    // JVM).
    private val synced = mutableListOf<Pair<File, List<String>>>()
    private val disk =
        DiskBlobFiles({ dir: File -> synced += dir to dir.list().orEmpty().sorted() }) { blobDir }

    @After
    fun removeDirectory() {
        root.deleteRecursively()
    }

    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun noBlob_readsAsNull() {
        assertNull(disk.read("session"))
    }

    @Test
    fun aWrite_leavesOnlyTheBlob_andSyncsTheDirectoryAfterTheRename() {
        disk.write("session", bytes("one"))
        disk.write("session", bytes("two"))
        assertArrayEquals(bytes("two"), disk.read("session"))
        assertEquals(listOf("session"), blobDir.list()!!.toList())
        val (last, held) = synced.last()
        assertEquals(blobDir, last)
        // At the last sync the rename had happened: the final name is there and the staged copy is not.
        assertEquals(listOf("session"), held)
    }

    @Test
    fun creatingTheDirectory_syncsItsParent() {
        disk.write("session", bytes("one"))
        assertTrue(synced.toString(), synced.any { it.first == root })
    }

    @Test
    fun aWriteThatFails_leavesThePreviousBlobReadable_andNoStagedCopy() {
        disk.write("session", bytes("good"))
        // A directory where the staged copy goes: the write fails before the blob is touched.
        staged.mkdirs()
        assertThrows(IOException::class.java) { disk.write("session", bytes("new")) }
        staged.deleteRecursively()
        assertArrayEquals(bytes("good"), disk.read("session"))
        assertEquals(listOf("session"), blobDir.list()!!.toList())
    }

    @Test
    fun aWriteThatCannotReplace_throws_andLeavesNoStagedCopy() {
        // A non-empty directory where the blob should be: the rename can't replace it.
        File(blob, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        assertThrows(IOException::class.java) { disk.write("session", bytes("one")) }
        assertFalse(staged.exists())
    }

    @Test
    fun anOrphanNewFile_aloneIsNoBlob_andIsDeletedOnRead() {
        blobDir.mkdirs()
        staged.writeText("half a blob")
        assertNull(disk.read("session"))
        assertFalse(staged.exists())
    }

    @Test
    fun aLeftoverNewFile_besideTheBlob_isIgnoredAndDeletedOnRead() {
        disk.write("session", bytes("good"))
        staged.writeText("half")
        assertArrayEquals(bytes("good"), disk.read("session"))
        assertFalse(staged.exists())
    }

    @Test
    fun aBlobThatIsADirectory_isAFault_notNoBlob() {
        blob.mkdirs()
        assertThrows(IOException::class.java) { disk.read("session") }
    }

    @Test
    fun aLegacyBackup_winsOverATornBlob_asAtomicFileWouldHaveRead() {
        // An older build's AtomicFile was cut off mid-write: the backup is the last good copy.
        blobDir.mkdirs()
        backup.writeText("good")
        blob.writeText("to")
        assertArrayEquals(bytes("good"), disk.read("session"))
        assertFalse(backup.exists())
        assertArrayEquals(bytes("good"), disk.read("session"))
    }

    @Test
    fun aLegacyBackup_withNoBlob_isRestored() {
        blobDir.mkdirs()
        backup.writeText("good")
        assertArrayEquals(bytes("good"), disk.read("session"))
        assertEquals(listOf("session"), blobDir.list()!!.toList())
    }

    @Test
    fun aWrite_withALegacyBackupLeft_neverLetsTheBackupComeBackLater() {
        blobDir.mkdirs()
        backup.writeText("old")
        disk.write("session", bytes("new"))
        assertFalse(backup.exists())
        assertArrayEquals(bytes("new"), DiskBlobFiles({ _: File -> }) { blobDir }.read("session"))
    }

    @Test
    fun aFailedBackupRestore_isAReadFault_andKeepsTheBackup() {
        File(blob, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        backup.writeText("good")
        assertThrows(IOException::class.java) { disk.read("session") }
        assertTrue(backup.exists())
    }

    @Test
    fun aDelete_removesTheBlobAndAnyStagedOrBackupCopy() {
        disk.write("session", bytes("one"))
        staged.writeText("half")
        backup.writeText("old")
        disk.delete("session")
        assertEquals(emptyList<String>(), blobDir.list()!!.toList())
        assertNull(disk.read("session"))
    }

    @Test
    fun aDeleteThatCannotRemoveTheBackup_neverLetsTheBackupBecomeTheBlobAgain() {
        disk.write("session", bytes("one"))
        // A non-empty directory where the backup is: it can't be removed, and the blob must stay in place.
        File(backup, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        assertThrows(IOException::class.java) { disk.delete("session") }
        // The blob was not removed ahead of its backup, so no backup is left to be put back over a missing blob.
        assertEquals("one", blob.readText())
        assertTrue(backup.exists())
    }

    @Test
    fun aDelete_syncsTheDirectoryAfterTheRemoval() {
        disk.write("session", bytes("one"))
        synced.clear()
        disk.delete("session")
        assertEquals(listOf(blobDir to emptyList<String>()), synced)
    }

    @Test
    fun aDelete_ofNoBlob_isNotAnError() {
        disk.delete("session")
    }

    @Test
    fun deleteAll_removesEveryBlobAndLeftover() {
        disk.write("session", bytes("one"))
        disk.write("other", bytes("two"))
        staged.writeText("half")
        backup.writeText("old")
        synced.clear()
        disk.deleteAll()
        assertEquals(emptyList<String>(), blobDir.list()!!.toList())
        assertEquals(listOf(blobDir to emptyList<String>()), synced)
    }
}
