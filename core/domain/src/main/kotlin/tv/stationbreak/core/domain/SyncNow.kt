// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where Sync now stands (spec 26, Part 5). A closed set: no cause, address or message travels with it. */
sealed interface SyncNowState {
    /** Nothing has been asked for, or the last press ended with nothing to say (the sync had nothing to run). */
    data object Idle : SyncNowState

    /** A press is syncing: it is listing the server's libraries, waiting behind another sync, or syncing. */
    data object Running : SyncNowState

    /** The last press synced to its end at [atMs] (epoch milliseconds). */
    data class Done(
        val atMs: Long,
    ) : SyncNowState

    /** The last press stopped for [failure]. The saved library and today's schedule are as they were. */
    data class Failed(
        val failure: SyncFailure,
    ) : SyncNowState
}

/**
 * Sync now (spec 26, Part 5): the viewer asks for the server's library changes at once. One press lists the server's
 * libraries once and then runs the stay-current passes, waiting behind a sync already under way; never two at once.
 */
interface SyncNow {
    /** Where the last press stands. */
    val state: StateFlow<SyncNowState>

    /** Starts a press and gives true, or gives false and starts nothing while one is [SyncNowState.Running]. */
    fun start(): Boolean

    companion object {
        /** Never syncs: for a build or a test with no Sync now. */
        val None: SyncNow =
            object : SyncNow {
                override val state: StateFlow<SyncNowState> = MutableStateFlow(SyncNowState.Idle).asStateFlow()

                override fun start(): Boolean = false
            }
    }
}
