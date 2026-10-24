// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackEnd
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

/** How a play that had started ended, as [Sitting.hold] sees it. */
internal enum class Held { Boundary, Ended, Failed }

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
    lead: Pending? = null,
    /** Called once, when the first picture of the sitting plays, with the scope of the block that showed it. */
    private val onFirstPicture: (CoroutineScope) -> Unit = {},
) {
    /** How far ahead of the clock this sitting runs (milliseconds, never negative). */
    var driftMs = 0L

    /** [ChannelOnAir.builtUntilMs] as of the last anchor; a drift that reaches it re-anchors (ADR-0017). */
    private var limitMs = Long.MAX_VALUE

    private val nextUp = NextUp(session, tuner, timings, ::vnowMs, lead)
    private var lingering: Job? = null
    private var pictured = false

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
                        holdIdent(onAir, onAir.now.endMs)
                    }

                    is Airing.Program -> {
                        playProgram(this, onAir, airing, handle)
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

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // One block's steps: each early return is a way it ends.
    private suspend fun playProgram(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
        program: Airing.Program,
        handle: Pending?,
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
            val joins = cut == null || cut.retarget
            val label = if (joins) BannerLabel.TuningIn else BannerLabel.Now
            val joined = if (joins) position.inWholeMilliseconds else null
            val banner = bannerFor(onAir.channel, block, label, joined, driftMs)
            val use = cut
            cut = null
            val kind =
                when {
                    use != null -> TuneKind.Prepared
                    failures > 0 -> TuneKind.Retry
                    else -> TuneKind.Fresh
                }
            val asked = if (use != null && !use.retarget) use.start else position
            val outcome =
                tuneAndShow(scope, banner, snow = use == null, what = TuneWhat(kind, block, asked)) {
                    if (use != null) {
                        use.consumed = true
                        tuner.tune(session, use.handle, position.takeIf { use.retarget })
                    } else {
                        tuner.tune(session, ItemId(program.itemId.value), position)
                    }
                }
            val dead =
                when (outcome) {
                    is TuneOutcome.Playing -> {
                        when (hold(block.endMs, outcome.end)) {
                            Held.Boundary -> return null
                            Held.Ended -> return holdIdent(onAir, block.endMs)
                            Held.Failed -> ++failures >= 2
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
            if (dead) return deadItem(scope, onAir, program.itemId)
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
        tune: suspend () -> TuneOutcome,
    ): TuneOutcome {
        if (snow) state.value = LiveState.Tuning(banner)
        val began = clock.now()
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
            showWatching(scope, banner)
            if (!pictured) {
                pictured = true
                onFirstPicture(scope)
            }
        }
        return outcome
    }

    private fun showWatching(
        scope: CoroutineScope,
        banner: Banner,
    ) {
        lingering?.cancel()
        // Once the picture plays, "Tuning in" becomes "Now" (R22); the joining line stays. Filling in stays as it is.
        val label = if (banner.label == BannerLabel.TuningIn) BannerLabel.Now else banner.label
        val shown = LiveState.Watching(banner.copy(label = label))
        state.value = shown
        lingering =
            scope.launch {
                delay(timings.bannerLinger)
                state.compareAndSet(shown, LiveState.Watching(null))
            }
    }

    /** Waits for how the play ends, or for [untilMs] (the block's end, which cuts a show that runs long). */
    suspend fun hold(
        untilMs: Long,
        end: Flow<PlaybackEnd>,
    ): Held {
        val left = untilMs - vnowMs()
        // An empty flow means the play was stopped under us; it counts as an end.
        val ended = if (left <= 0) null else withTimeoutOrNull(left) { end.firstOrNull() ?: PlaybackEnd.Ended }
        if (ended == null) waitUntil(untilMs)
        return when {
            ended == null || vnowMs() >= untilMs -> {
                cutFrom = clock.now()
                Held.Boundary
            }

            ended is PlaybackEnd.Failed -> {
                Held.Failed
            }

            else -> {
                Held.Ended
            }
        }
    }

    /** The ident card until [untilMs], with "up next" from [onAir]: a gap, or a show that ended early. Stops first. */
    suspend fun holdIdent(
        onAir: ChannelOnAir,
        untilMs: Long,
    ): Exit? {
        // Worked out before the stop: the card must follow the stop at once, not after a look at the schedule.
        val up = upNext(onAir)
        tuner.stop(session)
        state.value = LiveState.Ident(up, onAir.tag())
        waitUntil(untilMs)
        cutFrom = clock.now()
        return null
    }

    fun expired(): Exit {
        state.value = LiveState.SignedOut
        return Exit.SignedOut
    }

    internal companion object {
        /** How many following blocks each look at the schedule asks for (the next one is pre-resolved). */
        const val WINDOW = 3
    }
}
