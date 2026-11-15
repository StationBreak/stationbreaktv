// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * [activeAccount] is who is signed in now. [ScheduleSource.airings] answers for that account, so a switch-on made for
 * another one is dropped, not asked about. The work runs on [scope], which outlives any screen.
 */
class BuiltInChannelReadiness(
    private val schedule: ScheduleSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val activeAccount: () -> LibraryAccount?,
    private val settle: Duration = SETTLE,
    private val showFor: Duration = SHOWN_FOR,
    private val retryAfter: Duration = RETRY_AFTER,
) : ChannelReadiness,
    ChannelSwitchOns {
    private data class Held(
        val account: LibraryAccount?,
        val ids: Set<ChannelId>,
    )

    private val held = MutableStateFlow(Held(null, emptySet()))
    private val shown = MutableStateFlow<ReadyNotice?>(null)
    private var settling: Job? = null
    private var clearing: Job? = null
    private var lastId = 0L

    override val pending: Flow<Set<ChannelId>> = held.map { it.ids }.distinctUntilChanged()

    override val notice: StateFlow<ReadyNotice?> = shown.asStateFlow()

    override fun switchedOn(
        account: LibraryAccount,
        id: ChannelId,
    ) {
        held.update { if (it.account == account) it.copy(ids = it.ids + id) else Held(account, setOf(id)) }
        synchronized(this) {
            settling?.cancel()
            settling = scope.launch { settlePending() }
        }
    }

    private suspend fun settlePending() {
        delay(settle)
        val batch = held.value
        if (batch.ids.isEmpty()) return
        val airings = airingsOrNull()
        // The batch is done with, whatever came of it: each of its channels ends here, ready or not.
        held.update { if (it.account == batch.account) it.copy(ids = it.ids - batch.ids) else it }
        if (airings == null || activeAccount() != batch.account) return
        val ready = airings.filter { it.id in batch.ids && it.hidden == null }
        if (ready.isNotEmpty()) show(ready.map { ReadyChannel(it.number, it.name) })
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
                if (attempt < ATTEMPTS - 1) delay(retryAfter)
            }
        }
        return null
    }

    // A second batch while a notice shows joins it, and the notice stays for a full time again.
    private fun show(ready: List<ReadyChannel>) {
        synchronized(this) {
            val before = shown.value
            val channels = (before?.channels.orEmpty() + ready).distinctBy { it.number }.sortedBy { it.number }
            shown.value = ReadyNotice(++lastId, channels)
            clearing?.cancel()
            clearing =
                scope.launch {
                    delay(showFor)
                    shown.value = null
                }
        }
    }

    private companion object {
        /** Long enough for a viewer to switch on a few channels one after the other. */
        val SETTLE = 400.milliseconds

        /** How long a notice stays. */
        val SHOWN_FOR = 8.seconds

        val RETRY_AFTER = 2.seconds
        const val ATTEMPTS = 3
    }
}
