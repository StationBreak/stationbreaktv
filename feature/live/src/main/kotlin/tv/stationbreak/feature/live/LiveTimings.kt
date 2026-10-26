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
 * @property flipSettle how long after the last Up or Down press the channel the viewer stopped on is tuned (spec 15):
 *   long enough that a held key only moves the number, short enough to cost the flip little of its time budget.
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
    val flipLogSize: Int = 50,
) {
    init {
        require(flipLogSize >= 1) { "At least one flip must be kept" }
        require(standInAttempts >= 1) { "At least one stand-in must be tried" }
    }
}
