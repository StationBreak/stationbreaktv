// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
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
import kotlin.time.Duration.Companion.milliseconds
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

/** How the live hold ([Sitting.hold]) ended: how the play ended, or a transport op that starts a behind play. */
internal sealed interface LiveHold {
    /** The block's end came first: the play is cut there. */
    data object Boundary : LiveHold

    /** The media played to its end inside the block. */
    data object Ended : LiveHold

    /** The play broke; [positionMs] is where the player says it failed, null when it could not say. */
    data class Failed(
        val positionMs: Long?,
    ) : LiveHold

    /** The viewer paused or stepped back on the live play: [op] starts the behind play. */
    data class Transport(
        val op: TransportOp,
    ) : LiveHold
}

/** How a program's play ended ([Sitting.holdProgram]): live or behind, every transport op already run. */
internal sealed interface Held {
    /** The block's end came first: the play is cut there. */
    data object Boundary : Held

    /** The media played to its end inside the block. */
    data object Ended : Held

    /** The play broke; [positionMs] is where the player says it failed, null when it could not say. */
    data class Failed(
        val positionMs: Long?,
    ) : Held

    /** A behind play reached its item's end: catch up with what is on now, from [catchUp] when it was resolved. */
    data class CaughtUp(
        val catchUp: Pending.CatchUp?,
    ) : Held

    /** A behind play's file ended early while the clock is still inside its block: hold the ident. */
    data object EndedInBlock : Held

    /** The block's one retry is used and the item is still dead, with the clock inside its block: the dead item. */
    data object Dead : Held

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
@Suppress("LongParameterList", "TooManyFunctions") // Ports one for one; a function per step, plus 2 catch-up wrappers.
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
    /** Told once a kept place this sitting was given is settled: its behind play starts, or the place is dropped. */
    internal val resumeSettled: () -> Unit = {},
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

    /** This block's wall-clock look-ahead at the next block: dropped while behind, started again at live. */
    internal val boundary = BoundaryLookAhead(nextUp)

    /** The wide look for the banner's "Next", kept for the block it was made in: the ident card reuses it. */
    internal var widenedNext: Pair<Long, AiringBlock>? = null

    /** When the last play was cut off at a boundary (or an ident card ended): the start of the next tune's gap. */
    private var cutFrom: Instant? = null

    fun vnowMs(): Long = clock.now().toEpochMilliseconds() + driftMs

    /** Waits until [vnowMs] reaches [ms], however the clock moves meanwhile. */
    suspend fun waitUntil(ms: Long) {
        while (vnowMs() < ms) delay(ms - vnowMs())
    }

    /**
     * Follows the channel from [first] (what was on when it was chosen) until something ends the sitting. A kept paused
     * place to [resume] at, when the return rules keep it ([returnTo]), comes first ([resumeBlock]); then what is on.
     */
    suspend fun follow(
        first: ChannelOnAir,
        resume: Place? = null,
    ): Exit {
        val place = resume?.let { returnTo(it, clock.now().toEpochMilliseconds()) }
        if (resume != null && place == null) resumeSettled()
        if (place == null) return followFrom(first)
        // After a kept place, what is on now is looked at afresh: the item ended, or it went live and its block did.
        return resumeBlock(first, place) ?: followFrom(null)
    }

    /** The block loop, from [first] (what was on when the channel was chosen), else from what is on at its time. */
    private suspend fun followFrom(first: ChannelOnAir?): Exit {
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

    /** A behind play's catch-up, resolved ahead for [block] at its position at [atMs] ([NextUp.resolveCatchUp]). */
    internal suspend fun resolveCatchUp(
        block: AiringBlock,
        atMs: Long,
    ): Pending.CatchUp? = nextUp.resolveCatchUp(block, atMs)

    /** Ends a behind play's catch-up handle that will not be played. */
    internal suspend fun discardCatchUp(handle: Pending.CatchUp) = nextUp.discard(handle)

    private suspend fun inBlock(onAir: ChannelOnAir): Exit? =
        coroutineScope {
            val handle = nextUp.takeFor(onAir.now)
            boundary.start(this, onAir)
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
                boundary.keep()
                coroutineContext[Job]?.cancelChildren()
            }
        }

    /**
     * A return to a kept paused [place] (spec 20, ADR-0021 5): the item of its block tuned at the place under the snow
     * and paused on the first frame, before the picture shows, then a behind play from there, still paused. Nothing
     * plays on by itself: Play is the viewer's. A block still on air and one ended by the clock go the same way. No
     * boundary look-ahead starts (the viewer is behind); it starts once back at live, or on the ident. [onAir] is
     * what is on now. Null when [follow] goes on with what is on: the item ended (caught up), or the play went live and
     * reached its boundary or broke.
     */
    internal suspend fun resumeBlock(
        onAir: ChannelOnAir,
        place: Place,
    ): Exit? =
        coroutineScope {
            val airing = place.block.airing
            if (airing is Airing.Program && onAir.now.sameAs(place.block)) boundary.startDropped(this, onAir)
            // A stand-in is never reported.
            val visiting = Visiting(reporter.takeIf { airing is Airing.Program })
            try {
                when (airing) {
                    is Airing.Program -> resumeProgram(this, onAir, airing, place, visiting)
                    is Airing.StandIn -> resumeStandIn(this, onAir, airing, place)
                    Airing.Slate -> null.also { resumeSettled() }
                }
            } finally {
                visiting.leave()
                boundary.keep()
                coroutineContext[Job]?.cancelChildren()
            }
        }

    /**
     * A kept place's [item] tuned at the place under the snow, with [label]'s banner, and paused on the first frame,
     * before the picture shows (C14). A dead tune is not retried and falls back to nothing (no slate, no stand-in, no
     * live): the banner over no picture, with the place paused; the block's one retry waits for Play.
     */
    @Suppress("LongParameterList") // The block's scope, what is on, the place, its item, and how it is tuned.
    internal suspend fun tunePaused(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        place: Place,
        item: ItemId,
        label: BannerLabel,
        kind: TuneKind,
    ): TuneOutcome {
        val block = place.block
        val position = place.mediaMs.milliseconds
        val banner = bannerFor(onAir.channel, block, label, place.mediaMs, driftMs, onAir.nextProgram())
        val outcome =
            tuneAndShow(scope, banner, true, TuneWhat(kind, block, position), onAir, { it.control.pause() }) {
                tuner.tune(session, item, position)
            }
        if (outcome is TuneOutcome.Dead) {
            heldBlockStartMs = block.startMs
            showWatching(scope, banner, block.airing.itemId())
            onSettled(scope)
        }
        return outcome
    }

    /** The play a [tunePaused] outcome hands the behind play: none at all for a dead tune; null when it ended. */
    internal fun pausedPlay(outcome: TuneOutcome): TuneOutcome.Playing? =
        (outcome as? TuneOutcome.Playing) ?: NO_PLAY.takeIf { outcome is TuneOutcome.Dead }

    /** A kept place's tune that signed out or was stopped: the place is settled and the sitting ends. */
    internal fun resumeEnded(outcome: TuneOutcome): Exit {
        resumeSettled()
        return if (outcome == TuneOutcome.Expired) expired() else Exit.Stopped
    }

    /** [resumeBlock] for a program's block: the paused tune, then its behind play, reported as the same airing. */
    private suspend fun resumeProgram(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        program: Airing.Program,
        place: Place,
        visiting: Visiting,
    ): Exit? {
        val item = ItemId(program.itemId.value)
        val outcome = tunePaused(scope, onAir, place, item, BannerLabel.TuningIn, TuneKind.Fresh)
        val play = pausedPlay(outcome) ?: return resumeEnded(outcome)
        val retune = retuneAt(scope, onAir, place.block, item, BannerLabel.TuningIn)
        val dead = outcome is TuneOutcome.Dead
        return resumeHeld(scope, onAir, program, play, place, visiting, retune, BlockRetries(), dead)
    }

    /** The resumed play, from the kept place (paused), until its item ends, it goes live and ends, or an exit. */
    @Suppress("LongParameterList") // The block's scope and what is on, the program, its play, place, visit and retry.
    private suspend fun resumeHeld(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        program: Airing.Program,
        play: TuneOutcome.Playing,
        place: Place,
        visiting: Visiting,
        retune: Retune,
        retries: BlockRetries,
        dead: Boolean,
    ): Exit? {
        val block = place.block
        // The behind play starts at once from here, with no suspension first: it holds the kept place from now on.
        resumeSettled()
        val held = holdReporting(block, program, play, visiting, place.mediaMs, retune, retries, place, dead)
        return when (held) {
            // Live again and the block's end came, or the live play broke: what is on now, by the usual loop.
            Held.Boundary, is Held.Failed -> {
                null
            }

            is Held.CaughtUp -> {
                held.catchUp?.let { nextUp.catchUp(it) }
                cutFrom = clock.now()
                null
            }

            // The file ended with the clock still inside its block: the ident to the block's end, as on the live path.
            Held.Ended, Held.EndedInBlock -> {
                rearmBoundaryLookAhead()
                visiting.leave()
                holdIdent(scope, onAir, block.endMs)
            }

            Held.Dead -> {
                visiting.leave()
                deadItem(scope, onAir, program.itemId)
            }

            is Held.Exited -> {
                held.exit
            }
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
        // One retry for the block, whichever play (live or behind) takes it.
        val retries = BlockRetries()
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
                    use is Pending.CatchUp -> TuneKind.CatchUp
                    retries.taken -> TuneKind.Retry
                    else -> TuneKind.Fresh
                }
            val asked = if (use is Pending.AtBoundary) use.start else position
            // Only a cut at a block boundary, or a behind play's catch-up on the same channel, has no snow: a flip
            // shows it on every route (one decode at a time).
            val snow = use !is Pending.AtBoundary && use !is Pending.CatchUp
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
                        val item = ItemId(program.itemId.value)
                        val retune = retuneAt(scope, onAir, block, item, BannerLabel.TuningIn)
                        when (val held = holdReporting(block, program, outcome, visiting, from, retune, retries)) {
                            Held.Boundary -> {
                                return null
                            }

                            // Caught up: the visit is left (by the caller) and the sitting follows what is on now, with
                            // the handle resolved for it.
                            is Held.CaughtUp -> {
                                held.catchUp?.let { nextUp.catchUp(it) }
                                // The item's end is the cut: the catch-up's tune reports its gap from here.
                                cutFrom = clock.now()
                                return null
                            }

                            Held.Ended -> {
                                visiting.leave()
                                return holdIdent(scope, onAir, block.endMs)
                            }

                            // Behind, the file ended early with the clock still in its block: the ident holds to the
                            // block's end, as on the live path, and the next block is resolved ahead again.
                            Held.EndedInBlock -> {
                                rearmBoundaryLookAhead()
                                visiting.leave()
                                return holdIdent(scope, onAir, block.endMs)
                            }

                            is Held.Failed -> {
                                !retries.take()
                            }

                            Held.Dead -> {
                                true
                            }

                            is Held.Exited -> {
                                return held.exit
                            }
                        }
                    }

                    // One retry, from the clock; none after a timeout (it would only cost another 20 s of snow).
                    is TuneOutcome.Dead -> {
                        !retries.take() || outcome.timedOut
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
     * the banner over it. The snow shows for at least [LiveTimings.minSnow] from the start of the tune. [onPlaying] is
     * told of a play as soon as the tune answers, before the picture shows.
     */
    suspend fun tuneAndShow(
        scope: CoroutineScope,
        banner: Banner,
        snow: Boolean,
        what: TuneWhat,
        onAir: ChannelOnAir? = null,
        onPlaying: (TuneOutcome.Playing) -> Unit = {},
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
            // Before the picture shows (a retry that must be paused again, say).
            onPlaying(outcome)
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
     * It also takes the viewer's transport ops for the block (or stand-in piece) that started at [blockStartMs]:
     * Play/Pause and Pause return [LiveHold.Transport], as does a back step when [control] can seek; the rest mean
     * nothing while live.
     */
    @Suppress("LongParameterList") // The play's three flows and handle, the block's end and its tag.
    suspend fun hold(
        untilMs: Long,
        end: Flow<PlaybackEnd>,
        notes: Flow<PlayNote>,
        reporting: Boolean,
        control: PlayControl,
        blockStartMs: Long,
    ): LiveHold {
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
        if (woke is Woke.Op) return LiveHold.Transport(woke.op)
        val ended = (woke as? Woke.End)?.end
        if (ended is PlaybackEnd.Failed) report(TuneReport.HeldFailed(ended))
        if (ended == null) waitUntil(untilMs)
        return when {
            ended == null || vnowMs() >= untilMs -> {
                cutFrom = clock.now()
                LiveHold.Boundary
            }

            ended is PlaybackEnd.Failed -> {
                LiveHold.Failed(ended.positionMs.takeIf { it >= 0 })
            }

            else -> {
                LiveHold.Ended
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

        /** A kept place's dead tune: no play at all, held paused until the viewer presses Play. */
        internal val NO_PLAY = TuneOutcome.Playing(flow { awaitCancellation() }, PlayControl.None)
    }
}

/**
 * Cancels this block's wall-clock look-ahead at the next block and discards its handle: a behind play's next item is
 * what is on at its cut, not the next block, and a resolve or encode is not held through a pause (spec 20).
 */
internal suspend fun Sitting.dropBoundaryLookAhead() = boundary.drop()

/** Starts this block's look-ahead again after a drop, once the play is live (or on the ident) again; true if it did. */
internal fun Sitting.rearmBoundaryLookAhead() = boundary.rearm()

/**
 * A transport op already taken from the inbox when the play's end or the cut won: it is dropped, with a line
 * (`press dropped reason=<reason>`), so no press vanishes without a trace.
 */
private fun Sitting.reportLostOp(
    taken: Deferred<*>,
    reason: String,
) {
    if (!taken.isCompleted || taken.isCancelled) return
    report(TuneReport.PressDropped(reason, state.value.javaClass.logName()))
}

/** The play's end (an empty flow: stopped under us, which counts as an end), or the first live op taken. */
private suspend fun Sitting.endOrOp(
    end: Flow<PlaybackEnd>,
    control: PlayControl,
    blockStartMs: Long,
): Woke =
    coroutineScope {
        val ended = async { Woke.End(end.firstOrNull() ?: PlaybackEnd.Ended) }
        val op = async { takeLiveOp(control, blockStartMs) }
        val woke: Woke =
            try {
                select<Woke> {
                    ended.onAwait { it }
                    op.onAwait { it }
                }
            } catch (cut: TimeoutCancellationException) {
                // The block's end cut the hold: an op it had already taken goes with it.
                reportLostOp(op, "transport-lost-at-cut")
                throw cut
            }
        if (woke is Woke.End) reportLostOp(op, "transport-lost-at-end")
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
