// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The pace of a held Up or Down (#430): the press steps at once, then the key's auto-repeats step on a fixed grid from
 * the press, the first [delay] after it and then every [every], whatever rate the remote repeats at. At most one step
 * per repeat: a repeat that arrives after a gap (a lost burst) steps once, not once for each step it missed, so the
 * number never jumps further than the viewer could read.
 */
internal class HeldFlip(
    private val delay: Duration,
    private val every: Duration,
) {
    private var pressedAt: Instant? = null
    private var stepped = 0L

    /** A new press (not a repeat) at [at]: it steps, and the grid starts from it. */
    fun press(at: Instant) {
        pressedAt = at
        stepped = 0
    }

    /** True when the repeat at [at] is due a step. A repeat with no press before it never steps. */
    fun repeatSteps(at: Instant): Boolean {
        val since = pressedAt?.let { at - it }
        // How many grid points the hold has passed: the first at [delay], then one every [every].
        val due = if (since == null || since < delay) 0L else 1 + ((since - delay) / every).toLong()
        val steps = due > stepped
        if (steps) stepped = due
        return steps
    }
}
