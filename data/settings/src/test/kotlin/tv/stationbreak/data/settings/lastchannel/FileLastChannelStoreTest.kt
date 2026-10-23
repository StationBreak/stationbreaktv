// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lastchannel

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.data.settings.server.RecordFile
import java.io.IOException
import java.util.concurrent.Executor

/** The last channel per account behind the [tv.stationbreak.core.domain.LastChannelStore] port, files faked. */
class FileLastChannelStoreTest {
    private class MemoryFile : RecordFile {
        var text: String? = null
        var failWrites = false
        var failReads = false

        override fun read(): String? {
            if (failReads) throw IOException("unreadable")
            return text
        }

        override fun write(text: String) {
            if (failWrites) throw IOException("disk full")
            this.text = text
        }
    }

    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val files = mutableMapOf<String, MemoryFile>()
    private val store = FileLastChannelStore({ name -> files.getOrPut(name) { MemoryFile() } }, inPlace)
    private val account = LibraryAccount(ServerId("server-a"), UserId("user-a"))
    private val channel = ChannelId("channel-1")

    @Test
    fun savedChannelIsLoadedForTheSameAccount() =
        runBlocking {
            assertTrue(store.save(account, channel))
            assertEquals(channel, store.load(account))
        }

    @Test
    fun nothingSavedLoadsAsNull() = runBlocking { assertNull(store.load(account)) }

    @Test
    fun anotherAccountHasNoSavedChannel() =
        runBlocking {
            store.save(account, channel)
            assertNull(store.load(LibraryAccount(ServerId("server-a"), UserId("user-b"))))
            assertNull(store.load(LibraryAccount(ServerId("server-b"), UserId("user-a"))))
        }

    @Test
    fun aCorruptFileLoadsAsNull() =
        runBlocking {
            store.save(account, channel)
            val file = files.values.single()
            file.text = "   "
            assertNull(store.load(account))
            file.text = "x".repeat(10_000)
            assertNull(store.load(account))
            file.text = channel.value
            file.failReads = true
            assertNull(store.load(account))
        }

    @Test
    fun saveOverwrites() =
        runBlocking {
            store.save(account, channel)
            store.save(account, ChannelId("channel-2"))
            assertEquals(ChannelId("channel-2"), store.load(account))
        }

    @Test
    fun aWriteFaultIsFalseAndKeepsTheOldChannel() =
        runBlocking {
            store.save(account, channel)
            files.values.single().failWrites = true
            assertFalse(store.save(account, ChannelId("channel-2")))
            assertEquals(channel, store.load(account))
        }

    @Test
    fun fileNamesHoldNoRawIds() =
        runBlocking {
            store.save(account, channel)
            val name = files.keys.single()
            assertTrue(Regex("[0-9a-f]{64}").containsMatchIn(name))
            assertFalse(name.contains("server-a") || name.contains("user-a"))
        }
}
