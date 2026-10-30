// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The live screen's tunables (spec 14): safe defaults in one place, never inline constants (memory: configurable
 * defaults).
 *
 * @property bannerLinger how long the banner stays over the picture once it plays.
 * @property actionsLinger how long the actions banner (Info, Last) stays after the last key; the viewer's own
 *   banner, so it can need more than the tune banner does.
 * @property minSnow the least time the snow shows, even when the picture is ready sooner, so tuning always reads as
 *   tuning.
 * @property slateHold how long the "Technical difficulties" slate holds before the channel moves on.
 * @property standInThreshold the time left in a dead block at or under which the channel moves straight on (and
 *   drifts); over it, a stand-in fills the block.
 * @property standInAttempts how many stand-ins are tried for one dead block before the ident card holds.
 * @property preResolveLead how long before a block ends the next program is resolved.
 * @property crashBackoff how long the card holds after a sitting broke on an unexpected exception, before a fresh
 *   sitting re-anchors to the clock: long enough that a fault which repeats cannot spin the loop.
 * @property flipSettle how long after the last Up or Down press or repeat the channel the viewer stopped on is tuned
 *   (spec 15), but never while the key is still down: a key held past it tunes when it comes up (#430, R55). Short
 *   enough to cost the flip little of its time budget.
 * @property flipRepeatDelay how long a held Up or Down waits after its first step before it steps again (#430): the
 *   pause of a cable box or TV, so a press that lingers moves one channel, not two.
 * @property flipRepeatEvery how often a held Up or Down steps after that pause (#430): slow enough to read each channel
 *   and let go on the one wanted, whatever rate the remote repeats at (about 20 a second on a Fire TV remote).
 * @property flipReleaseFallback how long after a held key's last down or repeat the channel is tuned when its release
 *   never arrives (#430). Longer than [keyRepeatTimeout], so the gap between a press and its first auto-repeat
 *   never passes for a release.
 * @property keyRepeatTimeout the device's key-repeat timeout, `ViewConfiguration.getKeyRepeatTimeout()`: a held key's
 *   first auto-repeat comes about this long after its press (480 ms on the Insignia). The app passes the device's own
 *   ([forKeyRepeatTimeout]); Android's default stands in for it elsewhere (JVM tests).
 * @property pauseReportMax how long a pause keeps being reported to the server as paused (spec 20, ADR-0021): check-ins
 *   carry `IsPaused` for this long, then go silent, so a long pause does not hold a server session open. The next Play
 *   sends a fresh Playing.
 * @property flipLogSize how many flips [LiveChannel.flips] keeps, the newest last.
 */
data class LiveTimings(
    val bannerLinger: Duration = 4.seconds,
    val actionsLinger: Duration = 4.seconds,
    val minSnow: Duration = 500.milliseconds,
    val slateHold: Duration = 3.seconds,
    val standInThreshold: Duration = 30.minutes,
    val standInAttempts: Int = 2,
    val preResolveLead: Duration = 45.seconds,
    val crashBackoff: Duration = 5.seconds,
    val flipSettle: Duration = 400.milliseconds,
    val flipRepeatDelay: Duration = 400.milliseconds,
    val flipRepeatEvery: Duration = 250.milliseconds,
    val flipReleaseFallback: Duration = 1.seconds,
    val flipLogSize: Int = 50,
    val keyRepeatTimeout: Duration = DEFAULT_KEY_REPEAT_TIMEOUT,
    val pauseReportMax: Duration = DEFAULT_PAUSE_REPORT_MAX,
) {
    init {
        require(flipReleaseFallback > keyRepeatTimeout) { "flipReleaseFallback must outlast the key-repeat timeout" }
        require(flipLogSize >= 1) { "At least one flip must be kept" }
        require(standInAttempts >= 1) { "At least one stand-in must be tried" }
        require(flipRepeatDelay >= Duration.ZERO) { "flipRepeatDelay must not be negative" }
        require(flipRepeatEvery > Duration.ZERO) { "flipRepeatEvery must be positive" }
        require(pauseReportMax >= Duration.ZERO) { "pauseReportMax must not be negative" }
    }

    companion object {
        /** How long a pause is reported as paused before the reporter goes silent. */
        val DEFAULT_PAUSE_REPORT_MAX: Duration = 5.minutes

        /** Android's default key-repeat timeout (the long-press timeout): 500 ms. */
        val DEFAULT_KEY_REPEAT_TIMEOUT: Duration = 500.milliseconds

        /**
         * The defaults on a device whose key-repeat timeout is [timeout]: the release fallback is the default one, or
         * twice [timeout] when that is longer, so a slow key repeat (an accessibility setting) never ends a hold.
         */
        fun forKeyRepeatTimeout(timeout: Duration): LiveTimings {
            val fallback = LiveTimings().flipReleaseFallback
            return LiveTimings(keyRepeatTimeout = timeout, flipReleaseFallback = maxOf(fallback, timeout * 2))
        }
    }
}
