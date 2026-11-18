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
import tv.stationbreak.data.settings.readAtMost
import tv.stationbreak.data.settings.syncDirectoryWithOs
import tv.stationbreak.data.settings.writeStaged
import java.io.File
import java.io.IOException

/**
 * The [DayStore] on files under `filesDir/days/<server>/<user>/<name>` (#480): app-private storage, one directory per
 * account. A path is built only from IDs that are already canonical and a name of lower-case letters, digits, `_` and
 * `-` (at most `DayStore.MAX_NAME` long), so nothing can name a path outside its account's directory; any other
 * account or name gets no file. As an `AccountCache` it deletes a signed-out account's directory ([forget]) and every
 * account's but the signed-in one's ([useAccount]), and from then on refuses writes for any account but the signed-in
 * one (any account is written until [useAccount] names one) and for a forgotten one, so a late save cannot bring them
 * back.
 *
 * A write goes to `<name>.new`, is synced, is renamed over `<name>`, and then the directory is synced, as the lineup's
 * files are ([DiskLineupFiles]): a power cut leaves the old file or the new one, never a torn one, and an orphan
 * `<name>.new` is never read or listed, and is deleted before the account's next write. Neither a write nor a read
 * goes past [maxBytes]: a bigger file reads as [StoredBytes.Failed]. Faults never throw. One file operation at a time,
 * on [io].
 */
@Suppress("TooManyFunctions") // The port's six operations, each with the file work it needs.
class FileDayStore internal constructor(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = MAX_BYTES,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryWithOs,
) : DayStore {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val lock = Mutex()

    // Under [lock]: the signed-in account's directory, once [useAccount] has said, and the directories of accounts
    // [forget] has cleared in this process. Neither may be written to by a save that finishes late (a build that began
    // before the sign-out or the switch): that would bring a signed-out account's days back.
    private var active: File? = null
    private val forgotten = HashSet<File>()

    override suspend fun read(
        account: LibraryAccount,
        name: String,
    ): StoredBytes {
        val file = fileOf(account, name) ?: return StoredBytes.Absent
        return locked {
            try {
                when {
                    !file.exists() -> {
                        StoredBytes.Absent
                    }

                    // Anything else that can't be read (a directory, say) is a fault, never "no file".
                    !file.isFile -> {
                        StoredBytes.Failed
                    }

                    else -> {
                        val bytes = readAtMost(file, maxBytes)
                        if (bytes.size > maxBytes) throw IOException("the day is too large")
                        StoredBytes.Present(bytes)
                    }
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
        val directory = directoryOf(account)
        if (file == null || directory == null || bytes.size > maxBytes) return false
        return locked {
            val current = active
            if (directory in forgotten || (current != null && current != directory)) return@locked false
            try {
                // A staged copy left by a write the process died in is never read: it goes before the next write.
                file.parentFile
                    ?.listFiles()
                    ?.filter { it.name.endsWith(NEW) }
                    ?.forEach { it.delete() }
                writeStaged(file, bytes, syncDirectory)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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

    /** [account] signed out: its directory of days goes, and nothing is written to it again in this process. */
    override suspend fun forget(account: LibraryAccount) {
        val directory = directoryOf(account) ?: return
        locked {
            forgotten += directory
            if (active == directory) active = null
            quietly { directory.deleteRecursively() }
        }
    }

    /**
     * [account] is the one signed in now: every other account's directory of days goes, and only this account's days
     * are written from now on. Never throws.
     */
    override suspend fun useAccount(account: LibraryAccount) {
        val own = directoryOf(account)
        locked {
            active = own
            own?.let { forgotten -= it }
            quietly {
                root.listFiles()?.forEach { server ->
                    server.listFiles()?.filter { it != own }?.forEach { it.deleteRecursively() }
                    // A server directory with no account left in it goes too.
                    if (server.list()?.isEmpty() == true) server.delete()
                }
            }
        }
    }

    // Clearing a cache is best effort: a file that won't go is only disk use, and a sign-out must not fail on it.
    @Suppress("TooGenericExceptionCaught")
    private fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Best effort.
        }
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

    internal companion object {
        const val DIR_NAME = "days"
        const val NEW = ".new"

        /**
         * The largest file of days written or read: a realistic day of about 50 channels (the planner's 48 picks and
         * the leftovers) is under 2 MiB. The viewer's own channels, edited and opt-in starters and pinned ones outside
         * the top cost the planner no seat (ADR-0023, the seat rule), so the lineup can be larger. On the JVM, over a
         * generated library of 20,000 items, the file is about 1.7 MiB at 50 channels, 1.7 at 60, 1.8 at 75 and 1.8 at
         * 100 (`PersistedDaysSizeTest`), well under this bound. The Insignia's build, restore and guide-open timings at
         * those counts are measured separately (the test session) and recorded in the ADR-0023 addendum.
         */
        const val MAX_BYTES = 16 * 1024 * 1024

        private val NAME = Regex("[0-9a-z_-]{1,${DayStore.MAX_NAME}}")
    }
}
