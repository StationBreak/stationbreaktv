// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.PlayerEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * One fresh tune of a behind play's item at a media position (ms): its retry at the place (ADR-0021 4, 6). [onPlaying]
 * is told of the play as soon as the tune answers, before the picture shows.
 */
internal fun interface Retune {
    suspend fun at(
        mediaMs: Long,
        onPlaying: (TuneOutcome.Playing) -> Unit,
    ): TuneOutcome
}

/** The one retry a block (or stand-in piece) allows, shared by its live and behind plays (ADR-0021 4 and 6). */
internal class BlockRetries {
    /** True once the retry was taken. */
    var taken = false
        private set

    /** Takes the retry: true when it was still there. */
    fun take(): Boolean = !taken.also { taken = true }
}

/**
 * The [Retune] of [item] on [block] (a program's block, or a stand-in piece): a fresh tune at the place under the snow,
 * with the block's banner ([label], joined at the place), reported as [TuneKind.Retry]. A session that expired shows
 * as signed out before the behind play exits.
 */
@Suppress("LongParameterList") // The block's scope, schedule, block, item and banner label: what its own tune uses.
internal fun Sitting.retuneAt(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    block: AiringBlock,
    item: ItemId,
    label: BannerLabel,
): Retune =
    Retune { mediaMs, onPlaying ->
        val position = mediaMs.milliseconds
        val banner = bannerFor(onAir.channel, block, label, mediaMs, driftMs, onAir.nextProgram())
        val what = TuneWhat(TuneKind.Retry, block, position)
        val outcome =
            tuneAndShow(scope, banner, snow = true, what = what, onAir = onAir, onPlaying = onPlaying) {
                tuner.tune(session, item, position)
            }
        if (outcome == TuneOutcome.Expired) expired()
        outcome
    }

/**
 * A program's (or stand-in piece's) play while it is live: [hold] until the play ends or the block does, and when the
 * viewer pauses or steps back, a [BehindPlay] until its item ends, it ends the sitting, or it is back at live (then
 * the live hold carries on with the play it hands back). [visiting] is the block's visit, told of pauses and seeks;
 * null when the play is not reported (a stand-in is never). [retune] is the behind play's retry at the place, made
 * only while [retries] still holds the block's one retry.
 */
@Suppress("ReturnCount") // Each return is one way the program's play ends; a return to live goes round again.
internal suspend fun Sitting.holdProgram(
    block: AiringBlock,
    first: TuneOutcome.Playing,
    visiting: Visiting?,
    retune: Retune,
    retries: BlockRetries,
): Held {
    var play = first
    while (true) {
        val held =
            when (val live = hold(block.endMs, play.end, play.notes, visiting != null, play.control, block.startMs)) {
                LiveHold.Boundary -> return Held.Boundary
                LiveHold.Ended -> return Held.Ended
                is LiveHold.Failed -> return Held.Failed(live.positionMs)
                is LiveHold.Transport -> live
            }
        val behindPlay = BehindPlay(behindHost(), block, play, visiting, retune, retries)
        behind = behindPlay
        val ended =
            try {
                behindPlay.run(held.op)
            } finally {
                behind = null
            }
        when (ended) {
            // Live again: the block's look-ahead, dropped while behind, is started again for the boundary's cut.
            is BehindEnd.Live -> {
                rearmBoundaryLookAhead()
                play = ended.play
            }

            is BehindEnd.ItemEnd -> {
                return Held.CaughtUp(ended.catchUp)
            }

            BehindEnd.EndedInBlock -> {
                return Held.EndedInBlock
            }

            BehindEnd.Dead -> {
                return Held.Dead
            }

            is BehindEnd.Exit -> {
                return Held.Exited(ended.exit)
            }
        }
    }
}

/** How a [BehindPlay] ended. */
internal sealed interface BehindEnd {
    /**
     * The item reached its end (the cut, the file's end past its block, or live once the block has ended): catch up
     * with what is on now, from [catchUp] when it was resolved ahead and is still held.
     */
    data class ItemEnd(
        val catchUp: Pending.CatchUp?,
    ) : BehindEnd

    /** The file ended early and the clock is still inside the block: the ident holds to its end. */
    data object EndedInBlock : BehindEnd

    /** The play failed with the block's retry used (or the retry is dead), the clock still inside the block. */
    data object Dead : BehindEnd

    /** Back at the live position on the same item, still on air: the live hold carries on with [play]. */
    data class Live(
        val play: TuneOutcome.Playing,
    ) : BehindEnd

    /** The sitting ends: the retry at the place signed out or was stopped. */
    data class Exit(
        val exit: tv.stationbreak.feature.live.Exit,
    ) : BehindEnd
}

/**
 * The viewer's play of [block] once they have paused (or stepped back) off the live position (spec 20, ADR-0021 3, 5
 * and 9). It is either paused or playing behind; how far behind is derived from the media position, never stored. It
 * holds on media time, not wall time: it waits for the media left to the block's cut while playing, and not at all
 * while paused, so the block's slot may end meanwhile and Play carries on with it.
 *
 * Catch-up (ADR-0021 4): once behind, the block's wall-clock look-ahead is dropped. With [LiveTimings.preResolveLead]
 * of media left, what will be on at the cut (the sitting's time now plus the media left) is resolved ahead; a pause or
 * a seek lets that handle go and arms again. At the cut, at the file's end past its block, or at live once the block
 * has ended by the clock, the item ends with the handle. A file that ends with the clock still inside its block holds
 * the ident instead.
 *
 * It owns the seek target: Back and Ahead steps add up there, clamped at the block's in-point and at the lesser of the
 * live position and the cut, and one absolute seek settles them as a flip does: never while the key is down, on its
 * release (no sooner than [LiveTimings.flipSettle] after the last press or repeat), or after
 * [LiveTimings.flipReleaseFallback] when no release comes. A settled target at the live position, while the block is
 * still on air, is live again.
 *
 * Single writer: the play's events are only forwarded by their collector, into [events], and its loop takes them
 * with the ops and the timers, so all its state is written there (or by the banner's linger, in the same scope). It is
 * confined to the sitting's dispatcher; only what [pausedPlace] reads from outside is `@Volatile`.
 *
 * A play that fails (a seek that timed out included) is tuned once more at the place by [retune], under the snow,
 * while [retries] still holds the block's one retry (ADR-0021 4 and 6). If it plays, the behind play carries on with
 * it, paused (before its picture shows) if it was paused, or live if it was going back to live. When no retry is left,
 * or the retry is dead: once the block has ended by the clock, the item has ended and the sitting catches up, with no
 * slate and no stand-in; while the clock is still inside the block, the item is dead for it.
 *
 * It holds the one subscription to the play's events, which drive the [visiting] reports; a retry moves it to the new
 * play. [visiting] is null when the play is not reported (a stand-in is never). A stand-in piece (ADR-0021 4) is
 * paused and stepped as a program is, but resolves nothing ahead: what follows it is the dead block's next piece.
 */
@Suppress("TooManyFunctions") // One function per transport op and state change.
internal class BehindPlay(
    private val host: BehindHost,
    private val block: AiringBlock,
    first: TuneOutcome.Playing,
    private val visiting: Visiting?,
    private val retune: Retune,
    private val retries: BlockRetries,
) {
    /** The play on screen: the first, or the retry at the place that replaced it. */
    private var play = first

    /** A program's block resolves its catch-up ahead; a stand-in piece's end goes back to the stand-ins. */
    private val onProgram = block.airing is Airing.Program

    /** The collector forwarding the play's events into [events]; replaced with the play. */
    private var collector: Job? = null

    private val inPointMs = TransportMath.inPointMs(block)
    private val cutMs = TransportMath.cutMs(block)
    private val timings = host.timings
    private val aheadMs = timings.stepAhead.inWholeMilliseconds
    private val rearmMinMs = timings.holdRearmMin.inWholeMilliseconds
    private val goLiveWaitMs = timings.goLiveWait.inWholeMilliseconds
    private val leadMs = timings.preResolveLead.inWholeMilliseconds

    /** Paused or playing behind: Play/Pause is resolved against this, not against the player's last event. */
    @Volatile
    private var paused = false

    /** The media position last known (a read, or the last Paused or SeekDone event), and the clock time it was so. */
    @Volatile
    private var knownMs = inPointMs
    private var knownAtMs = 0L

    /** Whether the transport view shows: while paused, and for [LiveTimings.bannerLinger] after Play or a step. */
    private var visible = true
    private var linger: Job? = null

    /** Set when the play's own end came (not a cut): its last notes are waited for. */
    private var playEnded = false

    /** The steps' target and when it settles into one seek (the flip's rule). */
    private val steps = StepSettle(timings, ::nowMs)

    /** Which SeekDone is the latest seek's, and whether the visit's watched time is stopped for one; one per play. */
    private var seeks = newSeeks()

    /** The seek whose line is waiting for its first `SeekDone` or `Playing` (the diagnostics, spec 20). */
    private var seekLine: SeekLine? = null

    /** The clock time (epoch ms) the pause began while paused; null otherwise. */
    private var pausedAtMs: Long? = null

    /** A resume asked for, whose line waits for the next `Playing`: how long it was held, and when it was asked for. */
    private var resumeLine: ResumeLine? = null

    /**
     * The clock time (epoch ms) the seek back to live went out, while it is in flight (null otherwise): once its own
     * SeekDone comes the viewer is live, and it is waited for [LiveTimings.goLiveWait] at most.
     */
    private var liveSinceMs: Long? = null

    /** The play's events, forwarded by their one collector for the loop to take in order. */
    private val events = Channel<PlayerEvent>(Channel.UNLIMITED)

    /** What is on at the cut, resolving or resolved ahead on media time; null until armed and after a pause or seek. */
    private var catchUpAhead: Deferred<Pending.CatchUp?>? = null

    /** The scope of [run]: the banner's linger runs in it, so it ends with the behind play. */
    private var runScope: CoroutineScope? = null

    /**
     * Runs from the live play's [first] op (or a kept [from] place, Task 12) until the item ends, live, or an exit.
     * Each play's notes are watched while it plays: the first's, then the retry's.
     */
    suspend fun run(
        first: TransportOp?,
        from: Place? = null,
    ): BehindEnd =
        coroutineScope {
            runScope = this
            subscribe()
            try {
                if (from != null) {
                    known(from.mediaMs)
                } else {
                    // Live until now: if the player cannot say where it is, the live position is the place.
                    known(TransportMath.liveMs(block, host.vnowMs()))
                    read()
                }
                var ended = begin(first)
                while (ended == null) {
                    playEnded = false
                    val round = host.watchNotes(play.notes, visiting != null, { _: Round -> playEnded }) { loop(this) }
                    ended =
                        when (round) {
                            is Round.Over -> round.end
                            is Round.Failed -> retry(round.end)
                        }
                }
                ended
            } finally {
                linger?.cancel()
                // Events already in are still told to the visit (a Resumed after the live seek's SeekDone, say).
                unsubscribe()
                // A seek still open is closed at the best-known place, so the visit's watched time runs on after us.
                seeks.close(knownMs)?.let { visiting?.seeked(it) }
                reportUnfinished()
                // Not handed to a catch-up (live again, or an exit): let it go.
                dropCatchUp()
                host.publishTransport(null)
                coroutineContext.cancelChildren()
            }
        }

    /** The paused place now, for the screen stop (Task 12); null unless paused on a program block. */
    fun pausedPlace(): Place? = if (paused && onProgram) Place(host.channel, block, knownMs) else null

    /** The live play's [first] op; non-null when it ends the behind play at once. Then behind: no look-ahead. */
    private suspend fun begin(first: TransportOp?): BehindEnd? {
        // A first op that changed nothing (a step the play cannot take) leaves the viewer live.
        val ended = first?.let { act(it) ?: BehindEnd.Live(play).takeIf { stillLive() } }
        // Behind: the next block's wall-clock resolve is not what follows this item, nor held through a pause.
        if (ended == null) host.dropBoundaryLookAhead()
        return ended
    }

    private fun stillLive() = !paused && steps.target == null && !seeks.inFlight && liveSinceMs == null

    /** The current play, behind, until it ends the behind play ([Round.Over]) or fails ([Round.Failed]). */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Each return is one way the behind play ends.
    @OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout: the media-time hold and the settle.
    private suspend fun loop(within: CoroutineScope): Round {
        val ended = within.async { play.end.firstOrNull() ?: PlaybackEnd.Ended }
        val nextOp = { within.async { host.receive(block.startMs) } }
        var next = nextOp()
        var firedShort = false
        while (true) {
            publish()
            // Media left to the cut, re-read each time round: a hold that fired early (playback slower than the
            // clock) waits again for what is left. None while paused: the picture holds as long as the viewer likes.
            val leftMs = if (paused) null else cutMs - read()
            if (leftMs != null && leftMs <= 0) {
                // Up to two lines for one cut, one per thing lost: an op already taken from the inbox, and a stepped
                // target never sought. Each is a press the viewer made that did nothing.
                lostOp(next, "transport-lost-at-cut")
                dropTarget("transport-lost-at-cut")
                return Round.Over(itemEnd())
            }
            val holdMs = leftMs?.let { holdFor(within, it) }
            // The first wait is the exact media left, so the cut is on time. Only once a wait has fired short is a
            // floor put under it, so a hold that keeps running short near the cut never spins.
            val dueMs = holdMs?.let { if (firedShort) maxOf(it, rearmMinMs) else it }
            val settleMs = steps.dueAtMs?.let { maxOf(0L, it - nowMs()) }
            val liveMs = liveSinceMs?.let { maxOf(0L, it + goLiveWaitMs - nowMs()) }
            val waitMs = listOfNotNull(dueMs, settleMs, liveMs).minOrNull()
            val woke =
                select {
                    ended.onAwait { Wake.End(it) }
                    next.onAwait { Wake.Op(it) }
                    events.onReceive { Wake.Event(it) }
                    if (waitMs != null) onTimeout(waitMs) { timedOut() }
                }
            firedShort = woke is Wake.Due
            when (woke) {
                is Wake.End -> {
                    lostOp(next, "transport-lost-at-end")
                    dropTarget("transport-lost-at-end")
                    playEnded = true
                    val end = woke.end
                    if (end !is PlaybackEnd.Failed) return Round.Over(playEnd())
                    // The retry's own tune drops what is in the inbox; the next play takes ops afresh.
                    next.cancel()
                    return Round.Failed(end)
                }

                is Wake.Op -> {
                    act(woke.op)?.let { return Round.Over(it) }
                    next = nextOp()
                }

                Wake.Settle -> {
                    settle()?.let { return Round.Over(it) }
                }

                // The live seek's own SeekDone: live.
                is Wake.Event -> {
                    if (onEvent(woke.event)) {
                        lostOp(next, "transport-lost-at-live")
                        return Round.Over(BehindEnd.Live(play))
                    }
                }

                // The live seek's SeekDone never came: live at the known place (run closes the seek there).
                Wake.LiveUnmatched -> {
                    lostOp(next, "transport-lost-at-live")
                    dropped("transport-seek-unmatched")
                    return Round.Over(BehindEnd.Live(play))
                }

                // Round again, where the position is read afresh.
                Wake.Due -> {}
            }
        }
    }

    /**
     * Does [op]. Play/Pause follows this play's state; steps add up to the target, which settles in one seek. Non-null
     * when it ends the item: live once the block has ended by the clock is what is on now.
     */
    private suspend fun act(op: TransportOp): BehindEnd? =
        when (op) {
            TransportOp.Toggle -> {
                if (paused) resume() else pause()
                null
            }

            TransportOp.Play -> {
                if (paused) resume()
                null
            }

            TransportOp.Pause -> {
                if (!paused) pause()
                null
            }

            is TransportOp.Step -> {
                step(op)
                null
            }

            TransportOp.StepReleased -> {
                released()
            }

            TransportOp.GoLive -> {
                liveOrItemEnd()
            }
        }

    /** GoLive: back to live while the block is on air; once it has ended by the clock, the item ends (non-null). */
    private suspend fun liveOrItemEnd(): BehindEnd? {
        if (!blockEnded()) {
            goLive()
            return null
        }
        steps.drop()
        return itemEnd()
    }

    private suspend fun pause() {
        dropCatchUp()
        liveSinceMs = null
        seeks.cancelLive()
        read()
        play.control.pause()
        paused = true
        // A resume still waiting for its Playing is over: no picture was attributed to it.
        flushResume()
        pausedAtMs = nowMs()
        linger?.cancel()
        visible = true
        publish()
    }

    private suspend fun resume() {
        read()
        play.control.resume()
        paused = false
        resumed()
        show()
    }

    /**
     * One Back or Ahead press or repeat. It moves only the target: from the pending one, or the position for the first
     * step. Every press or repeat puts the settle off; a repeat steps only when the held pace is due one. Nothing on a
     * play that cannot seek. Paused stays paused.
     */
    private suspend fun step(op: TransportOp.Step) {
        if (!play.control.seekable()) return
        if (!steps.key(op.repeat, op.atMs)) return
        liveSinceMs = null
        seeks.cancelLive()
        val fromMs = steps.target?.toMs ?: read()
        val liveMs = TransportMath.liveMs(block, host.vnowMs())
        val toMs =
            when (op.direction) {
                SeekDirection.Back -> TransportMath.back(fromMs - timings.stepBack.inWholeMilliseconds, inPointMs)
                SeekDirection.Ahead -> TransportMath.ahead(fromMs + aheadMs, liveMs, cutMs)
            }
        steps.aim(toMs, op.direction == SeekDirection.Ahead && TransportMath.reachesLive(toMs, liveMs, cutMs))
        show()
    }

    /** The Back or Ahead key came up: the target settles now, or when [StepSettle] says (non-null: the item ends). */
    private suspend fun released(): BehindEnd? = if (steps.released()) settle() else null

    /**
     * The pending target settles: one seek to it, or back to live when it is the live position and the play runs. A
     * target at live or at the cut once the block has ended by the clock ends the item (non-null): live is what is on
     * now, and no seek is made to reach it.
     */
    private suspend fun settle(): BehindEnd? {
        val (toMs, toLive) = steps.take() ?: return null
        val wentLive = !paused && toLive && goLive()
        val ends = !wentLive && !paused && (toLive || toMs >= cutMs) && blockEnded()
        if (!wentLive && !ends) seekToTarget(toMs, toLive)
        return if (ends) itemEnd() else null
    }

    /** The seek line's direction: live, else which way [toMs] is from [fromMs]. */
    private fun directionOf(
        toLive: Boolean,
        toMs: Long,
        fromMs: Long,
    ) = when {
        toLive -> DIR_LIVE
        toMs < fromMs -> DIR_BACK
        else -> DIR_AHEAD
    }

    /** Not live: the target itself, clamped at the cut while the play is still on its block. */
    private suspend fun seekToTarget(
        toMs: Long,
        toLive: Boolean,
    ) {
        val fromMs = read()
        if (fromMs < cutMs) {
            val to = minOf(toMs, cutMs)
            seek(directionOf(toLive, to, fromMs), to)
            show()
        } else {
            dropped("transport-lost-at-cut")
        }
    }

    /**
     * Back to live while the block is still on air: one seek to the live position, then play; the transport view goes,
     * and the viewer is live once that seek's own SeekDone comes. Once the block has ended, live is what is on now (the
     * caller ends the item): false, and nothing is done.
     */
    private suspend fun goLive(): Boolean {
        val liveMs = TransportMath.liveMs(block, host.vnowMs())
        if (liveMs >= cutMs) return false
        steps.drop()
        linger?.cancel()
        host.publishTransport(null)
        seek(DIR_LIVE, liveMs, live = true)
        liveSinceMs = nowMs()
        if (paused) {
            play.control.resume()
            paused = false
            resumed()
        }
        return true
    }

    /**
     * One absolute seek to [toMs]. The watched span closes at the player's own position, read just before, so both
     * ends of the seek are in the player's coordinates; its SeekDone reports where it landed. A [live] seek is the one
     * whose SeekDone ends the way back to live.
     */
    private suspend fun seek(
        direction: String,
        toMs: Long,
        live: Boolean = false,
    ) {
        dropCatchUp()
        val fromMs = play.control.position()?.inWholeMilliseconds
        visiting?.seeking(fromMs)
        seeks.sent(toMs, live)
        // A seek folded into one pending gets the one answer: the earlier one's line is closed as lost (-1).
        seekLine?.let { host.report(it.lost()) }
        seekLine = SeekLine(direction, toMs, nowMs(), play.timings.route)
        play.control.seekTo(toMs.milliseconds)
        known(toMs)
    }

    /** Shows the transport view, and hides it again [LiveTimings.bannerLinger] later unless paused. */
    private fun show() {
        visible = true
        publish()
        linger?.cancel()
        linger =
            runScope?.launch {
                delay(timings.bannerLinger)
                visible = false
                publish()
            }
    }

    /** What a timed-out wait was for: the live seek's wait first, then the settle, else the media-time hold. */
    private fun timedOut(): Wake =
        when {
            liveSinceMs?.let { nowMs() >= it + goLiveWaitMs } == true -> Wake.LiveUnmatched
            steps.isDue() -> Wake.Settle
            else -> Wake.Due
        }

    private fun nowMs(): Long = host.nowMs()

    /**
     * The item's end through the play's own end (not a failure). With the clock still inside a program's block, the
     * ident holds to its end; past it, straight to the catch-up with no ident. A stand-in piece's end is the item's
     * end.
     */
    private suspend fun playEnd(): BehindEnd {
        val inBlock = onProgram && host.onAir(host.vnowMs())?.now?.startMs == block.startMs
        return if (inBlock) BehindEnd.EndedInBlock.also { dropCatchUp() } else itemEnd()
    }

    /**
     * The play failed: it is reported as the live hold reports it, and its place closes the visit's watched time. While
     * the block's retry is left, it is taken: the item is tuned again at the place. Non-null when the behind play ends
     * instead: live (the retry of a play going back to live), the item's end, the item dead for its block, or an exit.
     * The watched time runs again only once the retry plays, from the place.
     */
    private suspend fun retry(end: PlaybackEnd.Failed): BehindEnd? {
        unsubscribe()
        host.report(TuneReport.HeldFailed(end))
        visiting?.failedPlay(end.positionMs)
        val goingLive = liveSinceMs != null
        val place = placeAfter(end, goingLive)
        // A seek still open (a seek that timed out) is done at the place: nothing is counted until the retry plays.
        seeks.close(place)?.let { visiting?.seeked(it) }
        seeks = newSeeks()
        // The unsubscribe above took the events that came; a seek line still waiting is lost with the play.
        seekLine?.let { host.report(it.lost()) }
        seekLine = null
        // A resume that never showed a picture before the play failed: -1. A pause carries on (the retry is paused
        // again at once, see carryOn), so its held time keeps running and is not reset.
        flushResume()
        liveSinceMs = null
        dropCatchUp()
        return when {
            // Nothing left to play: the play's end, as if it had ended there.
            place >= cutMs -> playEnd()

            !retries.take() -> deadOrOver()

            else -> tuneAgain(place, goingLive)
        }
    }

    /** The block's retry: the item tuned again at [place], under the snow. Non-null as for [retry]. */
    private suspend fun tuneAgain(
        place: Long,
        goingLive: Boolean,
    ): BehindEnd? {
        // The kept place while the retry tunes (a screen stop meanwhile keeps it).
        known(place)
        linger?.cancel()
        host.publishTransport(null)
        return when (retune.at(place, ::carryOn)) {
            is TuneOutcome.Playing -> {
                shown(goingLive)
            }

            is TuneOutcome.Dead -> {
                deadOrOver()
            }

            TuneOutcome.Expired -> {
                BehindEnd.Exit(Exit.SignedOut)
            }

            TuneOutcome.Cancelled -> {
                BehindEnd.Exit(Exit.Stopped)
            }
        }
    }

    /**
     * The item cannot be played again: once its block has ended by the clock, that is its end (catch up, nothing
     * resolved ahead); while the clock is still inside the block, it is dead for the block.
     */
    private fun deadOrOver(): BehindEnd = if (blockEnded()) BehindEnd.ItemEnd(null) else BehindEnd.Dead

    /**
     * Where the viewer was when [end] came: the clock's live position while [goingLive], else the target of a seek
     * still out (a timed-out seek's own position is from before it), else where the player says it failed, else the
     * best known; never before the block's in-point.
     */
    private suspend fun placeAfter(
        end: PlaybackEnd.Failed,
        goingLive: Boolean,
    ): Long {
        val target = seeks.targetMs
        val at =
            when {
                goingLive -> TransportMath.liveMs(block, host.vnowMs())
                target != null -> target
                end.positionMs >= 0 -> end.positionMs
                else -> read()
            }
        return maxOf(at, inPointMs)
    }

    /**
     * The retry plays, before its picture shows: its events are now the ones followed, and a paused viewer is paused
     * again at once, so the picture comes up paused.
     */
    private fun carryOn(outcome: TuneOutcome.Playing) {
        play = outcome
        subscribe()
        if (paused) play.control.pause()
    }

    /** The retry's picture is up: live again when it was [goingLive] (non-null), else behind, paused or playing. */
    private suspend fun shown(goingLive: Boolean): BehindEnd? {
        if (goingLive) return BehindEnd.Live(play)
        if (paused) {
            visible = true
            publish()
        } else {
            read()
            show()
        }
        return null
    }

    /** Forwards the play's events into [events]; made before its first transport call, as they carry no replay. */
    private fun subscribe() {
        val from = play.control.events
        collector = runScope?.launch(start = CoroutineStart.UNDISPATCHED) { from.collect { events.trySend(it) } }
    }

    /** Stops following the play's events; those already in are still told to the visit. */
    private fun unsubscribe() {
        collector?.cancel()
        collector = null
        while (true) onEvent(events.tryReceive().getOrNull() ?: break)
    }

    private fun newSeeks() = SeekTracker(timings.seekLandingSlack.inWholeMilliseconds)

    /** True once the block has ended by the sitting's clock: live is no longer on this item. */
    private fun blockEnded(): Boolean = TransportMath.liveMs(block, host.vnowMs()) >= cutMs

    /** The item ends: catch up, with the handle resolved ahead when it is ready (one still resolving is let go). */
    private suspend fun itemEnd(): BehindEnd.ItemEnd {
        val ahead = catchUpAhead
        catchUpAhead = null
        return BehindEnd.ItemEnd(ahead.finishedOrCancel())
    }

    /**
     * How long the media-time hold waits with [leftMs] of media left to the cut. With [LiveTimings.preResolveLead] or
     * less left, the catch-up is armed (once); until then the hold wakes first at the lead before the cut, to arm it.
     */
    private fun holdFor(
        within: CoroutineScope,
        leftMs: Long,
    ): Long {
        // A stand-in piece resolves nothing ahead: the stand-ins pick what follows it.
        if (!onProgram) return leftMs
        if (leftMs <= leadMs && catchUpAhead == null) armCatchUp(within, leftMs)
        return if (catchUpAhead == null) leftMs - leadMs else leftMs
    }

    /**
     * Resolves ahead what will be on at the cut, [leftMs] of media from now: the block on the channel at the sitting's
     * time then, at its position then. The schedule is read as it is; the tune is made from the position at the cut.
     */
    private fun armCatchUp(
        within: CoroutineScope,
        leftMs: Long,
    ) {
        val atMs = host.vnowMs() + leftMs
        catchUpAhead =
            within.async {
                val onAir = host.onAir(atMs) ?: return@async null
                host.resolveCatchUp(onAir.now, atMs)
            }
    }

    /** Lets the catch-up resolved ahead go (a pause or a seek moves the cut): a handle is ended, a resolve stopped. */
    private suspend fun dropCatchUp() {
        val ahead = catchUpAhead
        catchUpAhead = null
        ahead.finishedOrCancel()?.let { host.discard(it) }
    }

    /**
     * The exact position now. When the player cannot say, the last known place (seeded with the live position the
     * behind play started from, never the in-point), moved on by the clock while playing so the hold still reaches the
     * cut.
     */
    private suspend fun read(): Long {
        val exact = play.control.position()?.inWholeMilliseconds
        val ms = exact ?: if (paused) knownMs else knownMs + (nowMs() - knownAtMs)
        known(ms)
        return ms
    }

    private fun known(ms: Long) {
        knownMs = ms
        knownAtMs = nowMs()
    }

    /** Takes one of the play's events; true when it is the live seek's own SeekDone while going back to live. */
    private fun onEvent(event: PlayerEvent): Boolean {
        when (event) {
            is PlayerEvent.Paused -> {
                val at = event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN }
                at?.let(::known)
                seeks.close(at ?: knownMs)?.let { visiting?.seeked(it) }
                visiting?.paused(at)
                publish()
            }

            // Not a seek's end: Resumed comes as soon as Play is asked for, ahead of a seek's SeekDone (the way back
            // to live seeks, then resumes).
            is PlayerEvent.Resumed -> {
                visiting?.unpaused()
            }

            // A play that plays on is past a seek it began: its SeekDone comes first, so one still open was lost.
            is PlayerEvent.Playing -> {
                seeks.closeOnPlaying(event.position.inWholeMilliseconds)?.let { visiting?.seeked(it) }
                reportPlaying()
            }

            is PlayerEvent.Seeking -> {
                seeks.onSeeking(event.toMs)
            }

            is PlayerEvent.SeekDone -> {
                return seekDone(event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN })
            }

            else -> {}
        }
        return false
    }

    /**
     * A `SeekDone` at [at] (null: not known): reported only when it is the latest seek's. True when that is the live
     * seek while still going back to live.
     */
    private fun seekDone(at: Long?): Boolean {
        val landing = seeks.onSeekDone(at) ?: return false
        reportSeekLine()
        landing.positionMs?.let(::known)
        if (landing.report) visiting?.seeked(landing.atMs)
        publish()
        return landing.live && liveSinceMs != null
    }

    /** The resume is asked for: the pause's held time is told, and its line waits for the next `Playing`. */
    private fun resumed() {
        val since = pausedAtMs ?: return
        pausedAtMs = null
        val now = nowMs()
        flushResume()
        resumeLine = ResumeLine(now - since, now)
    }

    /** A resume still waiting for its `Playing` is told with `resumeMs=-1`: no picture was attributed to it. */
    private fun flushResume() {
        resumeLine?.let { host.report(TuneReport.Pause(it.heldMs, -1)) }
        resumeLine = null
    }

    /** The latest seek's `SeekDone`: its line goes out with the time it took. */
    private fun reportSeekLine() {
        val line = seekLine ?: return
        seekLine = null
        host.report(line.done(nowMs()))
    }

    /** A `Playing`: it ends the wait of a seek line (its `SeekDone` never came) and of the resume line. */
    private fun reportPlaying() {
        reportSeekLine()
        val resume = resumeLine ?: return
        resumeLine = null
        host.report(TuneReport.Pause(resume.heldMs, nowMs() - resume.atMs))
    }

    /** The behind play ends: a seek or resume still waiting is told as lost (-1), a pause still held with its time. */
    private fun reportUnfinished() {
        seekLine?.let { host.report(it.lost()) }
        seekLine = null
        flushResume()
        pausedAtMs?.let { host.report(TuneReport.Pause(nowMs() - it, -1)) }
        pausedAtMs = null
    }

    /** Logs a stepped target that was never sought, because the cut or the play's end came first. */
    private fun dropTarget(reason: String) {
        if (steps.drop() == null) return
        dropped(reason)
    }

    /** A `press dropped` line with [reason] and the state on screen. */
    private fun dropped(reason: String) = host.dropped(reason)

    /** An op already taken from the inbox when the loop ended is dropped with a line, never silently. */
    private fun lostOp(
        taken: Deferred<*>,
        reason: String,
    ) {
        if (taken.isCompleted && !taken.isCancelled) dropped(reason)
    }

    private fun publish() {
        // Going back to live: the view is gone, and stays gone.
        if (liveSinceMs != null) return
        host.publishTransport(
            TransportView(
                paused = paused,
                seeking = seeks.inFlight,
                seekable = play.control.seekable(),
                visible = visible || paused,
                mediaMs = knownMs,
                targetMs = steps.target?.toMs,
                asOfMs = knownAtMs,
                blockStartMs = block.startMs,
                inPointMs = inPointMs,
                cutMs = cutMs,
                driftMs = host.driftMs,
            ),
        )
    }

    /**
     * A seek sent, whose line is told once the player answers. [TuneReport.Seek.toPlayingMs] is -1 when no answer was
     * attributed to it: the play ended or was retried first, or a later seek was folded into it by the player.
     */
    private class SeekLine(
        val direction: String,
        val targetMs: Long,
        val sentAtMs: Long,
        val route: String?,
    ) {
        fun done(nowMs: Long) = TuneReport.Seek(direction, targetMs, nowMs - sentAtMs, route)

        fun lost() = TuneReport.Seek(direction, targetMs, -1, route)
    }

    /** A resume asked for at [atMs] after a pause held [heldMs]. */
    private class ResumeLine(
        val heldMs: Long,
        val atMs: Long,
    )

    /** How one play's [loop] ended: the behind play is over, or the play failed (for [retry]). */
    private sealed interface Round {
        data class Over(
            val end: BehindEnd,
        ) : Round

        data class Failed(
            val end: PlaybackEnd.Failed,
        ) : Round
    }

    /** What woke the behind loop. */
    private sealed interface Wake {
        data class End(
            val end: PlaybackEnd,
        ) : Wake

        data class Op(
            val op: TransportOp,
        ) : Wake

        /** The media-time hold is due: the position is read again. */
        data object Due : Wake

        /** The pending target's settle time came with the key still down. */
        data object Settle : Wake

        /** One of the play's events. */
        data class Event(
            val event: PlayerEvent,
        ) : Wake

        /** The seek back to live was not done within [LiveTimings.goLiveWait]. */
        data object LiveUnmatched : Wake
    }

    private companion object {
        const val DIR_BACK = "back"
        const val DIR_AHEAD = "ahead"
        const val DIR_LIVE = "live"
    }
}
