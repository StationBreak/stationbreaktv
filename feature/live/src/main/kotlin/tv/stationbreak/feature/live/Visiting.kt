// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.feature.live.report.AiringKey
import tv.stationbreak.feature.live.report.PlaybackReporter
import tv.stationbreak.feature.live.report.ReportTarget

/**
 * The reporter's view of one block's play loop (spec 16, R3): the visit spans every try at the block, so a failed play
 * that is retried is one visit, however long the retry takes. It is left only when the block is left for good.
 */
internal class Visiting(
    private val reporter: PlaybackReporter?,
) {
    private var open = false
    private var failed = false

    /** The visit begins, or (after a failed play) goes on: the time between the failure and now was not watched. */
    fun playing(
        fromMs: Long,
        visit: (PlaybackReporter) -> Unit,
    ) {
        val r = reporter ?: return
        if (failed) r.resumed(fromMs)
        failed = false
        visit(r)
        open = true
    }

    /**
     * The play failed: the time until the next play (or the leave) is not watched time. [positionMs] is where the
     * player says it failed (negative when it could not say); a known one closes the interval exactly there.
     */
    fun failedPlay(positionMs: Long = -1) {
        if (open && !failed) reporter?.stall(positionMs.takeIf { it >= 0 })
        if (open) failed = true
    }

    /** The viewer paused at [positionMs] when known (forwarded while a visit is open). */
    fun paused(positionMs: Long?) {
        if (open) reporter?.paused(positionMs)
    }

    fun unpaused() {
        if (open) reporter?.unpaused()
    }

    fun seeking() {
        if (open) reporter?.seeking()
    }

    fun seeked(toMs: Long) {
        if (open) reporter?.seeked(toMs)
    }

    /** The block is left for good (also safe to call twice). */
    fun leave() {
        if (open) reporter?.leave()
        open = false
        failed = false
    }
}

/**
 * [Sitting.hold] for a scheduled program that plays: the reporter is told of the visit (non-suspending, so nothing here
 * waits on the server). Slates, idents and stand-ins never come here. The caller leaves [visiting] when the block ends.
 */
internal suspend fun Sitting.holdReporting(
    block: AiringBlock,
    program: Airing.Program,
    outcome: TuneOutcome.Playing,
    visiting: Visiting,
    fromMs: Long,
): Held {
    val facts = outcome.report
    if (reporter == null || facts == null) return hold(block.endMs, outcome.end, outcome.notes)
    val item = ItemId(program.itemId.value)
    val target = ReportTarget(item, facts.sourceId, facts.playSessionId, facts.playMethod, facts.runtime)
    visiting.playing(fromMs) { it.visit(sittingId, AiringKey(channel, block.startMs, item), target, fromMs) }
    val held = hold(block.endMs, outcome.end, outcome.notes, reporting = true)
    if (held == Held.Failed) visiting.failedPlay(failedPositionMs)
    return held
}
