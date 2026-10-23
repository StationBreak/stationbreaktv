// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import tv.stationbreak.core.model.ChannelId
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
     * One sitting: pick a channel, tune, follow boundaries. Returns only when the session ends; otherwise it runs
     * until cancelled. A tune that is cancelled while this run is still active (the tuner stopped under it) starts a
     * fresh sitting from the clock instead of ending the run. Its finally stops the tuner under [NonCancellable], so
     * backgrounding never leaves picture, sound or an
     * encode running. It does not close the tuner: the host does, then releases the player (ADR-0016).
     */
    suspend fun run() {
        val session = sessions.current.value
        if (session == null) {
            current.value = LiveState.SignedOut
            return
        }
        var sitting: Sitting? = null
        var saved: ChannelId? = null
        try {
            while (true) {
                val (account, first) = awaitChannel()
                val id = first.channel.id
                // Saved when a channel is chosen, not per block: a boundary cut is not a new channel (and the save is
                // a synced file write that must not sit in the cut).
                if (id != saved) lastChannel.save(account, id)
                saved = id
                val next = Sitting(session, id, schedule, tuner, clock, timings, current)
                sitting = next
                val exit = next.follow(first)
                if (exit == Exit.SignedOut) return
                // Cancelled from outside is not an exit: it throws here, and the finally stops everything.
                currentCoroutineContext().ensureActive()
                next.release()
                tuner.stop(session)
                if (exit == Exit.Lost) {
                    // The channel stopped airing: the card, then look again.
                    current.value = LiveState.Ident(null)
                    delay(timings.slateHold)
                } else {
                    // The tuner was stopped under a live run (R24): re-anchor to the clock with a fresh sitting, under
                    // the snow of its tune. The pause only keeps a tuner that stays stopped from spinning this loop.
                    delay(timings.minSnow)
                }
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
            .flatMapLatest { account ->
                // No account is active until a library has been synced: nothing is on the air yet.
                if (account == null) {
                    current.value = LiveState.NoChannels
                    emptyFlow()
                } else {
                    channels.channels(account).map { account }
                }
            }.mapNotNull { account ->
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
