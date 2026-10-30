// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

/**
 * When the Back and Ahead steps settle into one seek, and where to (spec 20, ADR-0021 2 and 9). It follows the flip's
 * rule (#430, R55): nothing settles while the key is down except [LiveTimings.flipReleaseFallback] after the last press
 * or repeat, for a release that never comes; on the release, the target settles at the later of the last press or
 * repeat plus [LiveTimings.flipSettle], and now. A tap therefore settles its settle time after its press, so quick
 * taps add up to one seek, and a held key seeks once, when it comes up, even where the remote's first auto-repeat comes
 * after [LiveTimings.flipSettle] (480 ms on older hardware).
 *
 * Pure: [nowMs] is the clock (epoch ms). The steps' arithmetic (the clamps) is the caller's; this keeps the target.
 */
internal class StepSettle(
    timings: LiveTimings,
    private val nowMs: () -> Long,
) {
    /** A settled-to place: [toMs] in media time, [live] when an Ahead step's clamp hit the live position. */
    data class Target(
        val toMs: Long,
        val live: Boolean,
    )

    private val settleMs = timings.flipSettle.inWholeMilliseconds
    private val fallbackMs = timings.flipReleaseFallback.inWholeMilliseconds

    // DESIGN(#383): placeholder, held-step pace. A held Back or Ahead steps at the flip's pace: at 4 steps a second,
    // held Back takes about 45 s to go back 30 minutes, so how a viewer reaches the start of a long item (start-over
    // reachability) is for the design partner.
    private val pace = HeldFlip(timings.flipRepeatDelay, timings.flipRepeatEvery)

    private var keyDown = false
    private var lastKeyAtMs = 0L

    /** Where the steps so far add up to; null when none is pending. */
    var target: Target? = null
        private set

    /** The clock time (epoch ms) the pending target settles at; null when nothing is waiting to settle. */
    var dueAtMs: Long? = null
        private set

    /**
     * A Back or Ahead press, or a [repeat] of a held one, at the key event's time [atMs] (the clock when null). Each
     * puts the settle off to the release fallback. True when it steps: a press always, a repeat when the pace is due.
     */
    fun key(
        repeat: Boolean,
        atMs: Long?,
    ): Boolean {
        val now = nowMs()
        keyDown = true
        lastKeyAtMs = now
        dueAtMs = now + fallbackMs
        val keyAtMs = atMs ?: now
        if (!repeat) pace.press(keyAtMs)
        return !repeat || pace.repeatSteps(keyAtMs)
    }

    /** The steps now add up to [toMs]. */
    fun aim(
        toMs: Long,
        live: Boolean,
    ) {
        target = Target(toMs, live)
    }

    /** The key came up: true when the target settles now. A release with no key down does nothing. */
    fun released(): Boolean {
        if (!keyDown) return false
        keyDown = false
        dueAtMs = maxOf(lastKeyAtMs + settleMs, nowMs())
        return isDue()
    }

    fun isDue(): Boolean = dueAtMs?.let { nowMs() >= it } == true

    /** Settles: the pending target, if any, and nothing waits any more. */
    fun take(): Target? = drop()

    /** Drops the pending target (given back, so it can be logged) and the deadline; the key's state is kept. */
    fun drop(): Target? {
        val was = target
        target = null
        dueAtMs = null
        return was
    }
}
