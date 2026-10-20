// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The lineup files under one root, behind a seam so tests can inject I/O faults. A name is a relative path built only
 * from canonical IDs by the caller.
 */
internal interface LineupFiles {
    /**
     * The file's bytes, or null when there is no such file. Throws [IOException] when the file is there but can't be
     * read, or is larger than [MAX_BYTES].
     */
    fun read(name: String): ByteArray?

    /** Atomic: a power cut leaves the old bytes or the new ones, never torn ones. Throws [IOException] on a fault. */
    fun write(
        name: String,
        bytes: ByteArray,
    )

    /** Renames [from] to [to], replacing [to]. True when [from] existed and moved. Throws [IOException] on a fault. */
    fun move(
        from: String,
        to: String,
    ): Boolean

    /** Removes the file. Throws [IOException] if it exists and can't be removed. */
    fun delete(name: String)

    /** The file names (not paths) in the directory [directory]; empty when it does not exist. */
    fun list(directory: String): List<String>

    companion object {
        /** The largest lineup file the store will read: a bigger one is a fault, never data. */
        const val MAX_BYTES = 1024 * 1024
    }
}

/**
 * The files on disk. A write goes to `<name>.new`, is synced, is renamed over `<name>`, and then the directory is
 * synced, so a power cut leaves the old bytes or the new ones. This is written here rather than with
 * `android.util.AtomicFile`, whose `.bak` scheme on API 25 can leave the only copy truncated when a write fails. An
 * orphan `<name>.new` is never read: with no `<name>` it is "no lineup".
 */
internal class DiskLineupFiles(
    private val root: File,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
) : LineupFiles {
    override fun read(name: String): ByteArray? {
        val file = root.resolve(name)
        if (!file.exists()) return null
        // Anything else that can't be read (a directory, no permission, too big) is a fault, never "no file".
        if (!file.isFile) throw IOException("cannot read the lineup")
        return readAtMost(file)
    }

    // Reads through a stream that stops at MAX_BYTES + 1, so a file that grows or lies about its length can't be
    // pulled whole into memory.
    private fun readAtMost(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, LineupFiles.MAX_BYTES + 1 - total))
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (total > LineupFiles.MAX_BYTES) throw IOException("the lineup is too large")
            }
        }
        return out.toByteArray()
    }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        val file = root.resolve(name)
        file.parentFile?.let(::ensureDirectory)
        val staged = File(file.path + NEW)
        try {
            FileOutputStream(staged).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!staged.renameTo(file)) throw IOException("cannot replace the lineup")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // The old file was never touched; just drop the half-written copy.
            staged.delete()
            throw e
        }
        file.parentFile?.let { syncDirectory(it) }
    }

    override fun move(
        from: String,
        to: String,
    ): Boolean {
        val source = root.resolve(from)
        if (!source.exists()) return false
        if (!source.renameTo(root.resolve(to))) throw IOException("cannot set the lineup aside")
        source.parentFile?.let { syncDirectory(it) }
        return true
    }

    override fun delete(name: String) {
        val file = root.resolve(name)
        // A leftover staged copy of an interrupted write goes too.
        File(file.path + NEW).delete()
        file.delete()
        if (file.exists()) throw IOException("cannot delete the lineup")
    }

    override fun list(directory: String): List<String> =
        root
            .resolve(directory)
            .list()
            ?.toList()
            .orEmpty()

    // Creates the directory; each directory that was made is recorded in its parent by syncing that parent.
    private fun ensureDirectory(directory: File) {
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.isDirectory }.toList()
        if (missing.isEmpty()) return
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create the lineup directory")
        missing.mapNotNull { it.parentFile }.forEach(syncDirectory)
    }

    private companion object {
        const val NEW = ".new"
        const val BUFFER = 8192
    }
}

/**
 * Flushes [directory]'s entries to storage so a rename or a new file in it survives a power cut. `RandomAccessFile`
 * can't open a directory, so this opens it read-only with `Os.open` and syncs the descriptor (API 21+). Best effort:
 * a file system that refuses it is not a failed save.
 */
internal fun syncDirectoryWithOs(directory: File) {
    try {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (_: ErrnoException) {
        // Best effort.
    }
}
