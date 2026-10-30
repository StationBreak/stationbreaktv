// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

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

    /** Back at the live position on the same item, still on air: the live hold carries on with [play] (Task 8b). */
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
 * It holds the one subscription to the play's events, which drive the [visiting] reports. [visiting] is null when the
 * play is not reported (or, from Task 10, for a stand-in). [route] is the play's route, for the seek line (Task 11).
 */
@Suppress("LongParameterList") // The sitting, the block's scope, the block and its play, its visit and route.
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

    /** Paused or playing behind: Play/Pause is resolved against this, not against the player's last event. */
    @Volatile
    private var paused = false

    /** The media position last known (a read, or the last Paused or SeekDone event), and the clock time it was so. */
    @Volatile
    private var knownMs = inPointMs
    private var knownAtMs = 0L

    /** Whether the transport view shows: while paused, and for [LiveTimings.bannerLinger] after Play. */
    private var visible = true
    private var linger: Job? = null

    /** Set when the play's own end came (not a cut): its last notes are waited for. */
    private var playEnded = false

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
                sitting.state.transport.value = null
                coroutineContext.cancelChildren()
            }
        }

    /** The paused place now, for the screen stop (Task 12); null unless paused on a program block. */
    fun pausedPlace(): Place? {
        val onProgram = block.airing is Airing.Program
        return if (paused && onProgram) Place(sitting.channel, block, knownMs) else null
    }

    @Suppress("ReturnCount") // Each return is one way the behind play ends.
    @OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout: the media-time hold.
    private suspend fun loop(
        within: CoroutineScope,
        first: TransportOp?,
    ): BehindEnd {
        // Steps arrive in Task 8b: until then a step that started this play changes nothing, so it is still live.
        if (first != null && !act(first)) return BehindEnd.Live(play)
        val ended = within.async { play.end.firstOrNull() ?: PlaybackEnd.Ended }
        val nextOp = { within.async { sitting.commands.receive(block.startMs) } }
        var next = nextOp()
        while (true) {
            publish()
            // Media left to the cut, re-read each time round: a hold that fired early (playback slower than the
            // clock) waits again for what is left. None while paused: the picture holds as long as the viewer likes.
            val leftMs = if (paused) null else cutMs - read()
            if (leftMs != null && leftMs <= 0) {
                sitting.reportLostOp(next, "transport-lost-at-cut")
                return BehindEnd.ItemEnd(null)
            }
            val woke =
                select {
                    ended.onAwait { Wake.End(it) }
                    next.onAwait { Wake.Op(it) }
                    // A floor on the wait, so a hold that runs short near the cut never spins.
                    if (leftMs != null) onTimeout(maxOf(leftMs, MIN_REARM_MS)) { Wake.Due }
                }
            // Wake.Due: round again, where the position is read afresh.
            if (woke is Wake.End) {
                sitting.reportLostOp(next, "transport-lost-at-end")
                return playEnd(woke.end)
            }
            if (woke is Wake.Op) {
                act(woke.op)
                next = nextOp()
            }
        }
    }

    /** Does [op]; false when it means nothing here (a step, until Task 8b). Play/Pause follows this play's state. */
    private suspend fun act(op: TransportOp): Boolean =
        when (op) {
            TransportOp.Toggle -> {
                if (paused) resume() else pause()
                true
            }

            TransportOp.Play -> {
                if (paused) resume()
                true
            }

            TransportOp.Pause -> {
                if (!paused) pause()
                true
            }

            is TransportOp.Step, TransportOp.StepReleased, TransportOp.GoLive -> {
                false
            }
        }

    private suspend fun pause() {
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
        visible = true
        publish()
        linger?.cancel()
        linger =
            scope.launch {
                delay(sitting.timings.bannerLinger)
                visible = false
                publish()
            }
    }

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
        val ms = exact ?: if (paused) knownMs else knownMs + (sitting.clock.now().toEpochMilliseconds() - knownAtMs)
        known(ms)
        return ms
    }

    private fun known(ms: Long) {
        knownMs = ms
        knownAtMs = sitting.clock.now().toEpochMilliseconds()
    }

    private fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Paused -> {
                val at = event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN }
                at?.let(::known)
                visiting?.paused(at)
                publish()
            }

            is PlayerEvent.Resumed -> {
                visiting?.unpaused()
            }

            is PlayerEvent.SeekDone -> {
                event.positionMs.takeIf { it != PlayerEvent.NOT_KNOWN }?.let(::known)
            }

            else -> {}
        }
    }

    private fun publish() {
        sitting.state.transport.value =
            TransportView(
                paused = paused,
                seeking = false,
                seekable = play.control.seekable(),
                visible = visible || paused,
                mediaMs = knownMs,
                targetMs = null,
                asOfMs = knownAtMs,
                blockStartMs = block.startMs,
                inPointMs = inPointMs,
                cutMs = cutMs,
                driftMs = sitting.driftMs,
            )
    }

    private companion object {
        /** The shortest media-time wait: a hold that fires a little early waits at least this long again. */
        const val MIN_REARM_MS = 250L
    }

    /** What woke the behind loop. */
    private sealed interface Wake {
        data class End(
            val end: PlaybackEnd,
        ) : Wake

        data class Op(
            val op: TransportOp,
        ) : Wake

        data object Due : Wake
    }
}
