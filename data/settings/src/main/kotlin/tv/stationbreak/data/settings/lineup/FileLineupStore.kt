// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.model.CanonicalId
import java.io.File
import java.io.IOException

/**
 * The [LineupStore] on one file per account, `lineup/<server>/<user>.json` under `filesDir`, each replaced
 * atomically (spec 12; see [DiskLineupFiles]). It keeps opaque bytes and knows nothing of their format. A path is
 * built only from IDs that are already canonical (32 lower-case hexadecimal characters), so no account can name a
 * path outside its own file; any other ID gets no file and no access. A file over 1 MiB reads as
 * [StoredBytes.Failed]. A set-aside copy is `<name>.unreadable.<epochMillis>`, never replaced; the newest three are
 * kept. Faults never throw: a read fault on an existing file is
 * [StoredBytes.Failed] (only a missing file is [StoredBytes.Absent]), a write fault is `false`, and the store logs
 * nothing.
 *
 * Cancellation behaves as for `FileServerSettings`: a caller cancelled after its file work has begun still completes
 * it, then sees `CancellationException` instead of the result.
 */
class FileLineupStore internal constructor(
    private val files: LineupFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LineupStore {
    constructor(context: Context) : this(DiskLineupFiles(File(context.filesDir, DIR_NAME)))

    // One writer at a time, and a read never sees a file mid-rename.
    private val lock = Mutex()

    override suspend fun load(account: LibraryAccount): StoredBytes {
        val name = nameOf(account) ?: return StoredBytes.Absent
        return locked {
            try {
                files.read(name)?.let { StoredBytes.Present(it) } ?: StoredBytes.Absent
            } catch (_: IOException) {
                // The file is there but won't read: not absent, so nobody rebuilds over it.
                StoredBytes.Failed
            }
        }
    }

    override suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean {
        val name = nameOf(account)
        // Never write what a read would refuse (over the size cap): it would look like a failed file next time.
        if (name == null || bytes.size > LineupFiles.MAX_BYTES) return false
        return locked { attempt { files.write(name, bytes) } }
    }

    override suspend fun setAside(account: LibraryAccount): Boolean {
        val name = nameOf(account) ?: return false
        // Nothing to set aside is a success: the file is already out of the way.
        return locked {
            attempt {
                val copies = asideCopies(name)
                // Later than every copy there, even if the clock went backwards, so the order is the age.
                val stamp = maxOf(nowMillis(), (copies.maxOfOrNull { it.second } ?: -1L) + 1)
                if (files.move(name, "$name$ASIDE.$stamp")) prune(name)
            }
        }
    }

    override suspend fun forget(account: LibraryAccount): Boolean {
        val name = nameOf(account) ?: return false
        // The account's set-aside copies go with it: forgetting is the one explicit way to remove them.
        return locked {
            attempt {
                files.delete(name)
                asideCopies(name).forEach { files.delete(it.first) }
            }
        }
    }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileLineupStore"

    // The set-aside copies of [name] as (path, stamp), oldest first.
    private fun asideCopies(name: String): List<Pair<String, Long>> {
        val directory = name.substringBeforeLast('/')
        val prefix = name.substringAfterLast('/') + ASIDE + "."
        return files
            .list(directory)
            .mapNotNull { file ->
                val stamp = if (file.startsWith(prefix)) file.removePrefix(prefix).toLongOrNull() else null
                stamp?.let { "$directory/$file" to it }
            }.sortedBy { it.second }
    }

    // Keeps the newest MAX_ASIDE copies, so the first copy outlasts a few bad refreshes. Best effort: the move is done.
    private fun prune(name: String) {
        asideCopies(name).dropLast(MAX_ASIDE).forEach {
            try {
                files.delete(it.first)
            } catch (_: IOException) {
                // A stale copy that won't go is only disk use.
            }
        }
    }

    // Faults never throw: anything but cancellation (a SecurityException, say) is a failed write too.
    @Suppress("TooGenericExceptionCaught")
    private fun attempt(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun nameOf(account: LibraryAccount): String? {
        val server = CanonicalId.parseCanonicalOrNull(account.server.value)
        val user = CanonicalId.parseCanonicalOrNull(account.user.value)
        return if (server == null || user == null) null else "$server/$user$SUFFIX"
    }

    private companion object {
        const val DIR_NAME = "lineup"
        const val SUFFIX = ".json"
        const val ASIDE = ".unreadable"

        // Copies kept per account; the oldest goes when a further one is made.
        const val MAX_ASIDE = 3
    }
}
