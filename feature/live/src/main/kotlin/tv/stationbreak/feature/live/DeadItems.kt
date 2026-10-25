// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId

/**
 * A program that cannot be played (spec 14, goal 6): the "Technical difficulties" slate, then, by the time left in the
 * block at the moment of the decision, either a move straight on to the next show (which starts a sitting drift) or
 * stand-ins until the block ends. [dead] is the id to keep out of the stand-ins.
 */
@Suppress("ReturnCount") // Each is a way the dead item is dealt with: nothing to do, moved on, or stood in for.
internal suspend fun Sitting.deadItem(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    dead: CanonicalId,
): Exit? {
    val block = onAir.now
    // Worked out before the stop: the slate must follow the stop at once, not after a look at the schedule.
    val up = upNext(onAir)
    tuner.stop(session)
    val remaining = block.endMs - vnowMs()
    if (remaining <= 0) return null
    state.value = LiveState.TechnicalDifficulties(up, onAir.tag())
    onSettled(scope)
    delay(timings.slateHold)
    if (remaining <= timings.standInThreshold.inWholeMilliseconds) {
        // Move on: the next show plays from its start, so this sitting now runs ahead of the clock.
        driftMs = maxOf(driftMs, block.endMs - clock.now().toEpochMilliseconds())
        reportFlipEnded("slate")
        return null
    }
    return fillIn(scope, onAir, dead)
}

/**
 * Stand-ins for the dead block of [onAir], joined at the clock position and kept until the block ends. A stand-in that
 * fails (or ends early) is replaced by the next pick, up to [LiveTimings.standInAttempts] failures; then the ident
 * card holds until the block ends.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // One stand-in loop: each early return ends the block's cover.
private suspend fun Sitting.fillIn(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    dead: CanonicalId,
): Exit? {
    val block = onAir.now
    var exclude = setOf(dead)
    var failures = 0
    var laid: List<AiringBlock>? = null
    while (failures < timings.standInAttempts) {
        val at = vnowMs()
        if (at >= block.endMs) return null
        val pieces = laid ?: schedule.standIns(channel, block, exclude).also { laid = it }
        val piece = pieces.firstOrNull { at >= it.startMs && at < it.endMs } ?: break
        val standIn = piece.airing as Airing.StandIn
        val position = checkNotNull(piece.positionAt(at))
        val banner = bannerFor(onAir.channel, piece, BannerLabel.FillingIn, position.inWholeMilliseconds, driftMs)
        val outcome =
            tuneAndShow(scope, banner, snow = true, what = TuneWhat(TuneKind.StandIn, piece, position)) {
                tuner.tune(session, ItemId(standIn.itemId.value), position)
            }
        val failed =
            when (outcome) {
                is TuneOutcome.Playing -> {
                    // A piece that ends at its own end is not a failure: the next piece (or the schedule) follows.
                    val held = hold(piece.endMs, outcome.end)
                    if (held == Held.Boundary && piece.endMs >= block.endMs) return null
                    held != Held.Boundary
                }

                is TuneOutcome.Dead -> {
                    true
                }

                TuneOutcome.Expired -> {
                    return expired()
                }

                TuneOutcome.Cancelled -> {
                    return Exit.Stopped
                }
            }
        if (failed) {
            exclude = exclude + standIn.itemId
            failures++
            laid = null
        }
    }
    reportFlipEnded("failed")
    return holdIdent(scope, onAir, block.endMs)
}
