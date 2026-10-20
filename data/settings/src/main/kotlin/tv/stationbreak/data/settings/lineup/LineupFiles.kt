// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.lineup

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The lineup files under one root, behind a seam so tests can inject I/O faults. A name is a relative path built only
 * from canonical IDs by the caller.
 */
internal interface LineupFiles {
    /** The file's bytes, or null when there is no such file. */
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
}

internal class DiskLineupFiles(
    private val root: File,
) : LineupFiles {
    override fun read(name: String): ByteArray? =
        try {
            AtomicFile(root.resolve(name)).readFully()
        } catch (_: FileNotFoundException) {
            null
        }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        val file = root.resolve(name)
        file.parentFile?.let(::ensureDirectory)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // Whatever went wrong, never leave a half-written file behind.
            atomic.failWrite(out)
            throw e
        }
    }

    override fun move(
        from: String,
        to: String,
    ): Boolean {
        val source = root.resolve(from)
        if (!source.exists()) return false
        // Renaming over an existing file replaces it on Linux/Android, so one set-aside copy is kept.
        if (!source.renameTo(root.resolve(to))) throw IOException("cannot set the lineup aside")
        return true
    }

    override fun delete(name: String) {
        val file = root.resolve(name)
        // AtomicFile.delete also removes a leftover backup of an interrupted write.
        AtomicFile(file).delete()
        if (file.exists()) throw IOException("cannot delete the lineup")
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the lineup directory")
        }
    }
}
