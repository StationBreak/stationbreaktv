// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import androidx.annotation.VisibleForTesting
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
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.feature.live.report.AiringKey
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
    /** Fetches what the program info overlay shows beyond the banner; null leaves the overlay with the banner only. */
    details: ProgramDetailsCache? = null,
) {
    // The StationBreak card until the first look at the lineup: never a blank screen, never a false "no channels".
    private val screen = Screen(LiveState.Ident(null))
    private val tuned = Tuned()
    private val overlays = Overlays(screen, timings, tuned, details)

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

    // The sitting that is running, for the transport presses to reach; set and cleared by [sit].
    private var current: Sitting? = null

    /** The sitting that is running, for tests that look at its transport state. */
    @VisibleForTesting
    internal fun currentSitting(): Sitting? = current

    // The running run's reporter, so that [leave] can end its reporting before the run's own teardown gets to it.
    @Volatile
    @VisibleForTesting
    internal var reporting: PlaybackReporter? = null
        private set

    val state: StateFlow<LiveState> = screen.flow

    /** The program info overlay (spec 17) while one is open: closed by a tune, a boundary or a flip. */
    val info: StateFlow<LiveInfo?> = screen.info

    /** What the screen shows of the transport while the viewer is behind or paused; null while live (spec 20). */
    val transport: StateFlow<TransportView?> = screen.transport

    /** The channel tuned last (the one playing, or being tuned); null before the first. The guide opens on it. */
    val playingChannel: ChannelId? get() = tuned.playing?.id

    /** The latest flips, the newest last, at most [LiveTimings.flipLogSize]. */
    val flips: List<TuneReport.Flip> get() = flipLog

    /**
     * The viewer has left live TV (#431): ends reporting now, without suspending, so the final Stopped is queued at
     * once and goes out on the reporter's next dispatch, ahead of the run's own teardown (a tuner stop, the
     * neighbours' release), which may still be waiting. The host calls it as the screen leaves, then cancels the run.
     * Reporting stays closed for the rest of this run; a later [run] reports afresh.
     */
    fun leave() {
        reporting?.close()
    }

    /**
     * The place to keep across a screen stop (spec 20, ADR-0021 5): the paused place of the running sitting, with its
     * airing's watched time so far. Null unless the viewer is paused on a program's block: live, playing behind and on
     * a stand-in, the viewer joins live after the stop. Never suspends and never asks the player: the place is the one
     * the behind play last knew (its last Paused or SeekDone), and a paused player does not move. Main thread only.
     */
    fun kept(): Kept? {
        val place = current?.behind?.pausedPlace() ?: return null
        val program = place.block.airing as? Airing.Program
        val key = program?.let { AiringKey(place.channel, place.block.startMs, ItemId(it.itemId.value)) }
        return Kept(place, reporting?.snapshot()?.takeIf { it.key == key })
    }

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
     *
     * A [resume] kept from before a screen stop (in memory only, never persisted) seeds the reporter with its watched
     * time, and the first sitting returns to its place, paused, when its channel is on air and the return rules keep it
     * ([returnTo]); otherwise the viewer joins live.
     */
    suspend fun run(
        commands: Flow<LiveCommand> = emptyFlow(),
        resume: Kept? = null,
    ) {
        val session = sessions.current.value
        if (session == null) {
            screen.value = LiveState.SignedOut
            return
        }
        val neighbours = Neighbours(session, tuner, schedule, clock)
        val requests = Channel<SitRequest>(Channel.CONFLATED)
        requests.trySend(SitRequest(null, null, resume?.place))
        // Its own scope and job, so it outlives every sitting (a flip cancels them) and run's own cancel: the final
        // Stopped is drained from that scope after run ends (finishReporting). Never a child of a sitting (R1).
        val reporterScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val reporter =
            server?.let {
                PlaybackReporter(
                    reporterScope,
                    it,
                    session,
                    clock,
                    reportingConfig.copy(pauseReportMax = timings.pauseReportMax),
                ) { line ->
                    report(TuneReport.Reported(line))
                }
            }
        reporting = reporter
        // Before any visit of this run, so the kept airing's watched time goes on.
        resume?.watched?.let { reporter?.seed(it) }
        try {
            coroutineScope {
                val flipper =
                    Flipper(schedule, clock, timings, screen, tuned, ::record, this, ::earlyLineup) {
                        send(requests, it)
                    }
                refresher = flipper::refresh
                lineup = flipper::lineup
                follow(this, commands, flipper)
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
                reporting = null
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
        // Only the first sitting of a request lands where it says; one after it chooses afresh.
        var landing: SitRequest? = request
        var flip = request.flip
        try {
            while (true) {
                try {
                    val (acct, first, place) = land(landing)
                    landing = null
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
                    // The sitting this one replaces takes no more presses: its inbox is closed with it.
                    sitting?.commands?.close()
                    sitting = next
                    current = next
                    val exit = next.follow(first, place)
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
            sitting?.commands?.close()
            current = null
            withContext(NonCancellable) {
                sitting?.release()
                tuner.stop(session)
            }
        }
    }

    /**
     * Follows the viewer's [commands] in [scope], one at a time and in order: a transport press goes straight to the
     * sitting's inbox (ADR-0021, 9: not through the flipper), everything else to the flipper's handler (and the
     * overlays). Neither suspends, so a transport press never waits on anything that does, and a press queued behind a
     * flip sees the screen that flip left (the number up: dropped). A command that throws is dropped, as the flipper
     * drops its own.
     */
    private fun follow(
        scope: CoroutineScope,
        commands: Flow<LiveCommand>,
        flipper: Flipper,
    ) {
        scope.launch {
            commands.collect { command ->
                val op = transportOp(command)
                if (op != null) {
                    flipper.guarded { routeTransport(op) }
                } else {
                    flipper.handle(command) { overlays.handle(it, scope) }
                }
            }
        }
    }

    /** The sitting's op for a transport press; null for any other command (the flipper's or an overlay's). */
    private fun transportOp(command: LiveCommand): TransportOp? =
        when (command) {
            LiveCommand.PlayPause -> TransportOp.Toggle
            LiveCommand.Play -> TransportOp.Play
            LiveCommand.Pause -> TransportOp.Pause
            is LiveCommand.Seek -> TransportOp.Step(command.direction, command.repeat, command.atMs)
            LiveCommand.SeekReleased -> TransportOp.StepReleased
            LiveCommand.GoLive -> TransportOp.GoLive
            else -> null
        }

    /**
     * Hands a transport [op] to the running sitting's inbox, tagged with the sitting and the block whose picture is
     * up. Dropped, with a line, unless the picture plays: snow, a number being chosen and every card drop it.
     */
    private fun routeTransport(op: TransportOp) {
        val state = screen.flow.value
        val sitting = current
        val held = sitting?.heldBlockStartMs
        if (state !is LiveState.Watching || sitting == null || held == null) {
            report(TuneReport.PressDropped("transport-not-watching", state.javaClass.logName()))
            return
        }
        // Refused when the inbox is closed (the sitting is ending) or is another sitting's.
        if (!sitting.commands.offer(Tagged(sitting.sittingId, held, op))) {
            report(TuneReport.PressDropped("transport-refused", state.javaClass.logName()))
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
            SittingCommands(sittingId),
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
        report(TuneReport.Failed(error.javaClass.logName().ifEmpty { "Unknown" }))
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

    /** Where a sitting lands: the account, the channel with what is on it, and a kept place to return to on it. */
    private data class Landing(
        val account: LibraryAccount,
        val onAir: ChannelOnAir,
        val place: Place?,
    )

    /**
     * Where [request]'s sitting lands: the channel a flip landed on; else a kept place's channel when it is on air,
     * with the place; else the channel chosen as at start (and a kept place is dropped: the viewer joins live).
     */
    private suspend fun land(request: SitRequest?): Landing {
        request?.first?.let { return Landing(checkNotNull(account), it, null) }
        val kept = request?.resume
        val (acct, onAir) = kept?.let { onAirFor(it) } ?: awaitChannel()
        account = acct
        return Landing(acct, onAir, kept?.takeIf { it.channel == onAir.channel.id })
    }

    /** A kept [place]'s channel with what is on it now, and the active account; null when it is not on air. */
    private suspend fun onAirFor(place: Place): Pair<LibraryAccount, ChannelOnAir>? {
        val acct = library.activeAccount().first() ?: return null
        return schedule.onAir(place.channel, clock.now(), Sitting.WINDOW)?.let { acct to it }
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
