// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerSettings
import java.io.File
import java.io.IOException

/**
 * The [ServerSettings] on one small file in `noBackupFilesDir`: the address and its pin as a single record, replaced
 * atomically (spec 05, Unit 3). It is out of backup on purpose, so a restore onto another device asks about the
 * certificate again instead of carrying a trust decision over. No secret is kept here (the session is in the Keystore
 * store). Faults come back as `false` or `null`, never as an exception, and the store logs nothing.
 */
class FileServerSettings internal constructor(
    private val file: RecordFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerSettings {
    constructor(context: Context) : this(DiskRecordFile(File(context.noBackupFilesDir, DIR_NAME).resolve(FILE_NAME)))

    // One writer at a time, and a read never sees half of a read-modify-write.
    private val lock = Mutex()

    override suspend fun load(): ServerRecord? = locked { read() }

    override suspend fun save(record: ServerRecord): Boolean = locked { write(record) }

    override suspend fun clearPin(): Boolean =
        locked {
            val text =
                try {
                    file.read()
                } catch (_: IOException) {
                    return@locked false
                }
            // No record: nothing to clear. A record that can't be read may still hold a pin and can't be rewritten
            // without it, so that is a failure, not a success.
            if (text == null) true else clearIn(ServerRecordCodec.decode(text))
        }

    private fun clearIn(record: ServerRecord?): Boolean =
        when {
            record == null -> false
            record.pin == null -> true
            else -> write(record.copy(pin = null))
        }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileServerSettings"

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun read(): ServerRecord? =
        try {
            file.read()?.let(ServerRecordCodec::decode)
        } catch (_: IOException) {
            null
        }

    private fun write(record: ServerRecord): Boolean =
        try {
            file.write(ServerRecordCodec.encode(record))
            true
        } catch (_: IOException) {
            false
        }

    private companion object {
        const val DIR_NAME = "settings"
        const val FILE_NAME = "server"
    }
}
