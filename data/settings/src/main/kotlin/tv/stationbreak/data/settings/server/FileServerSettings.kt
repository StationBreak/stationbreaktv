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
import tv.stationbreak.core.model.ItemId
import java.io.File
import java.io.IOException

/**
 * The [ServerSettings] on one small file in `noBackupFilesDir`: the address and its pin as a single record, with the
 * library selection beside them, replaced atomically as a whole (spec 05, Units 3 and 4). It is out of backup on
 * purpose, so a restore onto another device asks about the certificate again instead of carrying a trust decision
 * over. No secret is kept here (the session is in the Keystore store). Faults come back as `false` or `null`, never as
 * an exception, and the store logs nothing.
 *
 * Cancellation: a caller cancelled while waiting for the lock does nothing, and the call throws
 * `CancellationException`. A caller cancelled after its write has begun still completes the write (the file work is
 * not interruptible), then sees `CancellationException` instead of the `Boolean`, so it can't tell whether the write
 * landed. A caller that must know, or must not leave a half-done sequence, runs the call in `NonCancellable`.
 */
@Suppress("TooManyFunctions") // The port's functions, one each, over the one locked file.
class FileServerSettings internal constructor(
    private val file: RecordFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerSettings {
    constructor(context: Context) : this(DiskRecordFile(File(context.noBackupFilesDir, DIR_NAME).resolve(FILE_NAME)))

    // One writer at a time, and a read never sees half of a read-modify-write.
    private val lock = Mutex()

    override suspend fun load(): ServerRecord? = locked { read()?.record }

    override suspend fun save(record: ServerRecord): Boolean =
        locked {
            // A choice of libraries belongs to the server it was made on: another address starts without one. A file
            // that can't be read has nothing worth keeping.
            val kept = read()?.takeIf { it.record.address == record.address }?.libraries
            write(StoredSettings(record, kept))
        }

    override suspend fun loadLibraries(): Set<ItemId>? = locked { read()?.libraries }

    override suspend fun saveLibraries(ids: Set<ItemId>): Boolean =
        locked {
            val held = read()
            if (held == null || ids.isEmpty()) false else write(held.copy(libraries = ids))
        }

    override suspend fun clearLibraries(): Boolean =
        locked {
            val text =
                try {
                    file.read()
                } catch (_: IOException) {
                    return@locked false
                }
            // No file: nothing to clear. A file that can't be read may still hold a selection and can't be rewritten
            // without the record, so that is a failure, not a success.
            if (text == null) true else dropSelection(ServerRecordCodec.decodeStored(text))
        }

    private fun dropSelection(held: StoredSettings?): Boolean =
        when {
            held == null -> false
            held.libraries == null -> true
            else -> write(held.copy(libraries = null))
        }

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
            if (text == null) true else clearIn(ServerRecordCodec.decodeStored(text))
        }

    private fun clearIn(held: StoredSettings?): Boolean =
        when {
            held == null -> false
            held.record.pin == null -> true
            else -> write(held.copy(record = held.record.copy(pin = null)))
        }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileServerSettings"

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun read(): StoredSettings? =
        try {
            file.read()?.let(ServerRecordCodec::decodeStored)
        } catch (_: IOException) {
            null
        }

    private fun write(stored: StoredSettings): Boolean =
        try {
            file.write(ServerRecordCodec.encode(stored))
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            // The codec refused a field it could not store safely: a failed write, like any other.
            false
        }

    private companion object {
        const val DIR_NAME = "settings"
        const val FILE_NAME = "server"
    }
}
