// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import android.content.Context
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
 * atomically (spec 12). It keeps opaque bytes and knows nothing of their format. A path is built only from IDs that
 * are already canonical (32 lower-case hexadecimal characters), so no account can name a path outside its own file;
 * any other ID gets no file and no access. Faults never throw: a read fault on an existing file is
 * [StoredBytes.Failed] (only a missing file is [StoredBytes.Absent]), a write fault is `false`, and the store logs
 * nothing.
 *
 * Cancellation behaves as for `FileServerSettings`: a caller cancelled after its file work has begun still completes
 * it, then sees `CancellationException` instead of the result.
 */
class FileLineupStore internal constructor(
    private val files: LineupFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
        val name = nameOf(account) ?: return false
        return locked { attempt { files.write(name, bytes) } }
    }

    override suspend fun setAside(account: LibraryAccount): Boolean {
        val name = nameOf(account) ?: return false
        // Nothing to set aside is a success: the file is already out of the way.
        return locked { attempt { files.move(name, "$name$ASIDE") } }
    }

    override suspend fun forget(account: LibraryAccount): Boolean {
        val name = nameOf(account) ?: return false
        return locked { attempt { files.delete(name) } }
    }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileLineupStore"

    private fun attempt(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: IOException) {
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
    }
}
