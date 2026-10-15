// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** The one record file, behind a seam so tests can inject I/O faults. */
internal interface RecordFile {
    /** The file's text, or null when there is no such file. */
    fun read(): String?

    /** Atomic: a power cut leaves the old text or the new one, never a torn one. Throws [IOException] on a fault. */
    fun write(text: String)
}

internal class DiskRecordFile(
    private val file: File,
) : RecordFile {
    override fun read(): String? =
        try {
            AtomicFile(file).readFully().toString(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }

    override fun write(text: String) {
        file.parentFile?.let(::ensureDirectory)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // Whatever went wrong, never leave a half-written file behind.
            atomic.failWrite(out)
            throw e
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the settings directory")
        }
    }
}
