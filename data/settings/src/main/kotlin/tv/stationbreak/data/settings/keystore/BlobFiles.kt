// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.keystore

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** The blob files, one per [BlobCodec.fileName], behind a seam so tests can inject I/O faults. */
internal interface BlobFiles {
    /** The blob's bytes, or null when there is no such file. */
    fun read(name: String): ByteArray?

    /** Atomic: a power cut leaves the old blob or the new one, never a torn one. */
    fun write(
        name: String,
        bytes: ByteArray,
    )

    fun delete(name: String)

    /** Every blob, and any temporary or backup file an interrupted write left. */
    fun deleteAll()
}

/** [dirProvider] runs on first use, on the calling (background) thread, not at construction. */
internal class DiskBlobFiles(
    dirProvider: () -> File,
) : BlobFiles {
    private val dir: File by lazy(dirProvider)


    override fun read(name: String): ByteArray? =
        try {
            AtomicFile(File(dir, name)).readFully()
        } catch (_: FileNotFoundException) {
            null
        }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create the blob directory")
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // Whatever went wrong, never leave a half-written file behind.
            file.failWrite(out)
            throw e
        }
    }

    override fun delete(name: String) {
        AtomicFile(File(dir, name)).delete()
        if (File(dir, name).exists()) throw IOException("cannot delete a blob")
    }

    override fun deleteAll() {
        val leftovers = dir.listFiles().orEmpty().filterNot { it.delete() }
        if (leftovers.isNotEmpty()) throw IOException("cannot delete every blob")
    }
}
