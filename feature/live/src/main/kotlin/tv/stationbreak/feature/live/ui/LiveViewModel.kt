// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.feature.live.LiveChannel
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.TunePort
import tv.stationbreak.feature.live.TunePortAdapter
import tv.stationbreak.feature.live.TuneReport
import javax.inject.Inject
import kotlin.time.Clock

/** The logcat tag of the live channel's numbers: `SBLIVE tune ...`, `SBLIVE cut ...` and `SBLIVE sitting ...`. */
private const val LOG_TAG = "SBLIVE"

/**
 * Empties [commands] at the start of a sitting: a press from before the stop would flip a channel the viewer is no
 * longer looking at. Writes one `press dropped` line to [log] when there was any, with the count (#392).
 */
internal fun drainStalePresses(
    commands: Channel<LiveCommand>,
    state: String,
    noSession: Boolean,
    log: (String) -> Unit,
) {
    var drained = 0
    while (commands.tryReceive().isSuccess) drained++
    if (drained > 0) {
        val reason = if (noSession) "no-session" else "drained-at-sit"
        log(TuneReport.PressDropped(reason, state, drained).line())
    }
}

/** How a sitting gets its tuner: over [VideoPlayer], for [Session]. A test swaps it for one that records. */
internal fun interface TunerFactory {
    fun create(
        player: VideoPlayer,
        session: Session,
    ): TunePort
}

/** The real tuner: a [TuneController] over the player, behind the [TunePort] seam. */
internal class ControllerTunerFactory
    @Inject
    constructor(
        private val resolver: PlaybackResolver,
        private val server: MediaServer,
    ) : TunerFactory {
        override fun create(
            player: VideoPlayer,
            session: Session,
        ): TunePort = TunePortAdapter(TuneController(resolver, server, player, clock = SystemClock::elapsedRealtime))
    }

/**
 * Supplies the live screen's collaborators and holds what it shows. It does NOT own the player: the player lives for
 * one STARTED period of the screen ([sit]), so backgrounding or standby stops everything at once and returning starts a
 * fresh sitting that re-anchors to the clock (review of spec 14: one player at a time, none kept while stopped).
 */
@Suppress("LongParameterList") // The machine's ports, the tuner and the player's factory, one for one.
@HiltViewModel
internal class LiveViewModel
    @Inject
    constructor(
        private val schedule: ScheduleSource,
        private val lastChannel: LastChannelStore,
        private val sessions: SessionRepository,
        private val library: LibraryRepository,
        private val channels: ChannelRepository,
        private val clock: Clock,
        private val controllers: ControllerTunerFactory,
        private val players: VideoPlayerFactory,
        private val server: MediaServer,
        private val details: ProgramDetailsCache,
    ) : ViewModel() {
        private val shown = MutableStateFlow<LiveState>(LiveState.Ident(null))
        val state: StateFlow<LiveState> = shown.asStateFlow()

        private val shownInfo = MutableStateFlow<LiveInfo?>(null)

        /** The program info overlay (spec 17), while one is open. */
        val info: StateFlow<LiveInfo?> = shownInfo.asStateFlow()

        private val guide = GuideSwitch()

        /**
         * The program guide (spec 17) is open over the picture. Closed as soon as the screen is paused (it leaves the
         * foreground: the screen closes it), and again when the screen stops, should the pause have been missed.
         */
        val guideOpen: StateFlow<Boolean> = guide.shown

        /** The channel that was playing when the guide last opened: the guide opens with focus on it. */
        val guideChannel: ChannelId? get() = guide.channel

        /**
         * When the guide last opened, in `System.nanoTime` units: each opening's own, for the guide's open-time
         * measurement and so each opening is a fresh guide session.
         */
        val guideOpenedAt: StateFlow<Long> = guide.openedAt

        // The running sitting's machine, while there is one: the guide asks it which channel is playing.
        @Volatile
        private var live: LiveChannel? = null

        // Here, not in the composition, because the ViewModel outlives it; drained at the start of each sitting.
        private val commands = Channel<LiveCommand>(Channel.UNLIMITED)

        /** Opens the guide over the channel playing now; [atNanos] is when its key arrived (`System.nanoTime`). */
        fun openGuide(atNanos: Long) {
            guide.open(atNanos, live?.playingChannel)
        }

        fun closeGuide() {
            guide.close()
        }

        /**
         * What is up over the picture now, for the key that has just arrived. Read from these flows, not from the last
         * composition: the guide flag is set by the key that opened it, at once, and the overlays are the machine's
         * latest. A command still in the queue is not in them yet, which errs towards doing less: a Back right behind
         * a Back that is closing an overlay closes again (a no-op) rather than opening the guide or leaving.
         */
        fun overlayUpNow(): OverlayUp = overlayUpOf(state.value, info.value, guideOpen.value)

        /** A key press, in order. Presses made while the screen was stopped are dropped by the next [sit]. */
        fun send(command: LiveCommand) {
            if (commands.trySend(command).isFailure) Log.i(LOG_TAG, "press dropped reason=send-failed")
        }

        /**
         * One STARTED period: makes the player, shows it on [surface], runs the live channel, and on the way out (a
         * stop, or the session ending) closes the tuner, then detaches and releases the player, in that order
         * (ADR-0016, 5). Returns when the session ends; otherwise runs until cancelled. The guide closes with it, so
         * live TV comes back from Home or standby on the picture.
         */
        suspend fun sit(
            context: Context,
            surface: SurfaceView,
            tuners: TunerFactory = controllers,
        ) = guide.whileSitting { sitting(context, surface, tuners) }

        private suspend fun sitting(
            context: Context,
            surface: SurfaceView,
            tuners: TunerFactory,
        ) {
            val before = shown.value.logName()
            shown.value = LiveState.Ident(null)
            shownInfo.value = null
            val session = sessions.current.value
            drainStalePresses(commands, before, session == null) { Log.i(LOG_TAG, it) }
            if (session == null) {
                shown.value = LiveState.SignedOut
                return
            }
            val player = players.create(context, SystemClock::elapsedRealtime)
            var tuner: TunePort? = null
            var ended = false
            try {
                player.attach(surface)
                val port = tuners.create(player, session).also { tuner = it }
                // The measurements are numbers and fixed names only (TuneReport), so they may go to logcat.
                val machine =
                    LiveChannel(
                        schedule,
                        port,
                        lastChannel,
                        sessions,
                        library,
                        channels,
                        clock,
                        report = { Log.i(LOG_TAG, it.line()) },
                        server = server,
                        details = details,
                    ).also { live = it }
                coroutineScope {
                    // SignedOut is held back: the screen must not navigate away before the session is cleared.
                    val mirror = launch { machine.state.collect { if (it != LiveState.SignedOut) shown.value = it } }
                    val infoMirror = launch { machine.info.collect { shownInfo.value = it } }
                    machine.run(commands.receiveAsFlow())
                    // run() returns only when the session ended.
                    mirror.cancel()
                    infoMirror.cancel()
                }
                ended = machine.state.value == LiveState.SignedOut
                if (!ended) shown.value = machine.state.value
            } finally {
                live = null
                withContext(NonCancellable) {
                    tuner?.close(session)
                    player.attach(null)
                    player.release()
                }
            }
            // As everywhere else, the rejected session and its account's cached library go together; only then does
            // the screen learn it, because it navigates away at once and a cancelled sitting would skip the cleanup.
            if (ended) {
                publishAfterCleanup(
                    { sessions.onServerError(MediaServerError.SessionExpired(), session) },
                    { shown.value = LiveState.SignedOut },
                )
            }
        }
    }

/** Runs [cleanup] to its end, even if the caller is cancelled, and only then calls [publish]. */
internal suspend fun publishAfterCleanup(
    cleanup: suspend () -> Unit,
    publish: () -> Unit,
) = withContext(NonCancellable) {
    cleanup()
    publish()
}
