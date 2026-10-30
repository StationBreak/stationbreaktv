// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import tv.stationbreak.feature.live.LiveTimings
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
 * @property pauseReportMax how long a pause is reported as paused before check-ins go silent (spec 20); the live run
 *   copies it from [LiveTimings.pauseReportMax].
 */
data class ReportingConfig(
    val startAfter: Duration = 30.seconds,
    val progressEvery: Duration = 30.seconds,
    val playedThreshold: Double = 0.9,
    val callTimeout: Duration = 5.seconds,
    val userDataAttempts: Int = 3,
    val markAttempts: Int = 3,
    val pauseReportMax: Duration = LiveTimings.DEFAULT_PAUSE_REPORT_MAX,
) {
    init {
        require(startAfter >= Duration.ZERO) { "startAfter must not be negative" }
        require(progressEvery > Duration.ZERO) { "progressEvery must be positive" }
        require(playedThreshold > 0.0 && playedThreshold <= 1.0) { "playedThreshold must be in (0, 1]" }
        require(callTimeout > Duration.ZERO) { "callTimeout must be positive" }
        // An old run's last Stopped drains for up to three call timeouts after the run ends; a new run's first Playing
        // must not come before it, or the server could see Playing(B) then Stopped(A).
        require(startAfter > callTimeout * DRAIN_CALLS) { "startAfter must be longer than three call timeouts" }
        require(userDataAttempts >= 1) { "At least one user-data read must be tried" }
        require(markAttempts >= 1) { "At least one mark-played must be tried" }
        require(pauseReportMax >= Duration.ZERO) { "pauseReportMax must not be negative" }
    }

    companion object {
        /** How many call timeouts a closing reporter may take to drain its queue. */
        const val DRAIN_CALLS = 3
    }
}
