// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import tv.stationbreak.data.settings.lineup.syncDirectoryWithOs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The blob files, one per [BlobCodec.fileName], behind a seam so tests can inject I/O faults. */
internal interface BlobFiles {
    /** The blob's bytes, or null when there is no such file. */
    fun read(name: String): ByteArray?

    /** Atomic: a power cut or a failed write leaves the old blob or the new one, never a torn one. */
    fun write(
        name: String,
        bytes: ByteArray,
    )

    fun delete(name: String)

    /** Every blob, and any temporary or backup file an interrupted write left. */
    fun deleteAll()
}

/**
 * The blobs on disk, written as the lineup files are (`DiskLineupFiles`): the bytes go to `<name>.new`, which is
 * synced, renamed over `<name>`, and then the directory is synced, so a power cut or a failed write leaves the old blob
 * or the new one. `android.util.AtomicFile` is not used: on API 25 its `.bak` scheme can leave the only copy truncated
 * when a write fails, and these are the sign-in secrets. A leftover `<name>.new` is never read, and is deleted on read.
 *
 * Builds before this wrote with AtomicFile, whose `<name>.bak` is the last good copy when a write was cut off. Such a
 * backup is put back over `<name>` before any read or write, as AtomicFile itself would have read it.
 *
 * [dirProvider] runs on first use, on the calling (background) thread, not at construction.
 */
internal class DiskBlobFiles(
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
    dirProvider: () -> File,
) : BlobFiles {
    private val dir: File by lazy(dirProvider)

    override fun read(name: String): ByteArray? {
        restoreLegacyBackup(name)
        // Only an interrupted write leaves this; it is never the blob.
        File(dir, name + NEW).delete()
        val file = File(dir, name)
        if (!file.exists()) return null
        // Anything else that can't be read (a directory, no permission) is a fault, never "no blob".
        if (!file.isFile) throw IOException("cannot read a blob")
        return file.readBytes()
    }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        ensureDirectory()
        restoreLegacyBackup(name)
        val file = File(dir, name)
        val staged = File(dir, name + NEW)
        try {
            FileOutputStream(staged).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!staged.renameTo(file)) throw IOException("cannot replace a blob")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // The old blob was never touched; just drop the half-written copy.
            staged.delete()
            throw e
        }
        syncDirectory(dir)
    }

    // The backup goes first and the blob last, so a delete cut short never leaves a backup that the next read would
    // put back as the blob.
    override fun delete(name: String) {
        for (suffix in listOf(LEGACY_BACKUP, NEW, "")) {
            val file = File(dir, name + suffix)
            if (file.exists() && !file.delete()) throw IOException("cannot delete a blob")
        }
        syncDirectory(dir)
    }

    override fun deleteAll() {
        val files = dir.listFiles().orEmpty().sortedBy { !it.name.endsWith(LEGACY_BACKUP) }
        val leftovers = files.filterNot { it.delete() }
        if (leftovers.isNotEmpty()) throw IOException("cannot delete every blob")
        if (files.isNotEmpty()) syncDirectory(dir)
    }

    // An older build's write was cut off: its backup is the last good copy, and the blob beside it is suspect.
    private fun restoreLegacyBackup(name: String) {
        val backup = File(dir, name + LEGACY_BACKUP)
        if (!backup.exists()) return
        if (!backup.renameTo(File(dir, name))) throw IOException("cannot restore a blob")
        syncDirectory(dir)
    }

    private fun ensureDirectory() {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create the blob directory")
        dir.parentFile?.let(syncDirectory)
    }

    private companion object {
        const val NEW = ".new"
        const val LEGACY_BACKUP = ".bak"
    }
}
