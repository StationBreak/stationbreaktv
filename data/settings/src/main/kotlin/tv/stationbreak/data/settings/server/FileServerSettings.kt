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
import tv.stationbreak.core.domain.StoredSelection
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

    /**
     * What the file holds, decoded, once it has been read (spec 26 Part 4, §3): null until then. The first read fills
     * it under [lock]; every writer sets it inside the same block as its write, right after the write. A read after
     * that never takes the lock or touches the disk, because selection reads are on the tune path. A read fault is
     * not remembered: before the first good read the next read tries again, and after a failed write whose re-read
     * also fails the last good value is kept. There is one store per file in the app (a singleton), so nothing
     * else writes the file behind it.
     */
    @Volatile private var memo: Snapshot? = null

    /** The decoded file: [stored] is null when there is no file or its text is not a record. */
    private class Snapshot(
        val stored: StoredSettings?,
    )

    override suspend fun load(): ServerRecord? = snapshot()?.record

    override suspend fun save(record: ServerRecord): Boolean =
        locked {
            // A choice of libraries belongs to the server it was made on: another address starts without one, but the
            // seq and the counters go on, so a seq is never reused. Only a file that is absent or is not a record has
            // nothing worth keeping; a read fault (a failed backup restore too) may hide a seq, so it fails the save.
            val text =
                try {
                    file.read()
                } catch (_: IOException) {
                    return@locked false
                }
            val held = text?.let(ServerRecordCodec::decodeStored)
            val kept =
                when {
                    held == null -> StoredSettings(record)
                    held.record.address == record.address -> held.copy(record = record)
                    else -> held.withoutSelection().copy(record = record)
                }
            write(kept)
        }

    override suspend fun loadLibraries(): Set<ItemId>? = snapshot()?.libraries

    override suspend fun saveLibraries(ids: Set<ItemId>): Boolean =
        locked {
            val held = read()
            if (held == null || ids.isEmpty()) {
                false
            } else {
                // A setup: the next seq, which is also the reset seq; nothing carries over but the listing count.
                val seq = held.seq + 1
                write(held.withoutSelection().copy(libraries = ids, seq = seq, resetSeq = seq))
            }
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
            else -> write(held.withoutSelection())
        }

    override suspend fun loadSelection(): StoredSelection? = snapshot()?.selection()

    override suspend fun loadSelectionSeq(): Long? = snapshot()?.seq

    override suspend fun saveSelection(selection: StoredSelection): Boolean =
        locked {
            val held = read()
            if (held == null || !selection.mayReplace(held.selection(), held.seq, held.resetSeq)) {
                false
            } else {
                write(
                    held.copy(
                        libraries = selection.chosen,
                        seq = selection.seq,
                        adding = selection.adding,
                        gone = selection.gone,
                        missing = selection.missing,
                        listings = selection.listings,
                        resetSeq = selection.resetSeq,
                    ),
                )
            }
        }

    override suspend fun loadPlayableLibraries(): Set<ItemId>? = snapshot()?.takeUnless { it.gone }?.libraries

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

    // The memo when it is filled: no lock, no disk. Otherwise one locked read fills it (or finds a writer just did).
    private suspend fun snapshot(): StoredSettings? {
        memo?.let { return it.stored }
        return locked { memo.let { if (it != null) it.stored else read() } }
    }

    // Reads the file and remembers what it decoded to. A read fault is answered with null and not remembered.
    private fun read(): StoredSettings? = readSnapshot()?.also { memo = it }?.stored

    // The decoded file, or null for a read fault. Touches no memo.
    private fun readSnapshot(): Snapshot? =
        try {
            Snapshot(file.read()?.let(ServerRecordCodec::decodeStored))
        } catch (_: IOException) {
            null
        }

    private fun write(stored: StoredSettings): Boolean {
        val written =
            try {
                file.write(ServerRecordCodec.encode(stored))
                true
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                // The codec refused a field it could not store safely: a failed write, like any other.
                false
            }
        if (written) {
            memo = Snapshot(stored)
        } else {
            // A failed write may still have landed, or not: the memory follows the disk, read again at once. The old
            // memo stays up during the re-read, so lock-free readers never queue behind it; it is replaced by what the
            // disk holds. When the re-read fails too, the last good value stays (the tune path keeps reading memory,
            // never the failing disk); the next successful write, or a writer's own successful read, replaces it.
            memo = readSnapshot() ?: memo
        }
        return written
    }

    private fun StoredSettings.selection(): StoredSelection? =
        libraries?.let { StoredSelection(it, seq, adding, gone, missing, listings, resetSeq) }

    // The selection goes; the seq, the reset seq and the listing count stay, so a seq is never taken twice.
    private fun StoredSettings.withoutSelection(): StoredSettings =
        copy(libraries = null, adding = emptyMap(), gone = false, missing = emptyMap())

    private companion object {
        const val DIR_NAME = "settings"
        const val FILE_NAME = "server"
    }
}
