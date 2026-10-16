// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

/**
 * What the saved library needs from the sync scheduler: to stop the running sync before it changes whose library is
 * saved. [RoomLibraryRepository.useAccount] (a different account) and [RoomLibraryRepository.forget] call it before
 * they touch a row, so the unique sync work is cancelled first. Cancelling is asynchronous, so every sync transaction
 * also checks that its account is still the signed-in one (Angua M1).
 */
fun interface SyncControl {
    /** Cancels the unique first-sync work, running or waiting. Does nothing if there is none. */
    fun cancelFirstSync()

    companion object {
        /** No scheduler: nothing to cancel. */
        val None = SyncControl {}
    }
}
