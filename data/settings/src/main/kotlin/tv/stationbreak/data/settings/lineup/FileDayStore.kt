// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.model.CanonicalId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The [DayStore] on files under `filesDir/days/<server>/<user>/<name>` (#480): app-private storage, one directory per
 * account. A path is built only from IDs that are already canonical and a name of digits, `_` and `-`, so nothing can
 * name a path outside its account's directory; any other account or name gets no file.
 *
 * A write goes to `<name>.new`, is synced, is renamed over `<name>`, and then the directory is synced, as the lineup's
 * files are ([DiskLineupFiles]): a power cut leaves the old file or the new one, never a torn one, and an orphan
 * `<name>.new` is never read or listed. Neither a write nor a read goes past [maxBytes]: a bigger file reads as
 * [StoredBytes.Failed]. Faults never throw. One file operation at a time, on [io].
 */
class FileDayStore internal constructor(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = MAX_BYTES,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
) : DayStore {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val lock = Mutex()

    override suspend fun read(
        account: LibraryAccount,
        name: String,
    ): StoredBytes {
        val file = fileOf(account, name) ?: return StoredBytes.Absent
        return locked {
            try {
                when {
                    !file.exists() -> StoredBytes.Absent

                    // Anything else that can't be read (a directory, say) is a fault, never "no file".
                    !file.isFile -> StoredBytes.Failed

                    else -> StoredBytes.Present(readAtMost(file))
                }
            } catch (_: IOException) {
                StoredBytes.Failed
            }
        }
    }

    // Faults never throw: anything but cancellation is a failed write, and the old file stays.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun write(
        account: LibraryAccount,
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val file = fileOf(account, name)
        if (file == null || bytes.size > maxBytes) return false
        return locked {
            val staged = File(file.path + NEW)
            try {
                file.parentFile?.let(::ensureDirectory)
                FileOutputStream(staged).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!staged.renameTo(file)) throw IOException("cannot replace the day")
                file.parentFile?.let(syncDirectory)
                true
            } catch (cancelled: CancellationException) {
                staged.delete()
                throw cancelled
            } catch (_: Exception) {
                staged.delete()
                false
            }
        }
    }

    override suspend fun names(account: LibraryAccount): List<String> {
        val directory = directoryOf(account) ?: return emptyList()
        return locked {
            directory
                .list()
                ?.filter { NAME.matches(it) }
                .orEmpty()
        }
    }

    override suspend fun delete(
        account: LibraryAccount,
        name: String,
    ): Boolean {
        val file = fileOf(account, name) ?: return false
        return locked { !file.exists() || file.delete() }
    }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileDayStore"

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun directoryOf(account: LibraryAccount): File? {
        val server = CanonicalId.parseCanonicalOrNull(account.server.value)
        val user = CanonicalId.parseCanonicalOrNull(account.user.value)
        return if (server == null || user == null) null else root.resolve("$server/$user")
    }

    private fun fileOf(
        account: LibraryAccount,
        name: String,
    ): File? = if (NAME.matches(name)) directoryOf(account)?.resolve(name) else null

    // Reads through a stream that stops at maxBytes + 1, so a file that grows or lies about its length can't be pulled
    // whole into memory.
    private fun readAtMost(file: File): ByteArray {
        val out = ByteArrayOutputStream(minOf(file.length(), maxBytes.toLong()).toInt())
        val buffer = ByteArray(BUFFER)
        var total = 0
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (total > maxBytes) throw IOException("the day is too large")
            }
        }
        return out.toByteArray()
    }

    // Creates the directory; each directory that was made is recorded in its parent by syncing that parent.
    private fun ensureDirectory(directory: File) {
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.isDirectory }.toList()
        if (missing.isEmpty()) return
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create the days directory")
        missing.mapNotNull { it.parentFile }.forEach(syncDirectory)
    }

    internal companion object {
        const val DIR_NAME = "days"
        const val NEW = ".new"
        const val BUFFER = 64 * 1024

        /** The largest file of days written or read: a realistic 50-channel day is under 2 MiB. */
        const val MAX_BYTES = 16 * 1024 * 1024

        private val NAME = Regex("[0-9_-]+")
    }
}
