// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.PlayNote
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.feature.live.report.PlaybackReporter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Why a sitting stopped following its channel. */
internal enum class Exit {
    /** The session ended: the state is [LiveState.SignedOut]. */
    SignedOut,

    /** The tuner was stopped or closed under the machine. */
    Stopped,

    /** The channel stopped airing: pick another. */
    Lost,
}

/** What a tune is for, as its report needs it: the way it is made, the block, and the media position asked for. */
internal class TuneWhat(
    val kind: TuneKind,
    val block: AiringBlock,
    val position: Duration,
)

/** How a play that had started ended, or what the viewer asked of it, as [Sitting.hold] sees it. */
internal sealed interface Held {
    /** The block's end came first: the play is cut there. */
    data object Boundary : Held

    /** The media played to its end inside the block. */
    data object Ended : Held

    /** The play broke; [positionMs] is where the player says it failed, null when it could not say. */
    data class Failed(
        val positionMs: Long?,
    ) : Held

    /** The viewer paused or stepped back on the live play: [op] starts the behind play. */
    data class Transport(
        val op: TransportOp,
    ) : Held

    /** A behind play reached its item's end: catch up (Task 9 fills [catchUp]; null until then). */
    data class CaughtUp(
        val catchUp: Pending.CatchUp?,
    ) : Held

    /** A behind play's file ended early while the clock is still inside its block: hold the ident (Task 9). */
    data object EndedInBlock : Held

    /** The behind play ended the sitting (a retry that signed out or was stopped, Task 10). */
    data class Exited(
        val exit: Exit,
    ) : Held
}

/** What woke the live hold: the play's end, or a transport op it takes. */
private sealed interface Woke {
    data class End(
        val end: PlaybackEnd,
    ) : Woke

    data class Op(
        val op: TransportOp,
    ) : Woke
}

/**
 * One sitting on one channel: a loop over the blocks of its schedule (spec 14). Time is always [vnowMs], the clock plus
 * the sitting's drift: zero while the channel is on schedule, and ahead of the clock after a dead item with little left
 * moved the channel on to the next show early (design goal 6). A new sitting starts at zero, which is the re-anchor.
 *
 * Dead items are handled in `DeadItems.kt`; choosing the channel is [LiveChannel]'s.
 */
@Suppress("LongParameterList") // The machine's ports and clock, one for one.
internal class Sitting(
    val session: Session,
    val channel: ChannelId,
    val schedule: ScheduleSource,
    val tuner: TunePort,
    val clock: Clock,
    val timings: LiveTimings,
    val state: Screen,
    val report: (TuneReport) -> Unit = {},
    /** Set for a sitting a flip started: its first block reports the flip's line (once), then this is null. */
    var flip: FlipTiming? = null,
    /** Resolved ahead while this channel was a neighbour: the first block plays it if it still fits. */
    lead: Pending.Retarget? = null,
    /** The run's playback reporter (spec 16), null where reporting is off, and this sitting's id in it. */
    internal val reporter: PlaybackReporter? = null,
    internal val sittingId: Long = 0,
    /** This sitting's inbox for the viewer's transport presses (spec 20); the run closes it with the sitting. */
    internal val commands: SittingCommands,
    /**
     * Called each time the screen settles: a tune plays, or a card (slate, ident, technical difficulties) is up and
     * waits. Gets the scope of the block that showed it; the run starts its look-ahead there, once per channel.
     */
    internal val onSettled: (CoroutineScope) -> Unit = {},
) {
    /** The start of the block (or stand-in piece) whose picture is up; null before a tune plays or on a card. */
    @Volatile
    internal var heldBlockStartMs: Long? = null

    /** The behind play running on this sitting's picture (spec 20), null while live or on a card. */
    @Volatile
    internal var behind: BehindPlay? = null

    /** How far ahead of the clock this sitting runs (milliseconds, never negative). */
    var driftMs = 0L

    /** [ChannelOnAir.builtUntilMs] as of the last anchor; a drift that reaches it re-anchors (ADR-0017). */
    private var limitMs = Long.MAX_VALUE

    private val nextUp = NextUp(session, tuner, timings, ::vnowMs, lead)
    private var lingering: Job? = null

    /** The wide look for the banner's "Next", kept for the block it was made in: the ident card reuses it. */
    internal var widenedNext: Pair<Long, AiringBlock>? = null

    /** When the last play was cut off at a boundary (or an ident card ended): the start of the next tune's gap. */
    private var cutFrom: Instant? = null

    fun vnowMs(): Long = clock.now().toEpochMilliseconds() + driftMs

    /** Waits until [vnowMs] reaches [ms], however the clock moves meanwhile. */
    suspend fun waitUntil(ms: Long) {
        while (vnowMs() < ms) delay(ms - vnowMs())
    }

    /** Follows the channel from [first] (what was on when it was chosen) until something ends the sitting. */
    suspend fun follow(first: ChannelOnAir): Exit {
        var current: ChannelOnAir? = first
        while (true) {
            val at = Instant.fromEpochMilliseconds(vnowMs())
            val onAir = current ?: schedule.onAir(channel, at, WINDOW)
            current = null
            // Nothing on at the drifted time (the schedule ends there, or the channel is hidden that day) is not the
            // channel lost: look again from the clock before giving it up.
            if (onAir == null && driftMs == 0L) return Exit.Lost
            if (onAir == null || (driftMs > 0 && onAir.now.startMs >= limitMs)) {
                // Back to the clock: nothing is on at the drifted time, or the drift has run past the schedule built
                // ahead (under the snow of a retune).
                driftMs = 0
                continue
            }
            if (driftMs == 0L) limitMs = onAir.builtUntilMs
            val exit = inBlock(onAir)
            if (exit != null) return exit
        }
    }

    /** Ends what the sitting holds: a pre-resolved handle nobody used. The caller stops the tuner. */
    suspend fun release() = nextUp.release()

    private suspend fun inBlock(onAir: ChannelOnAir): Exit? =
        coroutineScope {
            val handle = nextUp.takeFor(onAir.now)
            val ahead = nextUp.prepare(this, onAir)
            try {
                when (val airing = onAir.now.airing) {
                    Airing.Slate -> {
                        reportFlipEnded("slate")
                        holdIdent(this, onAir, onAir.now.endMs)
                    }

                    is Airing.Program -> {
                        // The reporter's visit spans all the block's tries and ends when the block is left for good.
                        val visiting = Visiting(reporter)
                        try {
                            playProgram(this, onAir, airing, handle, visiting)
                        } finally {
                            visiting.leave()
                        }
                    }

                    is Airing.StandIn -> {
                        error("A schedule never holds a stand-in")
                    }
                }
            } finally {
                handle?.let { nextUp.discard(it) }
                nextUp.keep(ahead)
                coroutineContext[Job]?.cancelChildren()
            }
        }

    /** One program's block, every try at it. [visiting] is left by the caller when the block is left for good. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One block's steps: each return is an ending.
    private suspend fun playProgram(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        program: Airing.Program,
        handle: Pending?,
        visiting: Visiting,
    ): Exit? {
        val block = onAir.now
        // A program the library no longer has cannot be tuned: straight to the slate.
        if (program.info == null) return deadItem(scope, onAir, program.itemId)
        var cut = handle
        var failures = 0
        while (true) {
            val at = vnowMs()
            if (at >= block.endMs) return null
            val position = checkNotNull(block.positionAt(at))
            val joins = cut !is Pending.AtBoundary
            val label = if (joins) BannerLabel.TuningIn else BannerLabel.Now
            val joined = if (joins) position.inWholeMilliseconds else null
            val banner = bannerFor(onAir.channel, block, label, joined, driftMs, onAir.nextProgram())
            val use = cut
            cut = null
            val kind =
                when {
                    use is Pending.AtBoundary -> TuneKind.Prepared
                    use is Pending.Retarget -> TuneKind.Neighbour
                    failures > 0 -> TuneKind.Retry
                    else -> TuneKind.Fresh
                }
            val asked = if (use is Pending.AtBoundary) use.start else position
            // Only a cut at a block boundary has no snow: a flip shows it on every route (one decode at a time).
            val snow = use !is Pending.AtBoundary
            val outcome =
                tuneAndShow(scope, banner, snow = snow, what = TuneWhat(kind, block, asked), onAir = onAir) {
                    use?.consumed = true
                    when (use) {
                        is Pending.AtBoundary -> tuner.tune(session, use.handle)
                        is Pending.Retarget -> tuner.tune(session, use.handle, position)
                        is Pending.CatchUp -> tuner.tune(session, use.handle, position)
                        null -> tuner.tune(session, ItemId(program.itemId.value), position)
                    }
                }
            val dead =
                when (outcome) {
                    is TuneOutcome.Playing -> {
                        val from = asked.inWholeMilliseconds
                        when (val held = holdReporting(scope, block, program, outcome, visiting, from)) {
                            // Caught up: the visit is left (by the caller) and the sitting follows what is on now.
                            Held.Boundary, is Held.CaughtUp -> {
                                return null
                            }

                            Held.Ended, Held.EndedInBlock -> {
                                visiting.leave()
                                return holdIdent(scope, onAir, block.endMs)
                            }

                            is Held.Failed -> {
                                ++failures >= 2
                            }

                            is Held.Exited -> {
                                return held.exit
                            }

                            is Held.Transport -> {
                                error("holdProgram runs every transport op")
                            }
                        }
                    }

                    // One retry, from the clock; none after a timeout (it would only cost another 20 s of snow).
                    is TuneOutcome.Dead -> {
                        ++failures >= 2 || outcome.timedOut
                    }

                    TuneOutcome.Expired -> {
                        return expired()
                    }

                    TuneOutcome.Cancelled -> {
                        return Exit.Stopped
                    }
                }
            if (dead) {
                visiting.leave()
                return deadItem(scope, onAir, program.itemId)
            }
        }
    }

    /**
     * Shows the tune (snow with the banner when [snow]; a straight cut otherwise), runs [tune], and on a picture shows
     * the banner over it. The snow shows for at least [LiveTimings.minSnow] from the start of the tune.
     */
    suspend fun tuneAndShow(
        scope: CoroutineScope,
        banner: Banner,
        snow: Boolean,
        what: TuneWhat,
        onAir: ChannelOnAir? = null,
        tune: suspend () -> TuneOutcome,
    ): TuneOutcome {
        // A press made before this cut or retune must not act on what plays after it.
        val stale = commands.drain()
        if (stale > 0) report(TuneReport.PressDropped("transport-at-tune", state.value.javaClass.logName(), stale))
        if (snow) state.value = LiveState.Tuning(banner)
        val began = clock.now()
        // The committed item's own first tune only: a stand-in comes after the slate's hold, which is not hand-over.
        flip?.takeIf { what.kind != TuneKind.StandIn }?.let { timing ->
            val sent = timing.sentAt
            if (sent != null && timing.handoverMs == TuneReport.NOT_SEPARATE) {
                timing.handoverMs = (began - sent).inWholeMilliseconds
            }
        }
        val cutSince = cutFrom
        cutFrom = null
        val outcome = tune()
        if (outcome is TuneOutcome.Playing) {
            reportTune(what, outcome.timings, cutSince)
            reportFlipPlayed(what.kind, began, clock.now(), outcome.timings)
            if (snow) {
                val left = timings.minSnow - (clock.now() - began)
                if (left > Duration.ZERO) delay(minOf(left, timings.minSnow))
            }
            heldBlockStartMs = what.block.startMs
            val playing = showWatching(scope, banner, what.block.airing.itemId())
            // The banner's "Next" came from the blocks already read; with no program among them, look wider, now that
            // the picture is up and the tune no longer waits for it.
            if (onAir != null && playing.banner.next == null) widenNext(scope, onAir, playing)
            onSettled(scope)
        }
        return outcome
    }

    private fun showWatching(
        scope: CoroutineScope,
        banner: Banner,
        itemId: CanonicalId?,
    ): Playing {
        lingering?.cancel()
        // Once the picture plays, "Tuning in" becomes "Now" (R22); the joining line stays. Filling in stays as it is.
        val label = if (banner.label == BannerLabel.TuningIn) BannerLabel.Now else banner.label
        val shown = banner.copy(label = label)
        val playing = Playing(shown, itemId)
        state.watching(LiveState.Watching(shown), playing)
        lingering =
            scope.launch {
                delay(timings.bannerLinger)
                state.hideBanner(playing.token)
            }
        return playing
    }

    /**
     * Waits for how the play ends, or for [untilMs] (the block's end, which cuts a show that runs long). The play's
     * stalls in [notes] are reported as they come (and to the reporter when [reporting]);
     * a failure is reported with its numbers (#392).
     *
     * With a [blockStartMs] (a program's block), it also takes the viewer's transport ops for that block: Play/Pause
     * and Pause return [Held.Transport], as does a back step when [control] can seek; the rest mean nothing while live.
     * Without one (stand-ins, until Task 10) it takes no ops.
     */
    @Suppress("LongParameterList") // The play's three flows and handle, the block's end and its tag.
    suspend fun hold(
        untilMs: Long,
        end: Flow<PlaybackEnd>,
        notes: Flow<PlayNote>,
        reporting: Boolean,
        control: PlayControl,
        blockStartMs: Long?,
    ): Held {
        val left = untilMs - vnowMs()
        val woke =
            if (left <= 0) {
                null
            } else {
                val reportTo = reporter.takeIf { reporting }
                withTimeoutOrNull(left) {
                    // An op leaves the play running: its notes are collected on by the behind play, not drained here.
                    watchNotes(notes, reportTo, drain = { it is Woke.End }) { endOrOp(end, control, blockStartMs) }
                }
            }
        if (woke is Woke.Op) return Held.Transport(woke.op)
        val ended = (woke as? Woke.End)?.end
        if (ended is PlaybackEnd.Failed) report(TuneReport.HeldFailed(ended))
        if (ended == null) waitUntil(untilMs)
        return when {
            ended == null || vnowMs() >= untilMs -> {
                cutFrom = clock.now()
                Held.Boundary
            }

            ended is PlaybackEnd.Failed -> {
                Held.Failed(ended.positionMs.takeIf { it >= 0 })
            }

            else -> {
                Held.Ended
            }
        }
    }

    /** The ident card until [untilMs], with "up next" from [onAir]: a gap, or a show that ended early. Stops first. */
    suspend fun holdIdent(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        untilMs: Long,
    ): Exit? {
        // Worked out before the stop: the card must follow the stop at once, not after a look at the schedule.
        val up = upNext(onAir)
        tuner.stop(session)
        heldBlockStartMs = null
        state.value = LiveState.Ident(up, onAir.tag())
        // A card with no picture also looks ahead, so a flip off it is not a fresh tune (#392).
        onSettled(scope)
        waitUntil(untilMs)
        cutFrom = clock.now()
        return null
    }

    fun expired(): Exit {
        heldBlockStartMs = null
        state.value = LiveState.SignedOut
        return Exit.SignedOut
    }

    internal companion object {
        /** How many following blocks each look at the schedule asks for (the next one is pre-resolved). */
        const val WINDOW = 3
    }
}

/** The play's end (an empty flow: stopped under us, which counts as an end), or the first live op taken. */
private suspend fun Sitting.endOrOp(
    end: Flow<PlaybackEnd>,
    control: PlayControl,
    blockStartMs: Long?,
): Woke =
    coroutineScope {
        val ended = async { Woke.End(end.firstOrNull() ?: PlaybackEnd.Ended) }
        if (blockStartMs == null) return@coroutineScope ended.await()
        val op = async { takeLiveOp(control, blockStartMs) }
        val woke =
            select {
                ended.onAwait { it }
                op.onAwait { it }
            }
        coroutineContext[Job]?.cancelChildren()
        woke
    }

/** The next op for the block that starts the behind play; ops that mean nothing while live are let go. */
private suspend fun Sitting.takeLiveOp(
    control: PlayControl,
    blockStartMs: Long,
): Woke.Op {
    while (true) {
        val op = commands.receive(blockStartMs)
        val takes =
            when (op) {
                TransportOp.Toggle, TransportOp.Pause -> true
                is TransportOp.Step -> op.direction == SeekDirection.Back && control.seekable()
                TransportOp.Play, TransportOp.StepReleased, TransportOp.GoLive -> false
            }
        if (takes) return Woke.Op(op)
    }
}
