// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.feature.live.LiveChannel
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.TunePort
import tv.stationbreak.feature.live.TunePortAdapter
import javax.inject.Inject
import kotlin.time.Clock

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
    ) : ViewModel() {
        private val shown = MutableStateFlow<LiveState>(LiveState.Ident(null))
        val state: StateFlow<LiveState> = shown.asStateFlow()

        /**
         * One STARTED period: makes the player, shows it on [surface], runs the live channel, and on the way out (a
         * stop, or the session ending) closes the tuner, then detaches and releases the player, in that order
         * (ADR-0016, 5). Returns when the session ends; otherwise runs until cancelled.
         */
        suspend fun sit(
            context: Context,
            surface: SurfaceView,
            tuners: TunerFactory = controllers,
        ) {
            shown.value = LiveState.Ident(null)
            val session = sessions.current.value
            if (session == null) {
                shown.value = LiveState.SignedOut
                return
            }
            val player = players.create(context, SystemClock::elapsedRealtime)
            var tuner: TunePort? = null
            try {
                player.attach(surface)
                val port = tuners.create(player, session).also { tuner = it }
                val live = LiveChannel(schedule, port, lastChannel, sessions, library, channels, clock)
                coroutineScope {
                    val mirror = launch { live.state.collect { shown.value = it } }
                    live.run()
                    // run() returns only when the session ended.
                    mirror.cancel()
                }
                shown.value = live.state.value
                if (shown.value == LiveState.SignedOut) {
                    // As everywhere else: the rejected session and its account's cached library go together.
                    sessions.onServerError(MediaServerError.SessionExpired(), session)
                }
            } finally {
                withContext(NonCancellable) {
                    tuner?.close(session)
                    player.attach(null)
                    player.release()
                }
            }
        }
    }
