// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.logos

import tv.stationbreak.core.domain.details.LogoStore
import java.io.File
import java.io.IOException

/**
 * A [LogoStore] of plain files: `dir/<account>/<key>`, capped at [maxBytes] in all, the least recently used going
 * first. A write goes to a temporary name and is renamed into place, so a reader never sees half a file. Nothing here
 * logs, and no name or byte of a logo ever leaves the directory.
 */
class FileLogoStore(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : LogoStore {
    init {
        require(maxBytes >= 1) { "A store holds at least a byte" }
    }

    private val lock = Any()

    override fun get(
        account: String,
        key: String,
    ): ByteArray? {
        val file = fileOf(account, key) ?: return null
        return synchronized(lock) { read(file) }
    }

    private fun read(file: File): ByteArray? =
        try {
            if (file.isFile) {
                // A read counts as a use: eviction goes by this time.
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }

    override fun put(
        account: String,
        key: String,
        bytes: ByteArray,
    ) {
        val file = fileOf(account, key)
        if (file != null && bytes.isNotEmpty() && bytes.size <= maxBytes) synchronized(lock) { write(file, key, bytes) }
    }

    // Under the lock. Written under a temporary name and renamed into place, then the store is brought under its cap.
    private fun write(
        file: File,
        key: String,
        bytes: ByteArray,
    ) {
        try {
            val folder = checkNotNull(file.parentFile)
            if (folder.isDirectory || folder.mkdirs()) {
                val temporary = File(folder, "$key.part")
                temporary.writeBytes(bytes)
                if (temporary.renameTo(file)) evict() else temporary.delete()
            }
        } catch (_: IOException) {
            // Not kept: the next look asks the server again.
        }
    }

    override fun retainOnly(account: String?) {
        synchronized(lock) {
            dir.listFiles()?.forEach { folder ->
                if (folder.name != account) folder.deleteRecursively()
            }
        }
    }

    // Under the lock. The files of every account, oldest use first, until what is left fits.
    private fun evict() {
        val files = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(PART) }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun fileOf(
        account: String,
        key: String,
    ): File? = if (HEX.matches(account) && HEX.matches(key)) File(File(dir, account), key) else null

    companion object {
        /** The default cap: 30 MB of logos, far more than a guide's worth. */
        const val DEFAULT_MAX_BYTES = 30L * 1024 * 1024

        private const val PART = ".part"
        private val HEX = Regex("[0-9a-f]{1,128}")
    }
}
