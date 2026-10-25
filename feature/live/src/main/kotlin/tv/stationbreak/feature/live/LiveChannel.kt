// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Session
import tv.stationbreak.feature.live.report.PlaybackReporter
import tv.stationbreak.feature.live.report.ReportingConfig
import kotlin.time.Clock

/**
 * The live channel (spec 14, F1.3): one sitting. [run] picks a channel, tunes it at the schedule's offset and follows
 * its blocks, and [state] says what the screen shows. The block loop is [Sitting]; dead items are in `DeadItems.kt`.
 * Time comes only from [clock] and `delay`, so virtual time drives it in tests. Nothing here logs: titles and IDs are
 * for the screen only. What it measures about each tune goes to [report] as numbers (see [TuneReport]); the host logs.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The machine's ports, one for one.
class LiveChannel(
    private val schedule: ScheduleSource,
    private val tuner: TunePort,
    private val lastChannel: LastChannelStore,
    private val sessions: SessionRepository,
    private val library: LibraryRepository,
    private val channels: ChannelRepository,
    private val clock: Clock,
    private val timings: LiveTimings = LiveTimings(),
    private val report: (TuneReport) -> Unit = {},
    /** The server the playback reporter (spec 16) talks to; null turns reporting off. */
    private val server: MediaServer? = null,
    private val reportingConfig: ReportingConfig = ReportingConfig(),
) {
    // The StationBreak card until the first look at the lineup: never a blank screen, never a false "no channels".
    private val screen = Screen(LiveState.Ident(null))
    private val tuned = Tuned()

    // The channel last saved as the last channel, and the account it is saved for: shared by the sittings of one run.
    private var saved: ChannelId? = null
    private var account: LibraryAccount? = null

    // A commit queued and not yet begun by a sitting: replaced, it is a flip that never resolved.
    private var unstarted: SitRequest? = null
    private var refresher: () -> Unit = {}
    private var lineup: suspend () -> FlipOrder = { FlipOrder(emptyList()) }
    private var looking: Job? = null
    private var lookingAt: ChannelId? = null
    private var flipLog: List<TuneReport.Flip> = emptyList()
    private var sittings = 0L

    val state: StateFlow<LiveState> = screen.flow

    /** The latest flips, the newest last, at most [LiveTimings.flipLogSize]. */
    val flips: List<TuneReport.Flip> get() = flipLog

    /**
     * Follows the viewer: picks a channel, tunes it at the schedule's offset and follows its blocks, and obeys
     * [commands] (spec 15). Everything here runs on one dispatcher (the main thread; the host launches it there), so
     * the shared fields need no locks; only [Screen], which the UI reads, has one.
     * Returns only when the session ends; otherwise it runs until cancelled.
     *
     * One sitting runs at a time ([sit]), and a flip's commit replaces it: the requests are collected with
     * `collectLatest`, so a newer one cancels the old sitting, whose finally stops the tuner, and only then does the
     * new one start. Moving the number ([Flipper]) never reaches the sitting, so the old channel plays on until then.
     * A tune that is cancelled while this run is still active (the tuner stopped under it) starts a fresh sitting
     * from the clock instead of ending the run. The finally of a sitting stops the tuner under [NonCancellable], so
     * backgrounding never leaves picture, sound or an encode running. It does not close the tuner: the host does,
     * then releases the player (ADR-0016).
     */
    suspend fun run(commands: Flow<LiveCommand> = emptyFlow()) {
        val session = sessions.current.value
        if (session == null) {
            screen.value = LiveState.SignedOut
            return
        }
        val neighbours = Neighbours(session, tuner, schedule, clock)
        val requests = Channel<SitRequest>(Channel.CONFLATED)
        requests.trySend(SitRequest(null, null))
        // Its own scope and job, so it outlives every sitting (a flip cancels them) and run's own cancel: the final
        // Stopped is drained from that scope after run ends (finishReporting). Never a child of a sitting (R1).
        val reporterScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val reporter =
            server?.let {
                PlaybackReporter(reporterScope, it, session, clock, reportingConfig) { line ->
                    report(TuneReport.Reported(line))
                }
            }
        try {
            coroutineScope {
                val flipper =
                    Flipper(schedule, clock, timings, screen, tuned, ::record, this, ::earlyLineup) {
                        send(requests, it)
                    }
                refresher = flipper::refresh
                lineup = flipper::lineup
                launch { flipper.follow(commands) }
                try {
                    requests.receiveAsFlow().collectLatest { request ->
                        if (sit(session, request, neighbours, reporter)) throw RunEnded()
                    }
                } finally {
                    // Presses still waiting for a first sitting can never be acted on now.
                    flipper.abandonEarly()
                }
            }
        } catch (
            @Suppress("SwallowedException") e: RunEnded,
        ) {
            // The session ended: the sitting already said so on the screen.
        } finally {
            // A commit nobody started a sitting for is a flip that never resolved.
            logFlip(unstarted?.flip, "abandoned")
            unstarted = null
            refresher = {}
            lineup = { FlipOrder(emptyList()) }
            try {
                withContext(NonCancellable) { neighbours.release() }
            } finally {
                finishReporting(reporter, reporterScope)
                screen.drop()
            }
        }
    }

    /** Queues a commit's sitting; a commit still waiting for its sitting is replaced, and its flip is logged so. */
    private fun send(
        requests: Channel<SitRequest>,
        request: SitRequest,
    ) {
        logFlip(unstarted?.flip, "superseded")
        unstarted = request
        request.flip?.sentAt = clock.now()
        requests.trySend(request)
    }

    /** Ends [run] from inside its sitting without being mistaken for a fault ([sit] returns instead of throwing). */
    private class RunEnded : RuntimeException()

    private fun record(what: TuneReport) {
        if (what is TuneReport.Flip) flipLog = (flipLog + what).takeLast(timings.flipLogSize)
        report(what)
    }

    /**
     * One sitting for one [request]: choose the channel (the one the flip landed on, or as at start), tune and follow
     * it, and when it ends of itself, look again. Returns true when the session ended.
     */
    private suspend fun sit(
        session: Session,
        request: SitRequest,
        neighbours: Neighbours,
        reporter: PlaybackReporter?,
    ): Boolean {
        if (unstarted === request) unstarted = null
        if (request.flip != null) screen.handOver()
        var sitting: Sitting? = null
        var known: ChannelTag? = null
        var landed = request.first
        var flip = request.flip
        try {
            while (true) {
                try {
                    val (acct, first) =
                        landed?.let { checkNotNull(account) to it } ?: awaitChannel().also { account = it.first }
                    landed = null
                    val id = first.channel.id
                    known = first.tag()
                    tuned.moveTo(first.channel)
                    refresher()
                    // Saved when a channel is chosen, not per block: a boundary cut is not a new channel (and the save
                    // is a synced file write that must not sit in the cut).
                    if (id != saved) lastChannel.save(acct, id)
                    saved = id
                    val next = newSitting(session, first, flip, neighbours, reporter)
                    flip = null
                    sitting = next
                    val exit = next.follow(first)
                    if (exit == Exit.SignedOut) return true
                    closeFlip(next, null, "abandoned")
                    // Cancelled from outside is not an exit: it throws here, and the finally stops everything.
                    currentCoroutineContext().ensureActive()
                    next.release()
                    tuner.stop(session)
                    if (exit == Exit.Lost) {
                        // The channel stopped airing: the card, then look again.
                        screen.value = LiveState.Ident(null)
                        // A flip off the card is as likely as one off a picture: look ahead while it waits.
                        coroutineScope {
                            lookAhead(this, neighbours, first.channel)
                            delay(timings.slateHold)
                            coroutineContext[Job]?.cancelChildren()
                        }
                    } else {
                        // The tuner was stopped under a live run (R24): re-anchor to the clock with a fresh sitting,
                        // under the snow of its tune. The pause only keeps a tuner that stays stopped from spinning
                        // this loop.
                        delay(timings.minSnow)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    closeFlip(sitting, flip, "abandoned")
                    flip = null
                    recoverFrom(e, session, sitting, known)
                }
            }
        } finally {
            // A newer commit waiting means this sitting was replaced; else the run is ending.
            closeFlip(sitting, flip, if (unstarted != null) "superseded" else "abandoned")
            withContext(NonCancellable) {
                sitting?.release()
                tuner.stop(session)
            }
        }
    }

    /**
     * Ends reporting without waiting for it: the final Stopped is sent from the reporter's own scope, which outlives
     * this run, so leaving live TV never delays the host's teardown (the player's release, a sign-out). The drain is
     * bounded by closeAndJoin's limit, and the scope is cancelled after it.
     */
    private fun finishReporting(
        reporter: PlaybackReporter?,
        reporterScope: CoroutineScope,
    ) {
        if (reporter == null) {
            reporterScope.cancel()
            return
        }
        reporter.close()
        reporterScope.launch {
            reporter.closeAndJoin()
            reporterScope.cancel()
        }
    }

    /** The sitting on [first]'s channel, playing the look-ahead's handle if it is still for what is on. */
    private suspend fun newSitting(
        session: Session,
        first: ChannelOnAir,
        flip: FlipTiming?,
        neighbours: Neighbours,
        reporter: PlaybackReporter?,
    ): Sitting {
        val id = first.channel.id
        val lead = neighbours.take(id, first)?.let { leadOf(it, first.now) }
        val sittingId = ++sittings
        reporter?.sittingStarted(sittingId)
        return Sitting(
            session,
            id,
            schedule,
            tuner,
            clock,
            timings,
            screen,
            ::record,
            flip,
            lead,
            reporter,
            sittingId,
        ) { scope ->
            lookAhead(scope, neighbours, first.channel)
        }
    }

    /**
     * Each time the screen settles on a picture or a card: resolves the channels above and below beside it, and again
     * as their blocks end. Not while the viewer is moving the number (the press is about to commit, and a commit ends
     * these anyway); it looks again when the number is still. A look-ahead already running for this channel is kept.
     */
    private fun lookAhead(
        scope: CoroutineScope,
        neighbours: Neighbours,
        playing: ChannelAiring,
    ) {
        // Already looking ahead from this channel (a picture, then its card): leave it be, it is the same work.
        if (lookingAt == playing.id && looking?.isActive == true) return
        looking?.cancel()
        lookingAt = playing.id
        looking =
            scope.launch {
                try {
                    neighbours.keepAround(playing, lineup) { screen.flow.value !is LiveState.Choosing }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    // No look-ahead is a slower flip, never a failed sitting.
                }
            }
    }

    /** Logs the flip a sitting (or the request not yet a sitting) still holds, once: it ended without resolving. */
    private fun closeFlip(
        sitting: Sitting?,
        flip: FlipTiming?,
        outcome: String,
    ) {
        val open = sitting?.flip ?: flip
        sitting?.flip = null
        logFlip(open, outcome)
    }

    /** Writes the line of a flip that ended without resolving, unless its line is already written. */
    private fun logFlip(
        timing: FlipTiming?,
        outcome: String,
    ) {
        if (timing != null && timing.claim()) record(timing.ended(outcome, clock.now()))
    }

    /**
     * An unexpected exception (a failed schedule or library read, say) must not end the screen: report its class name
     * (never its message, which may hold an id), put the card up with the channel if one was chosen, stop what plays,
     * and wait [LiveTimings.crashBackoff] so a fault that repeats cannot spin. The loop then starts a fresh sitting
     * from the clock. Only cancellation is ever rethrown.
     */
    private suspend fun recoverFrom(
        error: Exception,
        session: Session,
        sitting: Sitting?,
        known: ChannelTag?,
    ) {
        report(TuneReport.Failed(error.javaClass.simpleName.ifEmpty { "Unknown" }))
        screen.value = LiveState.Ident(null, known)
        withContext(NonCancellable) {
            try {
                sitting?.release()
                tuner.stop(session)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                // The cleanup failed too; the fresh sitting stops the tuner again before it tunes.
            }
        }
        delay(timings.crashBackoff)
    }

    /**
     * The lineup list as the banner can use it before the day is built: the switched-on channels with a number, from
     * the first list that has any. See [EarlyPresses] for the anchor rule. Waits for an account and a lineup, so it
     * runs in a job of its own, never on the press collector.
     */
    private suspend fun earlyLineup(): EarlyLineup? {
        val account = library.activeAccount().filterNotNull().first()
        val on =
            channels
                .channels(account)
                .map { list -> list.filter { it.state == ChannelStatus.On && it.number != null } }
                .first { it.isNotEmpty() }
        val airings = on.map { ChannelAiring(it.id, checkNotNull(it.number), it.name, it.onSince, null) }
        val saved = lastChannel.load(account)
        return EarlyLineup(FlipOrder(airings), airings.firstOrNull { it.id == saved })
    }

    /** Waits for a channel on the air. [LiveState.NoChannels] shows meanwhile; a lineup change looks again. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitChannel(): Pair<LibraryAccount, ChannelOnAir> =
        library
            .activeAccount()
            .flatMapLatest { account ->
                // No account is active until a library has been synced: nothing is on the air yet.
                if (account == null) {
                    screen.value = LiveState.NoChannels
                    emptyFlow()
                } else {
                    channels.channels(account).map { account }
                }
            }.mapNotNull { account ->
                val chosen = choose(account)
                if (chosen == null) screen.value = LiveState.NoChannels
                chosen?.let { account to it }
            }.first()

    /**
     * The saved channel, else the first of these with something on now (#375): the lowest-numbered unhidden channel
     * whose rating ceiling is the PG tier, then the lowest held to a ceiling below PG, then the lowest. Nobody has
     * chosen yet and anyone may be in the room, so little kids' channels are not preferred.
     */
    private suspend fun choose(account: LibraryAccount): ChannelOnAir? {
        val now = clock.now()
        val unhidden = schedule.airings(now).filter { it.hidden == null }
        val saved = lastChannel.load(account)
        // The sort is stable, so each group stays in number order.
        val rest = unhidden.filter { it.id != saved }.sortedBy { firstOpenGroup(it.ceilingRank) }
        val ordered = unhidden.filter { it.id == saved } + rest
        for (airing in ordered) {
            schedule.onAir(airing.id, now, Sitting.WINDOW)?.let { return it }
        }
        return null
    }

    /** 0 for the PG tier, 1 for a ceiling below PG, 2 for a higher ceiling or none. */
    private fun firstOpenGroup(ceilingRank: Int?): Int =
        when {
            ceilingRank == ChannelAiring.PG_TIER -> 0
            ceilingRank != null && ceilingRank < ChannelAiring.PG_TIER -> 1
            else -> 2
        }
}

/** A handle from [Neighbours.take] as the sitting wants it: for the block on air, played from the position then. */
private fun leadOf(
    handle: NextHandle,
    block: AiringBlock,
): Pending.Retarget? {
    val program = block.airing as? Airing.Program ?: return null
    return Pending.Retarget(handle, program.itemId, block.startMs)
}
