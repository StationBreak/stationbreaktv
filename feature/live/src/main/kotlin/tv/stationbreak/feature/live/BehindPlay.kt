// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.PlayerEvent
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * A program's play while it is live: [hold] until the play ends or the block does, and when the viewer pauses or
 * steps back, a [BehindPlay] until its item ends, it ends the sitting, or it is back at live (then the live hold
 * carries on with the play it hands back). [visiting] is the block's visit, told of pauses and seeks; null when
 * the play is not reported.
 */
@Suppress("ReturnCount") // Each return is one way the program's play ends; a return to live goes round again.
internal suspend fun Sitting.holdProgram(
    scope: CoroutineScope,
    block: AiringBlock,
    first: TuneOutcome.Playing,
    visiting: Visiting?,
): Held {
    var play = first
    while (true) {
        val held = hold(block.endMs, play.end, play.notes, visiting != null, play.control, block.startMs)
        if (held !is Held.Transport) return held
        val behindPlay = BehindPlay(this, scope, block, play, visiting, play.timings.route)
        behind = behindPlay
        val ended =
            try {
                behindPlay.run(held.op)
            } finally {
                behind = null
            }
        when (ended) {
            is BehindEnd.Live -> play = ended.play
            is BehindEnd.ItemEnd -> return Held.CaughtUp(ended.catchUp)
            BehindEnd.EndedInBlock -> return Held.EndedInBlock
            is BehindEnd.Exit -> return Held.Exited(ended.exit)
        }
    }
}

/**
 * A transport op already taken from the inbox when the play's end or the cut won: it is dropped, with a line
 * (`press dropped reason=<reason>`), so no press vanishes without a trace.
 */
internal fun Sitting.reportLostOp(
    taken: Deferred<*>,
    reason: String,
) {
    if (!taken.isCompleted || taken.isCancelled) return
    report(TuneReport.PressDropped(reason, state.value.javaClass.logName()))
}

/** How a [BehindPlay] ended. */
internal sealed interface BehindEnd {
    /** The item reached its end (the cut, or the file's end): catch up with what is on now ([catchUp], Task 9). */
    data class ItemEnd(
        val catchUp: Pending.CatchUp?,
    ) : BehindEnd

    /** The file ended early and the clock is still inside the block (Task 9). */
    data object EndedInBlock : BehindEnd

    /** Back at the live position on the same item, still on air: the live hold carries on with [play]. */
    data class Live(
        val play: TuneOutcome.Playing,
    ) : BehindEnd

    /** The sitting ends: a retry signed out or was stopped (Task 10). */
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
 * It owns the seek target: Back and Ahead steps add up there, clamped at the block's in-point and at the lesser of the
 * live position and the cut, and one absolute seek settles them as a flip does: never while the key is down, on its
 * release (no sooner than [LiveTimings.flipSettle] after the last press or repeat), or after
 * [LiveTimings.flipReleaseFallback] when no release comes. A settled target at the live position, while the block is
 * still on air, is live again.
 *
 * Its state is written by the loop and by the event collector, both on the sitting's dispatcher; the fields the
 * collector reads or writes are `@Volatile` as well, so a multi-threaded dispatcher still sees each write.
 *
 * It holds the one subscription to the play's events, which drive the [visiting] reports. [visiting] is null when the
 * play is not reported (or, from Task 10, for a stand-in). [route] is the play's route, for the seek line (Task 11).
 */
@Suppress("LongParameterList", "TooManyFunctions") // The sitting, the block's scope, the block and its play, its visit
// and route; one function per transport op and state change.
internal class BehindPlay(
    private val sitting: Sitting,
    private val scope: CoroutineScope,
    private val block: AiringBlock,
    private val play: TuneOutcome.Playing,
    private val visiting: Visiting?,
    val route: String?,
) {
    private val inPointMs = TransportMath.inPointMs(block)
    private val cutMs = TransportMath.cutMs(block)
    private val timings = sitting.timings
    private val aheadMs = timings.stepAhead.inWholeMilliseconds

    /** Paused or playing behind: Play/Pause is resolved against this, not against the player's last event. */
    @Volatile
    private var paused = false

    /** The media position last known (a read, or the last Paused or SeekDone event), and the clock time it was so. */
    @Volatile
    private var knownMs = inPointMs

    @Volatile
    private var knownAtMs = 0L

    /** Whether the transport view shows: while paused, and for [LiveTimings.bannerLinger] after Play or a step. */
    @Volatile
    private var visible = true
    private var linger: Job? = null

    /** Set when the play's own end came (not a cut): its last notes are waited for. */
    private var playEnded = false

    // DESIGN(#383): placeholder, held-step pace. A held Back or Ahead steps at the flip's pace: at 4 steps a second,
    // held Back takes about 45 s to go back 30 minutes, so how a viewer reaches the start of a long item (start-over
    // reachability) is for the design partner.
    private val pace = HeldFlip(timings.flipRepeatDelay, timings.flipRepeatEvery)

    /** Where the steps so far add up to, until it settles; null when no step is pending. */
    @Volatile
    private var targetMs: Long? = null

    /** True when the pending target is the live position: an Ahead step's clamp hit it. */
    private var targetIsLive = false

    /**
     * The clock time (epoch ms) the pending target settles at: the release fallback while the key is down, then the
     * later of the last press or repeat plus [LiveTimings.flipSettle], and the release (#430, R55).
     */
    private var settleAtMs: Long? = null

    /** True from a Back or Ahead press until its release: nothing settles at [LiveTimings.flipSettle] meanwhile. */
    private var keyDown = false

    /** The clock time (epoch ms) of the last Back or Ahead press or repeat. */
    private var lastKeyAtMs = 0L

    /** The target of the latest seek sent and not yet done; null when none is in flight. */
    @Volatile
    private var seekingToMs: Long? = null

    /** True once the player's `Seeking` for [seekingToMs] has come: the next `SeekDone` is that seek's. */
    @Volatile
    private var seekStarted = false

    /** The number of the latest seek sent (1, 2, …), and of the seek back to live while one is under way. */
    @Volatile
    private var seekSeq = 0L

    @Volatile
    private var liveSeq: Long? = null

    /**
     * True from a seek's `seeking` report until its `seeked` one: the visit's watched time is stopped meanwhile, so a
     * seek whose `SeekDone` never comes is closed at the best-known place by the next `Paused` or `Playing`, or when
     * the behind play ends, never left open.
     */
    @Volatile
    private var seekOpen = false

    /** Set while the seek back to live is in flight: once it is done, the viewer is live. */
    @Volatile
    private var goingLive: CompletableDeferred<Unit>? = null

    /** The clock time (epoch ms) the seek back to live went out: it is waited for [GO_LIVE_WAIT_MS] at most. */
    private var liveSinceMs = 0L

    /** Runs from the live play's [first] op (or a kept [from] place, Task 12) until the item ends, live, or an exit. */
    suspend fun run(
        first: TransportOp?,
        from: Place? = null,
    ): BehindEnd =
        coroutineScope {
            // The one subscription, made before the first transport call: the events carry no replay.
            launch(start = CoroutineStart.UNDISPATCHED) { play.control.events.collect(::onEvent) }
            try {
                if (from != null) {
                    known(from.mediaMs)
                } else {
                    // Live until now: if the player cannot say where it is, the live position is the place.
                    known(TransportMath.liveMs(block, sitting.vnowMs()))
                    read()
                }
                sitting.watchNotes(play.notes, sitting.reporter.takeIf { visiting != null }, { playEnded }) {
                    loop(this, first)
                }
            } finally {
                linger?.cancel()
                // A seek still open is closed at the best-known place, so the visit's watched time runs on after us.
                closeSeek(null)
                sitting.state.transport.value = null
                coroutineContext.cancelChildren()
            }
        }

    /** The paused place now, for the screen stop (Task 12); null unless paused on a program block. */
    fun pausedPlace(): Place? {
        val onProgram = block.airing is Airing.Program
        return if (paused && onProgram) Place(sitting.channel, block, knownMs) else null
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Each return is one way the behind play ends.
    @OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout: the media-time hold and the settle.
    private suspend fun loop(
        within: CoroutineScope,
        first: TransportOp?,
    ): BehindEnd {
        if (first != null) {
            act(first)
            // A first op that changed nothing (a step the play cannot take) leaves the viewer live.
            val still = !paused && targetMs == null && seekingToMs == null && goingLive == null
            if (still) return BehindEnd.Live(play)
        }
        val ended = within.async { play.end.firstOrNull() ?: PlaybackEnd.Ended }
        val nextOp = { within.async { sitting.commands.receive(block.startMs) } }
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
                sitting.reportLostOp(next, "transport-lost-at-cut")
                dropTarget("transport-lost-at-cut")
                return BehindEnd.ItemEnd(null)
            }
            // The first wait is the exact media left, so the cut is on time. Only once a wait has fired short is a
            // floor put under it, so a hold that keeps running short near the cut never spins.
            val dueMs = leftMs?.let { if (firedShort) maxOf(it, MIN_REARM_MS) else it }
            val settleMs = settleAtMs?.let { maxOf(0L, it - nowMs()) }
            val landed = goingLive
            val liveMs = landed?.let { maxOf(0L, liveSinceMs + GO_LIVE_WAIT_MS - nowMs()) }
            val waitMs = listOfNotNull(dueMs, settleMs, liveMs).minOrNull()
            val woke =
                select {
                    ended.onAwait { Wake.End(it) }
                    next.onAwait { Wake.Op(it) }
                    landed?.onAwait { Wake.Landed }
                    if (waitMs != null) onTimeout(waitMs) { timedOut() }
                }
            firedShort = woke is Wake.Due
            when (woke) {
                is Wake.End -> {
                    sitting.reportLostOp(next, "transport-lost-at-end")
                    dropTarget("transport-lost-at-end")
                    return playEnd(woke.end)
                }

                is Wake.Op -> {
                    act(woke.op)
                    next = nextOp()
                }

                Wake.Settle -> {
                    settle()
                }

                Wake.Landed -> {
                    sitting.reportLostOp(next, "transport-lost-at-live")
                    return BehindEnd.Live(play)
                }

                // The live seek's SeekDone never came: live at the known place (run closes the seek there).
                Wake.LiveUnmatched -> {
                    sitting.reportLostOp(next, "transport-lost-at-live")
                    dropped("transport-seek-unmatched")
                    return BehindEnd.Live(play)
                }

                // Round again, where the position is read afresh.
                Wake.Due -> {}
            }
        }
    }

    /** Does [op]. Play/Pause follows this play's state; steps add up to the target, which settles in one seek. */
    private suspend fun act(op: TransportOp) {
        when (op) {
            TransportOp.Toggle -> if (paused) resume() else pause()
            TransportOp.Play -> if (paused) resume()
            TransportOp.Pause -> if (!paused) pause()
            is TransportOp.Step -> step(op)
            TransportOp.StepReleased -> released()
            TransportOp.GoLive -> goLive()
        }
    }

    private suspend fun pause() {
        goingLive = null
        read()
        play.control.pause()
        paused = true
        linger?.cancel()
        visible = true
        publish()
    }

    private suspend fun resume() {
        read()
        play.control.resume()
        paused = false
        show()
    }

    /**
     * One Back or Ahead press or repeat. It moves only the target: from the pending one, or the position for the first
     * step. Every press or repeat puts the settle off; a repeat steps only when the held pace is due one. Nothing on a
     * play that cannot seek. Paused stays paused.
     */
    private suspend fun step(op: TransportOp.Step) {
        if (!play.control.seekable()) return
        // While the key is down only the release fallback settles, so a held key seeks once, when it comes up, even
        // where the remote's first auto-repeat comes after flipSettle (480 ms on older hardware).
        keyDown = true
        lastKeyAtMs = nowMs()
        settleAtMs = lastKeyAtMs + timings.flipReleaseFallback.inWholeMilliseconds
        val atMs = op.atMs ?: nowMs()
        if (op.repeat) {
            if (!pace.repeatSteps(atMs)) return
        } else {
            pace.press(atMs)
        }
        goingLive = null
        val fromMs = targetMs ?: read()
        val liveMs = TransportMath.liveMs(block, sitting.vnowMs())
        val toMs =
            when (op.direction) {
                SeekDirection.Back -> TransportMath.back(fromMs - timings.stepBack.inWholeMilliseconds, inPointMs)
                SeekDirection.Ahead -> TransportMath.ahead(fromMs + aheadMs, liveMs, cutMs)
            }
        targetMs = toMs
        targetIsLive = op.direction == SeekDirection.Ahead && TransportMath.reachesLive(toMs, liveMs, cutMs)
        show()
    }

    /**
     * The Back or Ahead key came up (R55, as for a flip): the target settles at the later of the last press or repeat
     * plus the settle, and now. A tap settles at its press plus the settle, so quick taps add up to one seek.
     */
    private suspend fun released() {
        if (!keyDown) return
        keyDown = false
        settleAtMs = maxOf(lastKeyAtMs + timings.flipSettle.inWholeMilliseconds, nowMs())
        if (settleDue()) settle()
    }

    /** The pending target settles: one seek to it, or back to live when it is the live position and the play runs. */
    private suspend fun settle() {
        val toMs = targetMs
        val toLive = targetIsLive
        targetMs = null
        targetIsLive = false
        settleAtMs = null
        if (toMs == null) return
        when {
            toLive && !paused && goLive() -> {}

            // The block ended before the settle (live is the next item, Task 9): the clamp holds while the play is
            // still on its block.
            read() < cutMs -> {
                seek(minOf(toMs, cutMs))
                show()
            }

            else -> {
                dropped("transport-lost-at-cut")
            }
        }
    }

    /**
     * Back to live while the block is still on air: one seek to the live position, then play; the transport view goes,
     * and the viewer is live once that seek's own SeekDone comes. Once the block has ended, live is the next item (Task
     * 9): false, and nothing is done.
     */
    private suspend fun goLive(): Boolean {
        val liveMs = TransportMath.liveMs(block, sitting.vnowMs())
        if (liveMs >= cutMs) return false
        targetMs = null
        targetIsLive = false
        settleAtMs = null
        linger?.cancel()
        liveSeq = null
        goingLive = CompletableDeferred()
        sitting.state.transport.value = null
        seek(liveMs, live = true)
        liveSinceMs = nowMs()
        if (paused) {
            play.control.resume()
            paused = false
        }
        return true
    }

    /**
     * One absolute seek to [toMs]. The watched span closes at the player's own position, read just before, so both
     * ends of the seek are in the player's coordinates; its SeekDone reports where it landed. The read may suspend, so
     * this seek is only made the one a SeekDone is matched to after it: one that comes meanwhile is the earlier seek's.
     * A [live] seek is the one whose SeekDone ends the way back to live.
     */
    private suspend fun seek(
        toMs: Long,
        live: Boolean = false,
    ) {
        val fromMs = play.control.position()?.inWholeMilliseconds
        visiting?.seeking(fromMs)
        seekOpen = true
        val seq = seekSeq + 1
        seekingToMs = toMs
        seekStarted = false
        seekSeq = seq
        if (live) liveSeq = seq
        play.control.seekTo(toMs.milliseconds)
        known(toMs)
    }

    /** Shows the transport view, and hides it again [LiveTimings.bannerLinger] later unless paused. */
    private fun show() {
        visible = true
        publish()
        linger?.cancel()
        linger =
            scope.launch {
                delay(timings.bannerLinger)
                visible = false
                publish()
            }
    }

    private fun settleDue(): Boolean = settleAtMs?.let { nowMs() >= it } == true

    /** What a timed-out wait was for: the live seek's wait first, then the settle, else the media-time hold. */
    private fun timedOut(): Wake =
        when {
            goingLive != null && nowMs() >= liveSinceMs + GO_LIVE_WAIT_MS -> Wake.LiveUnmatched
            settleDue() -> Wake.Settle
            else -> Wake.Due
        }

    private fun nowMs(): Long = sitting.clock.now().toEpochMilliseconds()

    /**
     * The item's end through the play's own end. A failure is reported as the live hold reports it, and its place
     * closes the visit's watched time; Task 9 tells an early end inside the block apart, and Task 10 retries a failure.
     */
    private fun playEnd(end: PlaybackEnd): BehindEnd {
        playEnded = true
        if (end is PlaybackEnd.Failed) {
            sitting.report(TuneReport.HeldFailed(end))
            visiting?.failedPlay(end.positionMs)
        }
        return BehindEnd.ItemEnd(null)
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

    private fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Paused -> {
                val at = event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN }
                at?.let(::known)
                closeSeek(at)
                visiting?.paused(at)
                publish()
            }

            // Not a seek's end: Resumed comes as soon as Play is asked for, ahead of a seek's SeekDone (the way back
            // to live seeks, then resumes).
            is PlayerEvent.Resumed -> {
                visiting?.unpaused()
            }

            // A play that plays on is past any seek: its SeekDone comes before Playing, so one still open was lost.
            is PlayerEvent.Playing -> {
                closeSeek(event.position.inWholeMilliseconds)
            }

            is PlayerEvent.Seeking -> {
                if (event.toMs == seekingToMs) seekStarted = true
            }

            is PlayerEvent.SeekDone -> {
                seekDone(event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN })
            }

            else -> {}
        }
    }

    /**
     * A `SeekDone` at [at] (null: not known). It is the latest seek's when that seek's `Seeking` came before it, or,
     * with no `Seeking` seen (the player folded the seek into one already pending), when it lands within
     * [LANDING_SLACK_MS] of the target. Any other is an earlier seek's, still buffered when the latest went out: it is
     * dropped, so it can neither report the landing nor end the way back to live early. Only the latest seek's is
     * reported, where it landed (else where it was sent).
     */
    private fun seekDone(at: Long?) {
        val toMs = seekingToMs ?: return
        // A SeekDone with no position can only be matched by its Seeking: without one, an earlier seek's is as likely.
        val mine = seekStarted || (at != null && abs(at - toMs) <= LANDING_SLACK_MS)
        if (!mine) return
        at?.let(::known)
        if (seekOpen) visiting?.seeked(at ?: toMs)
        seekOpen = false
        seekingToMs = null
        seekStarted = false
        if (seekSeq == liveSeq) goingLive?.complete(Unit)
        publish()
    }

    /**
     * Closes a seek whose SeekDone has not come, at [atMs] when known, else the best-known place: the visit's watched
     * time never stays stopped. Matching is left as it is, so the seek's own SeekDone can still end the way to live.
     */
    private fun closeSeek(atMs: Long?) {
        if (!seekOpen) return
        seekOpen = false
        visiting?.seeked(atMs ?: knownMs)
    }

    /** Logs a stepped target that was never sought, because the cut or the play's end came first. */
    private fun dropTarget(reason: String) {
        if (targetMs == null) return
        targetMs = null
        dropped(reason)
    }

    /** A `press dropped` line with [reason] and the state on screen. */
    private fun dropped(reason: String) {
        sitting.report(
            TuneReport.PressDropped(
                reason,
                sitting.state.value.javaClass
                    .logName(),
            ),
        )
    }

    private fun publish() {
        // Going back to live: the view is gone, and stays gone.
        if (goingLive != null) return
        sitting.state.transport.value =
            TransportView(
                paused = paused,
                seeking = seekingToMs != null,
                seekable = play.control.seekable(),
                visible = visible || paused,
                mediaMs = knownMs,
                targetMs = targetMs,
                asOfMs = knownAtMs,
                blockStartMs = block.startMs,
                inPointMs = inPointMs,
                cutMs = cutMs,
                driftMs = sitting.driftMs,
            )
    }

    private companion object {
        /** The shortest re-armed media-time wait: a hold that fired short waits at least this long again. */
        const val MIN_REARM_MS = 250L

        /** How far from its target a `SeekDone` with no `Seeking` seen may land and still be that seek's. */
        const val LANDING_SLACK_MS = 2_000L

        /**
         * The longest wait for the seek back to live to be done, as the player's own seek watchdog
         * (`TuneController.PLAYING_TIMEOUT`, 20 s): past it, live at the known place.
         */
        const val GO_LIVE_WAIT_MS = 20_000L
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

        /** The seek back to live is done. */
        data object Landed : Wake

        /** The seek back to live was not done within [GO_LIVE_WAIT_MS]. */
        data object LiveUnmatched : Wake
    }
}
