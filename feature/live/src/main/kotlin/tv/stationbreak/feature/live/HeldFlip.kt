// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlin.time.Duration

/**
 * The pace of a held Up or Down (#430): the press steps at once, then the key's auto-repeats step on a fixed grid from
 * the press, the first [delay] after it and then every [every], whatever rate the remote repeats at. At most one step
 * per repeat: a repeat that arrives after a gap (a lost burst) steps once, not once for each step it missed, so the
 * number never jumps further than the viewer could read. Times are the key events' own (uptime milliseconds), so a step
 * of the wall clock cannot stall or rush a hold.
 */
internal class HeldFlip(
    delay: Duration,
    every: Duration,
) {
    private val delayMs = delay.inWholeMilliseconds
    private val everyMs = every.inWholeMilliseconds.coerceAtLeast(1)
    private var pressedAtMs: Long? = null
    private var stepped = 0L

    /** A new press (not a repeat) at [atMs]: it steps, and the grid starts from it. */
    fun press(atMs: Long) {
        pressedAtMs = atMs
        stepped = 0
    }

    /** True when the repeat at [atMs] is due a step. A repeat with no press before it never steps. */
    fun repeatSteps(atMs: Long): Boolean {
        val since = pressedAtMs?.let { atMs - it }
        // How many grid points the hold has passed: the first at the delay, then one every step.
        val due = if (since == null || since < delayMs) 0L else 1 + (since - delayMs) / everyMs
        val steps = due > stepped
        if (steps) stepped = due
        return steps
    }
}
