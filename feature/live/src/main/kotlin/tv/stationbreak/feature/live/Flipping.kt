// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import tv.stationbreak.core.domain.logName
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
    // Volatile: the screen reads it on a key press (the guide opens on it) while the run writes it.
    @Volatile
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
 * it had begun is logged as superseded. The collector never waits on the schedule either: presses made before there is
 * a lineup to step through are queued and applied, in order, when a read launched for them arrives, and Menu looks its
 * channel up in a job of its own.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The machine's ports, one for one; one flip, step by step.
internal class Flipper(
    private val schedule: ScheduleSource,
    private val clock: Clock,
    private val timings: LiveTimings,
    private val screen: Screen,
    private val tuned: Tuned,
    private val report: (TuneReport) -> Unit,
    private val scope: CoroutineScope,
    private val earlyLineup: suspend () -> EarlyLineup?,
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

    // Presses made while there was no lineup to step through, and the read being made for them.
    private val queued = ArrayList<Pair<FlipDirection, Instant>>()
    private var reading: Job? = null

    // Presses made before the first sitting had a channel, and the read of the lineup list for their banner.
    private val early = EarlyPresses()
    private var earlyRead: Job? = null
    private var earlyShown = false

    // Menu's lookups of the previous channel, in the order pressed, while they are running.
    private val recalls = ArrayList<Job>()

    // The guide's tune, while its lookup runs; a newer press or tune replaces it.
    private var tuning: Job? = null

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
        anchored()
    }

    /**
     * The first sitting has a channel: presses made before are applied now, as one flip from where it landed. Decided
     * on the sitting having been given a channel, not on [onScreen]: the screen still shows what it showed before
     * (the card, or no channels) until the sitting writes its first state.
     *
     * The applied flip's timing counts from the cold-launch press, so its settle and time to playing include the wait
     * for the first sitting.
     */
    private fun anchored() {
        if (early.isEmpty || tuned.playing == null) return
        earlyRead?.cancel()
        val presses = early.take()
        // Each press the viewer waited out is a commit that the next one absorbed.
        writeLines(presses, presses.size - 1, "superseded")
        earlyTail = presses.last()
        queued += presses
        if (reading?.isActive != true) reading = scope.launch { readThenStep(fromEarly = true) }
    }

    /** Writes a line for each of the first [count] [presses] that waited out the settle before the next (or now). */
    private fun writeLines(
        presses: List<Press>,
        count: Int,
        outcome: String,
    ) {
        val now = clock.now()
        for (i in 0 until count) {
            val at = presses[i].second
            if ((presses.getOrNull(i + 1)?.second ?: now) - at < timings.flipSettle) continue
            report(FlipTiming(at, timings.flipSettle.inWholeMilliseconds).ended(outcome, now))
        }
    }

    /** The run is ending: presses still waiting for a first sitting can never be acted on. */
    fun abandonEarly() {
        earlyRead?.cancel()
        val presses = early.take()
        writeLines(presses, presses.size, "abandoned")
        if (earlyShown) screen.drop()
        earlyShown = false
    }

    /** The last early press, until its settle owns its line (or the read for it failed and wrote one). */
    private var earlyTail: Press? = null

    /** The lineup as of the last refresh, or a fresh read when there is none yet (not kept: a refresh sets it). */
    suspend fun lineup(): FlipOrder {
        cached?.let { return it }
        // A refresh in flight is the read being made: wait for it rather than read the lineup twice.
        refreshing?.takeIf { it.isActive }?.join()
        return cached ?: FlipOrder(schedule.airings(clock.now()))
    }

    /**
     * Handles [commands] until the flow ends; a settle still pending carries on in the run's scope. A command that
     * fails is dropped, not fatal. [overlay] sees every command first: the overlays' commands are its alone, and a
     * flip's key also keeps the actions banner up.
     */
    suspend fun follow(
        commands: Flow<LiveCommand>,
        overlay: (LiveCommand) -> Unit = {},
    ) = coroutineScope {
        commands.collect { command ->
            try {
                overlay(command)
                when (command) {
                    is LiveCommand.Flip -> press(command.direction)
                    LiveCommand.Previous -> recall()
                    is LiveCommand.Tune -> tune(command.channel)
                    LiveCommand.ShowBanner, LiveCommand.ShowInfo, LiveCommand.DismissOverlay -> Unit
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

    private fun press(direction: FlipDirection) {
        val now = clock.now()
        // Cancelled, not joined: the number must not wait for a commit's lookup to unwind.
        settling?.cancel()
        tuning?.cancel()
        recalls.forEach {
            dropped("menu-superseded")
            it.cancel()
        }
        if (!onScreen) {
            dropped("off-screen")
            abandonEarly()
        } else if (tuned.playing == null) {
            holdEarly(direction, now)
        } else {
            stepOrQueue(direction, now)
        }
    }

    private fun stepOrQueue(
        direction: FlipDirection,
        now: Instant,
    ) {
        val steps = order ?: cached
        if (steps == null || queued.isNotEmpty()) {
            // No lineup yet: the collector must not wait for the read. Presses behind this one queue in order.
            queued += direction to now
            if (reading?.isActive != true) reading = scope.launch { readThenStep() }
            return
        }
        step(steps, direction, now)
    }

    /**
     * A press before the first sitting: kept, never dropped. The banner follows it as soon as the lineup list is
     * loaded, read in a job of its own so the collector never waits for it (nor for the day build). With no saved
     * channel in the lineup there is no banner (see [EarlyPresses]); the press is still kept.
     */
    private fun holdEarly(
        direction: FlipDirection,
        pressedAt: Instant,
    ) {
        early.add(direction, pressedAt)
        if (early.lineup != null) {
            showEarly()
        } else if (earlyRead?.isActive != true) {
            earlyRead = scope.launch { readEarly() }
        }
    }

    private suspend fun readEarly() {
        early.lineup =
            try {
                earlyLineup()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                // No early banner; the presses still wait for the sitting.
                null
            }
        if (tuned.playing == null) showEarly()
    }

    private fun showEarly() {
        if (early.isEmpty) return
        val next = early.target() ?: return
        earlyShown = true
        screen.choose(LiveState.Choosing(next.tag(), overPicture = false))
    }

    /** Reads the lineup for the presses that queued, then applies them in the order they were made. */
    private suspend fun readThenStep(fromEarly: Boolean = false) {
        val steps =
            try {
                lineup()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                queued.clear()
                closeEarlyTail("failed")
                failed(e)
                return
            }
        val presses = queued.toList()
        queued.clear()
        // Early presses were pressed against a screen that has not caught up with the sitting yet.
        presses.forEach { (direction, at) ->
            if (fromEarly || onScreen) step(steps, direction, at) else dropped("off-screen-queued")
        }
        earlyTail = null
    }

    private fun closeEarlyTail(outcome: String) {
        val (_, at) = earlyTail ?: return
        earlyTail = null
        val now = clock.now()
        report(FlipTiming(at, timings.flipSettle.inWholeMilliseconds).ended(outcome, now))
    }

    /** Moves the number one step from where the last press left it and starts the settle. */
    private fun step(
        steps: FlipOrder,
        direction: FlipDirection,
        pressedAt: Instant,
    ) {
        val playing =
            tuned.playing ?: run {
                dropped("no-anchor")
                return
            }
        settling?.cancel()
        order = steps
        val next = steps.step(target?.number ?: playing.number, direction)
        if (next == null) {
            // Nowhere to go: take down a banner the early presses put up.
            dropped("nowhere-to-go")
            if (earlyShown) screen.drop()
            earlyShown = false
            return
        }
        earlyShown = false
        target = next
        screen.choose(LiveState.Choosing(next.tag(), overPicture = screen.pictureUp))
        settling = scope.launch { settle(steps, next, direction, pressedAt) }
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
            if (currentCoroutineContext().isActive) {
                failed(e)
                close(timing, "failed")
            } else {
                unresolved(timing)
            }
        }
    }

    /**
     * Writes the line of a flip that was committed (the settle ran out) but stopped before it was handed to a sitting:
     * a newer press replaced it (superseded) or the run ended (abandoned). A flip never sent has no sitting to log it.
     */
    private fun unresolved(timing: FlipTiming?) = close(timing, if (scope.isActive) "superseded" else "abandoned")

    /** Writes [timing]'s line with [outcome], unless it was handed to a sitting or already has its line. */
    private fun close(
        timing: FlipTiming?,
        outcome: String,
    ) {
        if (timing == null || timing.sentAt != null || !timing.claim()) return
        report(timing.ended(outcome, clock.now()))
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

    /**
     * Menu: the channel before this one, now. The lookup runs in its own job so the collector, and the number, never
     * wait on it; a number press cancels it. Menu twice in a row is two commits, so each waits for the one before.
     */
    private fun recall() {
        // Menu is the newer intent: presses still waiting for a lineup are not applied after it. Only when it can act:
        // with no previous channel it does nothing, and must not take a queued press with it.
        if (tuned.previous != null && tuned.playing != null && onScreen) {
            reading?.cancel()
            repeat(queued.size) { dropped("superseded-by-menu") }
            queued.clear()
        }
        val before = recalls.lastOrNull()
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Cancelled here (a press came first) it has no timing yet, so there is no flip line to write.
                    before?.join()
                    previous()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Cancelled meanwhile, the failure belongs to a flip a newer press replaced: it is not reported.
                    if (currentCoroutineContext().isActive) failed(e)
                } finally {
                    recalls.remove(job)
                }
            }
        recalls += job
        job.start()
    }

    /** Nothing happens when there is no previous channel, or it is gone or off the air. */
    private suspend fun previous() {
        val now = clock.now()
        val wanted = tuned.previous?.takeIf { tuned.playing != null && onScreen }
        if (wanted == null) {
            dropped(
                when {
                    !onScreen -> "menu-off-screen"
                    tuned.playing == null -> "menu-no-anchor"
                    else -> "menu-no-previous"
                },
            )
            return
        }
        // Menu is a commit: from here it owes a flip line, `superseded` if a newer press replaces it.
        val timing = FlipTiming(now, 0)
        try {
            val lookedAt = clock.now()
            val steps = order ?: lineup()
            val onAir = steps.byId(wanted)?.let { schedule.onAir(it.id, lookedAt, Sitting.WINDOW) }
            // A lookup that ran on past a newer press must not touch that press's settle or overlay.
            currentCoroutineContext().ensureActive()
            if (onAir == null) {
                dropped("menu-previous-off-air")
                return
            }
            timing.lookupMs = (clock.now() - lookedAt).inWholeMilliseconds
            settling?.cancelAndJoin()
            // A settle that finished while this looked would have moved the channel on: leave that commit be.
            if (tuned.previous == wanted) land(onAir, onAir.channel, timing)
        } catch (e: CancellationException) {
            unresolved(timing)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (currentCoroutineContext().isActive) close(timing, "failed") else unresolved(timing)
            throw e
        }
    }

    /**
     * The guide's Select on a program on now: [channel], at once. The newest intent, like Menu: a flip still settling,
     * a Menu lookup and presses waiting for a lineup are dropped. The lookup runs in a job of its own, so the collector
     * never waits on it, and the landing is a flip's commit ([land]): the same tune line, Menu back to the channel
     * before, the look-ahead from the new channel.
     */
    private fun tune(channel: ChannelId) {
        settling?.cancel()
        recalls.forEach {
            dropped("menu-superseded")
            it.cancel()
        }
        reading?.cancel()
        repeat(queued.size) { dropped("superseded-by-tune") }
        queued.clear()
        tuning?.cancel()
        tuning = scope.launch { tuneTo(channel) }
    }

    private suspend fun tuneTo(channel: ChannelId) {
        if (!onScreen) {
            dropped("tune-off-screen")
            return
        }
        // From here a commit: it owes a flip line, `superseded` if a newer press replaces it.
        val timing = FlipTiming(clock.now(), 0)
        try {
            val lookedAt = clock.now()
            val onAir = lineup().byId(channel)?.let { schedule.onAir(it.id, lookedAt, Sitting.WINDOW) }
            // A lookup that ran on past a newer press must not land.
            currentCoroutineContext().ensureActive()
            if (onAir == null) {
                dropped("tune-off-air")
                // A number a cancelled flip had put up goes with it.
                if (target != null) screen.drop()
                order = null
                target = null
                return
            }
            timing.lookupMs = (clock.now() - lookedAt).inWholeMilliseconds
            land(onAir, onAir.channel, timing)
        } catch (e: CancellationException) {
            unresolved(timing)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (currentCoroutineContext().isActive) {
                failed(e)
                close(timing, "failed")
            } else {
                unresolved(timing)
            }
        }
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
        report(TuneReport.Failed(error.logName().ifEmpty { "Unknown" }))
        order = null
        target = null
        screen.drop()
    }

    /** Writes the line of a press that was not acted on, with the label of why and the state on screen (#392). */
    private fun dropped(reason: String) = report(TuneReport.PressDropped(reason, screen.value.logName()))

    private fun ChannelAiring.tag() = ChannelTag(number, name)
}
