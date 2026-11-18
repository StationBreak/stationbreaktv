// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagedFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a write is staged, renamed, and the directories it made are synced`() {
        val file = File(tmp.root, "a/b/name")
        val synced = mutableListOf<File>()
        writeStaged(file, "new".toByteArray(), synced::add)
        assertEquals("new", file.readText())
        assertFalse(File(file.path + ".new").exists())
        assertTrue(file.parentFile in synced)
    }

    @Test
    fun `an orphan staged copy is not the file`() {
        val file = File(tmp.root, "name")
        File(file.path + ".new").writeText("torn")
        assertFalse(file.exists())
        writeStaged(file, "good".toByteArray()) {}
        assertEquals("good", file.readText())
        assertFalse(File(file.path + ".new").exists())
    }

    @Test
    fun `a failed rename drops the staged copy and keeps the old file`() {
        // A non-empty directory can't be replaced by a file, so the rename fails.
        val file = File(tmp.root, "name").apply { mkdir() }
        File(file, "child").writeText("x")
        val failed = runCatching { writeStaged(file, "new".toByteArray()) {} }
        assertTrue(failed.isFailure)
        assertFalse(File(file.path + ".new").exists())
        assertTrue(File(file, "child").exists())
    }

    @Test
    fun `readAtMost stops at max plus one and returns a file at or under the cap whole`() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(100) { it.toByte() }) }
        assertEquals(11, readAtMost(file, 10).size)
        assertArrayEquals(file.readBytes(), readAtMost(file, 100))
        assertEquals(100, readAtMost(file, 1000).size)
    }

    @Test
    fun `only one main source file stages and renames`() {
        val writers =
            File("src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "fd.sync()" in it.readText() && "renameTo" in it.readText() }
                .map { it.name }
                .toList()
        assertEquals(listOf("StagedFiles.kt"), writers)
    }
}
