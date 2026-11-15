// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Watches the channels the viewer switches on (#776). One loop for all of them: a switch-on waits a moment for any
 * more, then one [ScheduleSource.airings] call (which builds the days, so it answers when they are built) settles
 * every pending channel, so channels that switch on together are ready together and make one notice.
 *
 * Only a switch-on of the viewer's own, through [ChannelSwitchOns], is watched. A channel the builder creates is also
 * switched on, but its own screen already shows it; it is not watched here.
 *
 * [active] is the active account, the one [ScheduleSource.airings] answers for (the library's own, as the schedule
 * source reads it): a switch-on made for another account is dropped, and no build is asked for it. The work runs on
 * [scope], which outlives any screen.
 */
class BuiltInChannelReadiness(
    private val schedule: ScheduleSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val active: Flow<LibraryAccount?>,
    private val times: ReadinessTimes = ReadinessTimes(),
) : ChannelReadiness,
    ChannelSwitchOns {
    private data class Held(
        val account: LibraryAccount?,
        val ids: Set<ChannelId>,
    )

    private val lock = Any()
    private val held = MutableStateFlow(Held(null, emptySet()))
    private val shown = MutableStateFlow<ReadyNotice?>(null)
    private var clearing: Job? = null
    private var lastId = 0L

    // One runner, so passes never overlap: a switch-on that comes while a build runs waits for the next pass.
    private val requests = Channel<Unit>(Channel.CONFLATED)

    // Only the active account's channels are pending: starter ids are the same in every account.
    override val pending: Flow<Set<ChannelId>> =
        combine(held, active) { h, account -> if (h.account == account) h.ids else emptySet() }
            .distinctUntilChanged()

    override val notice: StateFlow<ReadyNotice?> = shown.asStateFlow()

    init {
        scope.launch {
            requests.consumeEach {
                // More switch-ons soon after are settled with this one: wait until none comes for a moment.
                while (withTimeoutOrNull(times.settle) { requests.receive() } != null) Unit
                settlePending()
            }
        }
    }

    override fun switchedOn(
        account: LibraryAccount,
        id: ChannelId,
    ) {
        held.update { if (it.account == account) it.copy(ids = it.ids + id) else Held(account, setOf(id)) }
        requests.trySend(Unit)
    }

    private suspend fun settlePending() {
        val batch = held.value
        if (batch.ids.isEmpty()) return
        // airings answers for the active account: a batch for another one is dropped, and no build is asked for it.
        val airings = if (active.first() == batch.account) airingsOrNull() else null
        // The batch is done with, whatever came of it: each of its channels ends here, ready or not.
        held.update { if (it.account == batch.account) it.copy(ids = it.ids - batch.ids) else it }
        if (airings == null || active.first() != batch.account) return
        val ready = airings.filter { it.id in batch.ids && it.hidden == null }
        if (ready.isNotEmpty()) show(ready.map { ReadyChannel(it.id, it.number, it.name) })
    }

    // The days of every channel are built by the call, so one that fails is asked again a few times, then given up on.
    @Suppress("TooGenericExceptionCaught") // Any failure of a build: drop the batch, never crash the app's scope.
    private suspend fun airingsOrNull(): List<ChannelAiring>? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return schedule.airings(clock.now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt < ATTEMPTS - 1) delay(times.retryAfter)
            }
        }
        return null
    }

    // A second batch while a notice shows joins it, and the notice stays for a full time again.
    private fun show(ready: List<ReadyChannel>) {
        synchronized(lock) {
            val before = shown.value
            val channels = (before?.channels.orEmpty() + ready).distinctBy { it.id }.sortedBy { it.number }
            val notice = ReadyNotice(++lastId, channels)
            shown.value = notice
            clearing?.cancel()
            // Clears only this notice: a clear that was already running when the next batch came must not remove it.
            clearing =
                scope.launch {
                    delay(times.showFor)
                    shown.compareAndSet(notice, null)
                }
        }
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}

/**
 * How long each wait lasts: for more switch-ons to settle, for a notice to stay, and before a failed build is asked
 * again.
 */
data class ReadinessTimes(
    val settle: Duration = 400.milliseconds,
    val showFor: Duration = 8.seconds,
    val retryAfter: Duration = 2.seconds,
)
