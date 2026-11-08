// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

/**
 * Whether the stored selection has been resumed at start (spec 26 Part 4, §2): [await] returns once the resume has run
 * (whether it changed anything or failed), and at once after that. Every selection operation and the library workers
 * (the first sync, stay current, the add pass) wait on it before they read the selection; the resume itself does not.
 */
fun interface SelectionReady {
    /** Suspends until the resume at start has run. */
    suspend fun await()

    companion object {
        /** Already complete: for a process with nothing to resume, and for the app until the resume is built. */
        val Complete: SelectionReady = SelectionReady {}
    }
}
