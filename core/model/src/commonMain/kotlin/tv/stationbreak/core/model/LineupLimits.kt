// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/** Limits of the stored lineup that the engine (which sizes what it writes) and the store (which enforces them) share. */
object LineupLimits {
    /** The largest lineup file the store reads or writes: 1 MiB. A bigger one is a fault, never data. */
    const val MAX_FILE_BYTES: Int = 1024 * 1024
}
