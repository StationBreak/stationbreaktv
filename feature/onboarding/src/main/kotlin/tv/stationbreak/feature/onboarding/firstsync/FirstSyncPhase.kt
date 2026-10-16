// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.feature.onboarding.server.ErrorCopy

/** What the first-sync progress screen shows. No address, name, token or title is ever part of it. */
internal sealed interface FirstSyncPhase {
    /** The sync has been asked for and has not saved anything yet. */
    data object Starting : FirstSyncPhase

    /**
     * No first page has been saved for a while (15 seconds), so the TV is probably not on a network: the count line
     * says it is waiting. It goes away when progress arrives.
     */
    data object WaitingForNetwork : FirstSyncPhase

    /** [copied] items are saved so far, of [total] once the saved library knows it (from the first page). */
    data class Syncing(
        val copied: Int,
        val total: Int?,
    ) : FirstSyncPhase {
        /** How far along the bar is, 0 to 1: never past the end, and empty until the total is known. */
        val fraction: Float
            get() = if (total == null || total <= 0) 0f else (copied.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * The sync stopped and cannot go on by itself. [copy] is the plain-language entry of the error table for [error].
     * With [sessionLost] the server no longer accepts the sign-in, so Retry goes back to signing in.
     */
    data class Failed(
        val error: MediaServerError,
        val copy: ErrorCopy,
        val sessionLost: Boolean,
    ) : FirstSyncPhase
}

/** What the screen tells whoever shows it, once each. */
internal enum class FirstSyncEvent {
    /** The first sync has ended (or there is nothing to wait for): on to the next destination. */
    Finished,

    /** The sign-in is gone: go back to launch routing, which asks the user to sign in again. */
    SessionLost,
}
