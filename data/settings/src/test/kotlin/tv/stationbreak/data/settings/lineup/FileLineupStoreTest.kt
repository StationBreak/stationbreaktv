// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import java.io.IOException
import java.util.concurrent.Executor

/** The stored lineup behind the [tv.stationbreak.core.domain.LineupStore] port, with the files faked. */
class FileLineupStoreTest {
    private class MemoryFiles : LineupFiles {
        val held = linkedMapOf<String, ByteArray>()
        val touched = mutableListOf<String>()
        var failWrites = false
        var failReads = false
        var failMoves = false
        var failDeletes = false

        override fun read(name: String): ByteArray? {
            touched += name
            if (failReads) throw IOException("unreadable")
            return held[name]
        }

        override fun write(
            name: String,
            bytes: ByteArray,
        ) {
            touched += name
            // A failed write must leave the old bytes, as an atomic write does.
            if (failWrites) throw IOException("disk full")
            held[name] = bytes
        }

        override fun move(
            from: String,
            to: String,
        ): Boolean {
            touched += from
            if (failMoves) throw IOException("cannot rename")
            val bytes = held.remove(from) ?: return false
            held[to] = bytes
            return true
        }

        override fun delete(name: String) {
            touched += name
            if (failDeletes) throw IOException("cannot delete")
            held.remove(name)
        }
    }

    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val files = MemoryFiles()
    private val store = FileLineupStore(files, inPlace)

    private val serverA = "a".repeat(ID_LENGTH)
    private val serverB = "b".repeat(ID_LENGTH)
    private val userOne = "1".repeat(ID_LENGTH)
    private val userTwo = "2".repeat(ID_LENGTH)
    private val one = LibraryAccount(ServerId(serverA), UserId(userOne))
    private val two = LibraryAccount(ServerId(serverA), UserId(userTwo))
    private val otherServer = LibraryAccount(ServerId(serverB), UserId(userOne))
    private val bytes = byteArrayOf(1, 2, 3)

    private fun StoredBytes.bytesOrNull(): ByteArray? = (this as? StoredBytes.Present)?.bytes

    @Test
    fun nothingStoredLoadsAsAbsent() = runBlocking { assertEquals(StoredBytes.Absent, store.load(one)) }

    @Test
    fun aSavedLineupLoadsBackThroughANewStoreOnTheSameFiles() =
        runBlocking {
            assertTrue(store.save(one, bytes))
            val rebuilt = FileLineupStore(files, inPlace)
            assertArrayEquals(bytes, rebuilt.load(one).bytesOrNull())
        }

    @Test
    fun theFileIsNamedByTheCanonicalServerAndUser() =
        runBlocking {
            store.save(one, bytes)
            assertEquals(listOf("$serverA/$userOne.json"), files.held.keys.toList())
        }

    @Test
    fun accountsDoNotShareALineup() =
        runBlocking {
            store.save(one, bytes)
            assertEquals(StoredBytes.Absent, store.load(two))
            assertEquals(StoredBytes.Absent, store.load(otherServer))
        }

    @Test
    fun switchingAccountsKeepsOtherLineups() =
        runBlocking {
            store.save(one, bytes)
            store.save(two, byteArrayOf(9))
            store.load(two)
            store.load(one)
            assertArrayEquals(bytes, store.load(one).bytesOrNull())
            assertArrayEquals(byteArrayOf(9), store.load(two).bytesOrNull())
        }

    @Test
    fun forgetDeletesOnlyThatAccount() =
        runBlocking {
            store.save(one, bytes)
            store.save(two, byteArrayOf(9))
            store.save(otherServer, byteArrayOf(7))
            assertTrue(store.forget(one))
            assertEquals(StoredBytes.Absent, store.load(one))
            assertArrayEquals(byteArrayOf(9), store.load(two).bytesOrNull())
            assertArrayEquals(byteArrayOf(7), store.load(otherServer).bytesOrNull())
        }

    @Test
    fun forgetWithNothingStoredSucceeds() = runBlocking { assertTrue(store.forget(one)) }

    @Test
    fun forgetReportsAFailure() =
        runBlocking {
            store.save(one, bytes)
            files.failDeletes = true
            assertFalse(store.forget(one))
        }

    @Test
    fun setAsideMovesTheFileToUnreadable() =
        runBlocking {
            store.save(one, bytes)
            assertTrue(store.setAside(one))
            assertEquals(StoredBytes.Absent, store.load(one))
            assertArrayEquals(bytes, files.held["$serverA/$userOne.json.unreadable"])
        }

    @Test
    fun setAsideKeepsOneCopy() =
        runBlocking {
            store.save(one, bytes)
            store.setAside(one)
            store.save(one, byteArrayOf(5))
            assertTrue(store.setAside(one))
            assertEquals(listOf("$serverA/$userOne.json.unreadable"), files.held.keys.toList())
            assertArrayEquals(byteArrayOf(5), files.held["$serverA/$userOne.json.unreadable"])
        }

    @Test
    fun setAsideWithNothingStoredSucceeds() = runBlocking { assertTrue(store.setAside(one)) }

    @Test
    fun setAsideReportsAFailure() =
        runBlocking {
            store.save(one, bytes)
            files.failMoves = true
            assertFalse(store.setAside(one))
            assertArrayEquals(bytes, store.load(one).bytesOrNull())
        }

    @Test
    fun saveIsAtomic() =
        runBlocking {
            store.save(one, bytes)
            files.failWrites = true
            assertFalse(store.save(one, byteArrayOf(8, 8)))
            assertArrayEquals(bytes, store.load(one).bytesOrNull())
        }

    @Test
    fun aNonCanonicalIdTouchesNoFile() =
        runBlocking {
            val bad =
                listOf(
                    "../x",
                    serverA.uppercase(),
                    "${serverA.take(8)}-${serverA.drop(8)}",
                    "{$serverA}",
                    serverA.drop(1),
                    "$serverA/..",
                )
            for (id in bad) {
                val accounts =
                    listOf(
                        LibraryAccount(ServerId(id), UserId(userOne)),
                        LibraryAccount(ServerId(serverA), UserId(id)),
                    )
                for (account in accounts) {
                    assertEquals(StoredBytes.Absent, store.load(account))
                    assertFalse(store.save(account, bytes))
                    assertFalse(store.setAside(account))
                    assertFalse(store.forget(account))
                }
            }
            assertTrue(files.touched.isEmpty())
            assertTrue(files.held.isEmpty())
            assertNull(files.held["x"])
        }

    @Test
    fun readFaultLoadsAsFailedNotAbsent() =
        runBlocking {
            store.save(one, bytes)
            files.failReads = true
            assertEquals(StoredBytes.Failed, store.load(one))
            files.failReads = false
            assertArrayEquals(bytes, store.load(one).bytesOrNull())
        }

    @Test
    fun missingFileLoadsAsAbsent() = runBlocking { assertEquals(StoredBytes.Absent, store.load(one)) }

    @Test
    fun toStringNamesNoContents() {
        assertEquals("FileLineupStore", store.toString())
        assertEquals("StoredBytes.Present", StoredBytes.Present(bytes).toString())
    }

    private companion object {
        const val ID_LENGTH = 32
    }
}
