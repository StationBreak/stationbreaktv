// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** The built days on disk behind the [tv.stationbreak.core.domain.DayStore] port (#480). */
class FileDayStoreTest {
    private val root: File = Files.createTempDirectory("days").toFile()
    private val synced = mutableListOf<File>()
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val store = FileDayStore(root, inPlace, maxBytes = MAX) { synced += it }

    private val server = "a".repeat(ID_LENGTH)
    private val userOne = "1".repeat(ID_LENGTH)
    private val one = LibraryAccount(ServerId(server), UserId(userOne))
    private val two = LibraryAccount(ServerId(server), UserId("2".repeat(ID_LENGTH)))
    private val directory = root.resolve("$server/$userOne")
    private val bytes = byteArrayOf(1, 2, 3)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun stored(
        account: LibraryAccount = one,
        name: String = NAME,
    ): StoredBytes = runBlocking { store.read(account, name) }

    private fun read(
        account: LibraryAccount = one,
        name: String = NAME,
    ): ByteArray? = (stored(account, name) as? StoredBytes.Present)?.bytes

    private fun write(
        account: LibraryAccount = one,
        name: String = NAME,
        data: ByteArray = bytes,
    ): Boolean = runBlocking { store.write(account, name, data) }

    @Test
    fun noFileReadsAsAbsent() {
        assertEquals(StoredBytes.Absent, stored())
    }

    @Test
    fun aWrittenFileReadsBackUnderTheAccountsOwnDirectory() {
        assertTrue(write())
        assertArrayEquals(bytes, read())
        assertEquals(StoredBytes.Absent, stored(two))
        assertEquals(listOf(NAME), directory.list()!!.toList())
        assertEquals("the directory was synced after the rename", directory, synced.last())
    }

    @Test
    fun aRewriteReplacesTheFileAndLeavesNoStagedCopy() {
        write()
        write(data = byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), read())
        assertEquals(listOf(NAME), directory.list()!!.toList())
    }

    @Test
    fun aWriteThatFailsLeavesTheOldFile() {
        write()
        // A directory where the staged copy goes: the write fails before any rename.
        directory.resolve("$NAME.new/blocker").mkdirs()
        assertFalse(write(data = byteArrayOf(9, 9)))
        assertArrayEquals(bytes, read())
        assertEquals(listOf(NAME), runBlocking { store.names(one) })
    }

    @Test
    fun aWriteOverTheCapIsRefusedAndAFileOverItFailsToRead() {
        write()
        assertFalse(write(data = ByteArray(MAX + 1)))
        assertArrayEquals("the old file stays", bytes, read())

        // A file grown past the cap (written by something else) is a fault, not data.
        directory.resolve(NAME).writeBytes(ByteArray(MAX + 1))
        assertEquals(StoredBytes.Failed, stored())
        directory.resolve(NAME).writeBytes(ByteArray(MAX))
        assertEquals(MAX, read()?.size)
    }

    @Test
    fun aDirectoryInPlaceOfTheFileIsAFault() {
        directory.resolve(NAME).mkdirs()
        assertEquals(StoredBytes.Failed, stored())
    }

    @Test
    fun namesListOnlyTheAccountsFilesAndDeleteRemovesOne() {
        write(name = "1_2")
        write(name = "3")
        write(two, name = "4")
        // An orphan staged copy of an interrupted write is not a file.
        directory.resolve("5.new").writeBytes(bytes)
        assertEquals(listOf("1_2", "3"), runBlocking { store.names(one) }.sorted())
        assertTrue(runBlocking { store.delete(one, "3") })
        assertEquals(listOf("1_2"), runBlocking { store.names(one) })
        assertEquals(listOf("4"), runBlocking { store.names(two) })
    }

    @Test
    fun aNameOrAnAccountThatCouldLeaveTheDirectoryIsRefused() {
        for (name in listOf("../x", "a/b", "", ".", "x.new", "1 2")) {
            assertFalse(name, write(name = name))
            assertEquals(name, StoredBytes.Absent, stored(name = name))
        }
        val odd = LibraryAccount(ServerId("../$server"), UserId(userOne))
        assertFalse(write(odd))
        assertEquals(StoredBytes.Absent, stored(odd))
        assertEquals(emptyList<String>(), runBlocking { store.names(odd) })
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    private companion object {
        const val ID_LENGTH = 32
        const val NAME = "1760850000000"
        const val MAX = 1024
    }
}
