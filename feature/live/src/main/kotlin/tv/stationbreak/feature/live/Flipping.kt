// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Clock
import kotlin.time.Instant

/** When the viewer last pressed a key for a flip, and how long the settle waited after it (for [TuneReport.Flip]). */
internal class FlipTiming(
    val lastCommandAt: Instant,
    val settleMs: Long,
) {
    private var logged = false

    /** Time spent in the commit's schedule lookups, once it has looked (-1 before). */
    var lookupMs = TuneReport.NOT_SEPARATE

    /** When the commit was sent to the machine; set by the machine, to time the hand-over. */
    var sentAt: Instant? = null

    /** From [sentAt] to the new sitting's tune starting (-1 until a tune starts). */
    var handoverMs = TuneReport.NOT_SEPARATE

    /** True the first time only: a flip writes one line, whoever gets to it first. */
    fun claim(): Boolean = !logged.also { logged = true }
}

/**
 * Which channel the next sitting is on: [first] is what was on it at the commit, or null to choose as spec 14 does (the
 * saved channel, else the first with something on). [flip] is set when a flip asked for it.
 */
internal class SitRequest(
    val first: ChannelOnAir?,
    val flip: FlipTiming?,
)

/**
 * The channel being watched and the one before it, for Menu. Both are written from the sitting and from [Flipper], and
 * [moveTo] is idempotent, so whichever sees a channel change first sets it and the other changes nothing. Kept for this
 * run only (design: not persisted).
 */
internal class Tuned {
    var playing: ChannelAiring? = null
        private set

    var previous: ChannelId? = null
        private set

    @Synchronized
    fun moveTo(channel: ChannelAiring) {
        val was = playing
        if (was != null && was.id != channel.id) previous = was.id
        playing = channel
    }
}

/**
 * Flipping (spec 15): follows the viewer's [LiveCommand]s and asks for a new sitting with [start] when a flip commits.
 * It never touches the running sitting: [LiveState.Choosing] is only laid over the screen ([Screen]), so the old
 * channel plays on while the number moves, and the commit is the one place a sitting is replaced (the machine's
 * `collectLatest` cancels the old sitting, which stops the tuner, before the new one starts). Commands are handled one
 * at a time; only the settle runs beside them, and each command cancels a pending settle before it acts. It does not
 * wait for the settle to stop: a schedule lookup can run on for seconds (a day being built), and the number must keep
 * moving meanwhile. A cancelled settle never reaches [land], so a commit is either whole or never began, and the flip
 * it had begun is logged as superseded.
 */
@Suppress("LongParameterList") // The machine's ports, one for one.
internal class Flipper(
    private val schedule: ScheduleSource,
    private val clock: Clock,
    private val timings: LiveTimings,
    private val screen: Screen,
    private val tuned: Tuned,
    private val report: (TuneReport) -> Unit,
    private val scope: CoroutineScope,
    private val start: (SitRequest) -> Unit,
) {
    // The lineup as of the last refresh, read off the key path (a sitting starting). A press uses it and reads the
    // lineup itself only when there is none yet.
    private var cached: FlipOrder? = null
    private var refreshing: Job? = null

    // The snapshot a run of presses steps through: the cache as of the first press after a commit.
    private var order: FlipOrder? = null

    // Where a commit would go; null before the first press after a commit.
    private var target: ChannelAiring? = null
    private var settling: Job? = null

    /** Reads the lineup in the background for the presses to come; a read still running is replaced. */
    fun refresh() {
        refreshing?.cancel()
        refreshing =
            scope.launch {
                try {
                    cached = FlipOrder(schedule.airings(clock.now()))
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    // Keep the last good snapshot; a press reads for itself if there is none.
                }
            }
    }

    /** The lineup as of the last refresh, or a fresh read when there is none yet (not kept: a refresh sets it). */
    suspend fun lineup(): FlipOrder {
        cached?.let { return it }
        // A refresh in flight is the read being made: wait for it rather than read the lineup twice.
        refreshing?.takeIf { it.isActive }?.join()
        return cached ?: FlipOrder(schedule.airings(clock.now()))
    }

    /**
     * Handles [commands] until the flow ends; a settle still pending carries on in the run's scope. A command that
     * fails is dropped, not fatal.
     */
    suspend fun follow(commands: Flow<LiveCommand>) =
        coroutineScope {
            commands.collect { command ->
                try {
                    when (command) {
                        is LiveCommand.Flip -> press(command.direction)
                        LiveCommand.Previous -> previous()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    failed(e)
                }
            }
        }

    private val onScreen: Boolean
        get() = screen.value.let { it != LiveState.NoChannels && it != LiveState.SignedOut }

    private suspend fun press(direction: FlipDirection) {
        val now = clock.now()
        // Cancelled, not joined: the number must not wait for a commit's lookup to unwind.
        settling?.cancel()
        val playing = tuned.playing
        if (playing == null || !onScreen) return
        val steps = order ?: cached ?: FlipOrder(schedule.airings(now))
        order = steps
        val next = steps.step(target?.number ?: playing.number, direction) ?: return
        target = next
        screen.choose(LiveState.Choosing(next.tag(), overPicture = screen.pictureUp))
        settling = scope.launch { settle(steps, next, direction, now) }
    }

    /** Waits out the settle after the last press at [pressedAt], then commits to [chosen]. */
    private suspend fun settle(
        steps: FlipOrder,
        chosen: ChannelAiring,
        direction: FlipDirection,
        pressedAt: Instant,
    ) {
        var timing: FlipTiming? = null
        try {
            delay(timings.flipSettle)
            timing = FlipTiming(pressedAt, (clock.now() - pressedAt).inWholeMilliseconds)
            commit(steps, chosen, direction, timing)
        } catch (e: CancellationException) {
            unresolved(timing)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A lookup that failed on its way out of a cancel is that cancel, not a fault of the newer presses.
            if (currentCoroutineContext().isActive) failed(e) else unresolved(timing)
        }
    }

    /**
     * Writes the line of a flip that was committed (the settle ran out) but stopped before it was handed to a sitting:
     * a newer press replaced it (superseded) or the run ended (abandoned). A flip never sent has no sitting to log it.
     */
    private fun unresolved(timing: FlipTiming?) {
        if (timing == null || timing.sentAt != null || !timing.claim()) return
        report(timing.ended(if (scope.isActive) "superseded" else "abandoned", clock.now()))
    }

    /**
     * Commit to [chosen]; a channel with nothing on the air is stepped past in [direction], at most once round the
     * order, and if none has anything on the sitting chooses as it does at start. This is the one point where a flip
     * becomes a tune.
     */
    private suspend fun commit(
        steps: FlipOrder,
        chosen: ChannelAiring,
        direction: FlipDirection,
        timing: FlipTiming,
    ) {
        var candidate: ChannelAiring? = chosen
        var onAir: ChannelOnAir? = null
        var looked = 0
        var lookupMs = 0L
        while (candidate != null && onAir == null && looked < steps.size) {
            val lookedAt = clock.now()
            onAir = schedule.onAir(candidate.id, lookedAt, Sitting.WINDOW)
            lookupMs += (clock.now() - lookedAt).inWholeMilliseconds
            // A lookup that ran on past a newer press must not land: only the newest press commits.
            currentCoroutineContext().ensureActive()
            if (onAir == null) candidate = steps.step(candidate.number, direction)
            looked++
        }
        timing.lookupMs = lookupMs
        land(onAir, chosen, timing)
    }

    /** Menu: the channel before this one, now. Nothing happens when there is none, or it is gone or off the air. */
    private suspend fun previous() {
        val now = clock.now()
        val wanted = tuned.previous?.takeIf { tuned.playing != null && onScreen } ?: return
        val steps = order ?: cached ?: FlipOrder(schedule.airings(now))
        val lookedAt = clock.now()
        val onAir = steps.byId(wanted)?.let { schedule.onAir(it.id, lookedAt, Sitting.WINDOW) }
        if (onAir == null) return
        val timing = FlipTiming(now, 0).also { it.lookupMs = (clock.now() - lookedAt).inWholeMilliseconds }
        settling?.cancelAndJoin()
        // A settle that finished while this looked would have moved the channel on: leave that commit be.
        if (tuned.previous == wanted) land(onAir, onAir.channel, timing)
    }

    /**
     * Starts a sitting on [onAir]. The number stays up over snow until that sitting writes its first state. A landing
     * on the channel already playing (a full wrap) tunes nothing and drops the number.
     */
    private fun land(
        onAir: ChannelOnAir?,
        chosen: ChannelAiring,
        timing: FlipTiming,
    ) {
        order = null
        target = null
        if (onAir != null && onAir.channel.id == tuned.playing?.id) {
            screen.drop()
            return
        }
        onAir?.let { tuned.moveTo(it.channel) }
        screen.commit(LiveState.Choosing((onAir?.channel ?: chosen).tag(), overPicture = false))
        start(SitRequest(onAir, timing))
    }

    /** A flip that broke on an unexpected exception: report its class name only, and show the channel again. */
    private fun failed(error: Exception) {
        report(TuneReport.Failed(error.javaClass.simpleName.ifEmpty { "Unknown" }))
        order = null
        target = null
        screen.drop()
    }

    private fun ChannelAiring.tag() = ChannelTag(number, name)
}
