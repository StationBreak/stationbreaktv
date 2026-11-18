// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private const val STAGED_SUFFIX = ".new"
private const val BUFFER = 64 * 1024

/**
 * The one durable write in `data:settings`. Creates [file]'s missing parent directories, writes [bytes] to
 * `<name>.new`, syncs it, renames it over [file], and then syncs the directory with [syncDirectory] (so tests can
 * inject it). A power cut leaves the old file or the new one, never a torn one. `android.util.AtomicFile` is not used:
 * on API 25 its `.bak` scheme can leave the only copy truncated when a write fails.
 *
 * Throws [IOException] (or whatever the file system throws) on a fault, after deleting the staged copy, so the old
 * file is untouched; each caller decides whether that is a thrown error or a false. Cancellation passes through. An
 * orphan `<name>.new` is never [file], so a reader never sees it.
 */
internal fun writeStaged(
    file: File,
    bytes: ByteArray,
    syncDirectory: (File) -> Unit,
) {
    file.parentFile?.let { ensureDirectory(it, syncDirectory) }
    val staged = File(file.path + STAGED_SUFFIX)
    try {
        FileOutputStream(staged).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!staged.renameTo(file)) throw IOException("cannot replace the file")
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        // The old file was never touched; just drop the half-written copy.
        staged.delete()
        throw e
    }
    file.parentFile?.let(syncDirectory)
}

/** Creates [directory]; each directory that was made is recorded in its parent by syncing that parent. */
internal fun ensureDirectory(
    directory: File,
    syncDirectory: (File) -> Unit,
) {
    val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.isDirectory }.toList()
    if (missing.isEmpty()) return
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create the directory")
    missing.mapNotNull { it.parentFile }.forEach(syncDirectory)
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

/**
 * [file]'s bytes, stopping at [max] + 1, so a caller can tell "over the cap" (the result is longer than [max]) and a
 * file that grows or lies about its length can't be pulled whole into memory.
 */
internal fun readAtMost(
    file: File,
    max: Int,
): ByteArray {
    val out = ByteArrayOutputStream(minOf(file.length(), max + 1L).toInt())
    val buffer = ByteArray(BUFFER)
    var total = 0
    FileInputStream(file).use { input ->
        while (total <= max) {
            val read = input.read(buffer, 0, minOf(buffer.size, max + 1 - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
    }
    return out.toByteArray()
}
