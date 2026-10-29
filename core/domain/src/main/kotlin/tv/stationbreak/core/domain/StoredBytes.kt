// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * What a byte store ([LineupStore], [DayStore]) holds under one name: nothing, the bytes exactly as they were saved, or
 * a file that couldn't be read. A caller must never save over a [Failed] load of data it cannot rebuild: the file is
 * still there and may be fine on the next read.
 */
sealed interface StoredBytes {
    /** No file exists under the name. */
    data object Absent : StoredBytes

    /** A file exists but could not be read (an I/O fault, or over the store's size cap). It is not known to be bad. */
    data object Failed : StoredBytes

    class Present(
        val bytes: ByteArray,
    ) : StoredBytes {
        // A constant: the bytes are the user's data and must not reach a log through this.
        override fun toString(): String = "StoredBytes.Present"
    }
}
