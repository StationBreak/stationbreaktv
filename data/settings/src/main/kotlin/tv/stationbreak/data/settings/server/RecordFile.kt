// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import tv.stationbreak.data.settings.lineup.syncDirectoryWithOs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The one record file, behind a seam so tests can inject I/O faults. */
internal interface RecordFile {
    /** The file's text, or null when there is no such file. Throws [IOException] when it is there but can't be read. */
    fun read(): String?

    /** Atomic: a power cut leaves the old text or the new one, never a torn one. Throws [IOException] on a fault. */
    fun write(text: String)
}

/**
 * The record on disk, written as the lineup files are (`DiskLineupFiles`): the text goes to `<name>.new`, which is
 * synced, renamed over `<name>`, and then the directory is synced, so a power cut leaves the old text or the new one.
 * `android.util.AtomicFile` is not used: on API 25 its `.bak` scheme can leave the only copy truncated when a write
 * fails. A leftover `<name>.new` is never read, and is deleted on read.
 *
 * Builds before spec 26 Part 4 wrote with AtomicFile, whose `<name>.bak` is the last good copy when a write was cut
 * off. Such a backup is put back over `<name>` before any read or write, as AtomicFile itself would have read it.
 */
internal class DiskRecordFile(
    private val file: File,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
) : RecordFile {
    private val staged = File(file.path + NEW)
    private val legacyBackup = File(file.path + LEGACY_BACKUP)

    override fun read(): String? {
        restoreLegacyBackup()
        // Only an interrupted write leaves this; it is never the record.
        staged.delete()
        if (!file.exists()) return null
        // Anything else that can't be read (a directory, no permission) is a fault, never "no file".
        if (!file.isFile) throw IOException("cannot read the settings")
        return file.readBytes().toString(Charsets.UTF_8)
    }

    override fun write(text: String) {
        file.parentFile?.let(::ensureDirectory)
        restoreLegacyBackup()
        try {
            FileOutputStream(staged).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!staged.renameTo(file)) throw IOException("cannot replace the settings")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // The old file was never touched; just drop the half-written copy.
            staged.delete()
            throw e
        }
        file.parentFile?.let { syncDirectory(it) }
    }

    // An older build's write was cut off: its backup is the last good copy, and the record beside it is suspect. The
    // rename replaces the record in one step, and putting it back first means a later write can never be undone by
    // the backup reappearing.
    private fun restoreLegacyBackup() {
        if (!legacyBackup.exists()) return
        if (!legacyBackup.renameTo(file)) throw IOException("cannot restore the settings")
        file.parentFile?.let { syncDirectory(it) }
    }

    // Creates the directory; each directory that was made is recorded in its parent by syncing that parent.
    private fun ensureDirectory(directory: File) {
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.isDirectory }.toList()
        if (missing.isEmpty()) return
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create the settings directory")
        missing.mapNotNull { it.parentFile }.forEach(syncDirectory)
    }

    private companion object {
        const val NEW = ".new"
        const val LEGACY_BACKUP = ".bak"
    }
}
