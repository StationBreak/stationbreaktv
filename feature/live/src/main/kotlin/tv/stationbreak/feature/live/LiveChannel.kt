// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ScheduleSource
import kotlin.time.Clock

/**
 * The live channel (spec 14, F1.3): one sitting. [run] picks a channel, tunes it at the schedule's offset and follows
 * its blocks, and [state] says what the screen shows. The block loop is [Sitting]; dead items are in `DeadItems.kt`.
 * Time comes only from [clock] and `delay`, so virtual time drives it in tests. Nothing here logs: titles and IDs are
 * for the screen only.
 */
@Suppress("LongParameterList") // The machine's ports, one for one.
class LiveChannel(
    private val schedule: ScheduleSource,
    private val tuner: TunePort,
    private val lastChannel: LastChannelStore,
    private val sessions: SessionRepository,
    private val library: LibraryRepository,
    private val channels: ChannelRepository,
    private val clock: Clock,
    private val timings: LiveTimings = LiveTimings(),
) {
    // The StationBreak card until the first look at the lineup: never a blank screen, never a false "no channels".
    private val current = MutableStateFlow<LiveState>(LiveState.Ident(null))

    val state: StateFlow<LiveState> = current.asStateFlow()

    /**
     * One sitting: pick a channel, tune, follow boundaries. Returns when the session ends; otherwise it runs until
     * cancelled. Its finally stops the tuner under [NonCancellable], so backgrounding never leaves picture, sound or an
     * encode running. It does not close the tuner: the host does, then releases the player (ADR-0016).
     */
    suspend fun run() {
        val session = sessions.current.value
        if (session == null) {
            current.value = LiveState.SignedOut
            return
        }
        var sitting: Sitting? = null
        try {
            while (true) {
                val (account, first) = awaitChannel()
                val id = first.channel.id
                val next = Sitting(session, account, id, schedule, tuner, lastChannel, clock, timings, current)
                sitting = next
                if (next.follow(first) != Exit.Lost) return
                // The channel stopped airing: the card, then look again.
                next.release()
                tuner.stop(session)
                current.value = LiveState.Ident(null)
                delay(timings.slateHold)
            }
        } finally {
            withContext(NonCancellable) {
                sitting?.release()
                tuner.stop(session)
            }
        }
    }

    /** Waits for a channel on the air. [LiveState.NoChannels] shows meanwhile; a lineup change looks again. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitChannel(): Pair<LibraryAccount, ChannelOnAir> =
        library
            .activeAccount()
            .filterNotNull()
            .flatMapLatest { account -> channels.channels(account).map { account } }
            .mapNotNull { account ->
                val chosen = choose(account)
                if (chosen == null) current.value = LiveState.NoChannels
                chosen?.let { account to it }
            }.first()

    /** The saved channel, else the lowest unhidden one: the first of them that has something on now. */
    private suspend fun choose(account: LibraryAccount): ChannelOnAir? {
        val now = clock.now()
        val unhidden = schedule.airings(now).filter { it.hidden == null }
        val saved = lastChannel.load(account)
        val ordered = unhidden.filter { it.id == saved } + unhidden.filter { it.id != saved }
        for (airing in ordered) {
            schedule.onAir(airing.id, now, Sitting.WINDOW)?.let { return it }
        }
        return null
    }
}
