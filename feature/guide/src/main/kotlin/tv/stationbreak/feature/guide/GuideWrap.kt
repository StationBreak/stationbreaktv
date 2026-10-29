// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlin.time.Duration.Companion.milliseconds

/** How long a held Up or Down waits at the last (or first) row before it wraps: live's flipRepeatDelay (#430). */
internal val WRAP_HOLD = 400.milliseconds

/**
 * Whether a step from [from] to [to] went round the end of the rows: Down to a row above, or Up to a row below.
 */
internal fun GuideModel.wrapped(
    from: GuideFocus,
    to: GuideFocus,
    direction: GuideDirection,
): Boolean {
    val before = indexOf(from)?.first
    val after = indexOf(to)?.first
    return when {
        before == null || after == null -> false
        direction == GuideDirection.Down -> after < before
        direction == GuideDirection.Up -> after > before
        else -> false
    }
}

/**
 * The pace of a wrap, as live's held flip paces its steps (#430): a press wraps at once, but a held key's repeats stop
 * at the end for [WRAP_HOLD] first, counted from the first repeat that met it, so a long hold shows the last row
 * before it goes round rather than flying past. Steps that do not wrap are not paced: the guide has always scrolled at
 * the key's own rate.
 */
internal class WrapHold {
    // When a repeat first met the end; null when none is waiting there.
    private var reachedEndAtMs: Long? = null

    /** Whether the step at [atMs] (the key event's own time) may go ahead; [wraps] says it goes round the end. */
    fun allows(
        wraps: Boolean,
        repeat: Boolean,
        atMs: Long,
    ): Boolean {
        val since = reachedEndAtMs
        val waiting = wraps && repeat
        val allowed = !waiting || (since != null && atMs - since >= WRAP_HOLD.inWholeMilliseconds)
        reachedEndAtMs = if (waiting && !allowed) since ?: atMs else null
        return allowed
    }
}
