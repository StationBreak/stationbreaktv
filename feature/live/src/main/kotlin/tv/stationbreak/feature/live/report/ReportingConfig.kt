// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The playback reporting tunables (ADR-0018, spec 16): safe defaults in one place, no user-facing setting yet.
 *
 * @property startAfter how long the viewer must stay on a channel before the TV is reported as playing (the dwell). A
 *   sitting older than this reports each later item at once.
 * @property progressEvery the check-in interval once reporting; also the retry interval for a failed call.
 * @property playedThreshold the share of an item's runtime that must be watched for the app to mark it played.
 * @property callTimeout the longest one server call may take before it is abandoned, so a hung call cannot hold up
 *   the calls queued behind it for long.
 * @property userDataAttempts how many times one visit may try to read the item's user data before it gives up.
 * @property markAttempts how many times marking one airing played may be tried before it gives up.
 */
data class ReportingConfig(
    val startAfter: Duration = 30.seconds,
    val progressEvery: Duration = 30.seconds,
    val playedThreshold: Double = 0.9,
    val callTimeout: Duration = 5.seconds,
    val userDataAttempts: Int = 3,
    val markAttempts: Int = 3,
) {
    init {
        require(startAfter >= Duration.ZERO) { "startAfter must not be negative" }
        require(progressEvery > Duration.ZERO) { "progressEvery must be positive" }
        require(playedThreshold > 0.0 && playedThreshold <= 1.0) { "playedThreshold must be in (0, 1]" }
        require(callTimeout > Duration.ZERO) { "callTimeout must be positive" }
        require(userDataAttempts >= 1) { "At least one user-data read must be tried" }
        require(markAttempts >= 1) { "At least one mark-played must be tried" }
    }
}
