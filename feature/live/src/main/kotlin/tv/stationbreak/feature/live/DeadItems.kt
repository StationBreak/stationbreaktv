// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

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
    heldBlockStartMs = null
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
 * card holds until the block ends. A piece can be paused and stepped like a program; once it ends behind (or its one
 * retry at the place is dead), the piece on at the sitting's time follows, or the schedule once the block is over.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // One stand-in loop: each early return ends the block's cover.
internal suspend fun Sitting.fillIn(
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
        // The "Next" line names what follows the dead block, as the block's own banner would have.
        val joined = position.inWholeMilliseconds
        val banner = bannerFor(onAir.channel, piece, BannerLabel.FillingIn, joined, driftMs, onAir.nextProgram())
        val what = TuneWhat(TuneKind.StandIn, piece, position)
        val outcome =
            tuneAndShow(scope, banner, snow = true, what = what, onAir = onAir) {
                tuner.tune(session, ItemId(standIn.itemId.value), position)
            }
        val failed =
            when (outcome) {
                is TuneOutcome.Playing -> {
                    when (val held = holdPiece(scope, onAir, piece, ItemId(standIn.itemId.value), outcome)) {
                        PieceHeld.Covered -> return null
                        is PieceHeld.Exited -> return held.exit
                        is PieceHeld.Next -> held.failed
                    }
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

/** How a stand-in piece's play ended, for [fillIn]. */
private sealed interface PieceHeld {
    /** The piece was cut at its end, which is the dead block's: the block is covered. */
    data object Covered : PieceHeld

    /** The next piece follows; [failed] when this one broke or ended early while live. */
    data class Next(
        val failed: Boolean,
    ) : PieceHeld

    /** The sitting ends: the retry at the place signed out or was stopped. */
    data class Exited(
        val exit: Exit,
    ) : PieceHeld
}

/**
 * A stand-in [piece]'s play of [item], live or behind (ADR-0021 4: it can be paused and stepped). A piece that ends
 * at its own end is not a failure, nor is its end while behind (its one retry at the place included): the next piece,
 * or the schedule, follows. Once behind no longer, the next block's look-ahead, dropped while behind, starts again.
 */
private suspend fun Sitting.holdPiece(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    piece: AiringBlock,
    item: ItemId,
    outcome: TuneOutcome.Playing,
): PieceHeld {
    val retune = retuneAt(scope, onAir, piece, item, BannerLabel.FillingIn)
    return when (val held = holdProgram(piece, outcome, null, retune, BlockRetries())) {
        Held.Boundary -> {
            if (piece.endMs >= onAir.now.endMs) PieceHeld.Covered else PieceHeld.Next(failed = false)
        }

        is Held.CaughtUp, Held.EndedInBlock -> {
            (held as? Held.CaughtUp)?.catchUp?.let { discardCatchUp(it) }
            // Only while the dead block is still on: past it, the next block is played anyway.
            if (vnowMs() < onAir.now.endMs) rearmBoundaryLookAhead()
            PieceHeld.Next(failed = false)
        }

        // Its retry used and still failing, with the clock inside the piece: a failed piece, as while live.
        Held.Ended, is Held.Failed, Held.Dead -> {
            PieceHeld.Next(failed = true)
        }

        is Held.Exited -> {
            PieceHeld.Exited(held.exit)
        }
    }
}

/**
 * A return to a kept paused stand-in piece [place] (spec 20, ADR-0021 5): the piece tuned at the place and paused, as a
 * program's place is, then its behind play (never reported). Once the piece is over, the dead block's stand-ins carry
 * on while it is still on ([standInsAfter]); otherwise what is on follows.
 */
internal suspend fun Sitting.resumeStandIn(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    standIn: Airing.StandIn,
    place: Place,
): Exit? {
    val piece = place.block
    val item = ItemId(standIn.itemId.value)
    val outcome = tunePaused(scope, onAir, place, item, BannerLabel.FillingIn, TuneKind.StandIn)
    val play = pausedPlay(outcome) ?: return resumeEnded(outcome)
    resumeSettled()
    val retune = retuneAt(scope, onAir, piece, item, BannerLabel.FillingIn)
    val held = holdProgram(piece, play, null, retune, BlockRetries(), place, outcome is TuneOutcome.Dead)
    (held as? Held.CaughtUp)?.catchUp?.let { discardCatchUp(it) }
    return if (held is Held.Exited) held.exit else standInsAfter(scope, standIn)
}

/** After a resumed stand-in piece: the stand-ins of its dead block, while that block is still on; else null. */
private suspend fun Sitting.standInsAfter(
    scope: CoroutineScope,
    standIn: Airing.StandIn,
): Exit? {
    val onAir = schedule.onAir(channel, Instant.fromEpochMilliseconds(vnowMs()), Sitting.WINDOW)
    val dead = onAir?.now?.takeIf { it.startMs == standIn.forBlockStartMs }?.airing as? Airing.Program
    return if (onAir != null && dead != null) fillIn(scope, onAir, dead.itemId) else null
}
