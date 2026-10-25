// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.feature.live.report.AiringKey
import tv.stationbreak.feature.live.report.ReportTarget

/**
 * [Sitting.hold] for a scheduled program that plays: the reporter is told of the visit (non-suspending, so nothing here
 * waits on the server) and of its leaving, however the hold ends, including a cancel by a flip. A retry of the same
 * block visits the same airing again, which the reporter continues. Slates, idents and stand-ins never come here.
 */
internal suspend fun Sitting.holdReporting(
    block: AiringBlock,
    program: Airing.Program,
    outcome: TuneOutcome.Playing,
): Held {
    val facts = outcome.report
    val reporter = reporter
    if (reporter == null || facts == null) return hold(block.endMs, outcome.end, outcome.notes)
    val item = ItemId(program.itemId.value)
    val target = ReportTarget(item, facts.sourceId, facts.playSessionId, facts.playMethod, facts.runtime)
    reporter.visit(sittingId, AiringKey(channel, block.startMs, item), target)
    try {
        return hold(block.endMs, outcome.end, outcome.notes, reporting = true)
    } finally {
        reporter.leave()
    }
}
