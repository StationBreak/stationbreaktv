// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import java.io.File

/** The results file keeps growing across runs; it is emptied at the start of a run once it passes a megabyte. */
internal object ResultsFile {
    const val MAX_BYTES = 1_000_000L

    fun trimIfLarge(
        file: File,
        maxBytes: Long = MAX_BYTES,
    ) {
        if (file.length() > maxBytes) file.delete()
    }
}
