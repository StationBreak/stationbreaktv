// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lastchannel

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.data.settings.server.DiskRecordFile
import tv.stationbreak.data.settings.server.RecordFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The [LastChannelStore] on one small file per account in `noBackupFilesDir/lastchannel`, holding only the channel
 * ID, replaced atomically (spec 14). The file is named by the SHA-256 of `server/user`, so no server or user ID
 * appears in a path. Sign-out leaves the file: it holds a channel ID and nothing else. A value that is blank or
 * implausibly long reads as absent. Faults come back as `null` or `false`, never as an exception, and the store logs
 * nothing.
 *
 * Cancellation behaves as for `FileServerSettings`: a caller cancelled after its file work has begun still completes
 * it, then sees `CancellationException` instead of the result.
 */
class FileLastChannelStore internal constructor(
    private val open: (name: String) -> RecordFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LastChannelStore {
    constructor(context: Context) : this(
        { name -> DiskRecordFile(File(context.noBackupFilesDir, DIR_NAME).resolve(name)) },
    )

    // One writer at a time, and a read never sees a file mid-rename.
    private val lock = Mutex()

    override suspend fun load(account: LibraryAccount): ChannelId? =
        locked {
            try {
                open(fileName(account)).read()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
            } catch (_: IOException) {
                null
            }?.let(::ChannelId)
        }

    override suspend fun save(
        account: LibraryAccount,
        channel: ChannelId,
    ): Boolean =
        locked {
            try {
                open(fileName(account)).write(channel.value)
                true
            } catch (_: IOException) {
                false
            }
        }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileLastChannelStore"

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun fileName(account: LibraryAccount): String {
        val key = "${account.server.value}/${account.user.value}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { HEX[it.toInt() and BYTE_MASK] }
    }

    private companion object {
        const val DIR_NAME = "lastchannel"
        const val MAX_LENGTH = 256
        const val BYTE_MASK = 0xff
        val HEX = Array(256) { (if (it < 16) "0" else "") + it.toString(16) }
    }
}
