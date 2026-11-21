// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The idle fallback of the Kids Mode lock (#789, free): a TV left with Kids Mode off goes back to it once nobody has
 * pressed a key for [timeout]. Turning it on needs no PIN, so this only ever narrows what the TV shows.
 *
 * The host calls [touch] for every key press and [recheck] when the app comes back to the foreground (a waiting
 * coroutine does not count the time the TV slept), and runs [run] for as long as the process lives. Idle time is read
 * from [monotonic], which setting the wall clock does not move; a reading that went back (a reboot) counts as activity.
 * Nothing is kept on disk: a process that ends starts its wait over, and a TV that was off stays as it was.
 */
class KidsIdleFallback(
    private val lock: KidsLock,
    private val monotonic: MonotonicClock,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    private val lastActivity = AtomicLong(monotonic.elapsedMillis())
    private val rechecks = MutableStateFlow(0)

    /** Records that someone is there: the wait starts over. Cheap and safe from any thread. */
    fun touch() {
        lastActivity.set(monotonic.elapsedMillis())
    }

    /** Looks at the time again now, for when the process was asleep. */
    fun recheck() {
        rechecks.value++
    }

    /** Never returns. Waits out the idle time whenever Kids Mode is off with a PIN set, then turns it on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        // Kids Mode having just been turned off is someone being there: the wait starts from then.
        val waiting =
            lock.state
                .map { it.waits() }
                .distinctUntilChanged()
                .onEach { if (it) touch() }
        combine(waiting, rechecks) { waits, _ -> waits }
            .mapLatest { waits -> if (waits) waitThenEnable() }
            .collect {}
    }

    private fun KidsLockState.waits(): Boolean = !enabled && pinSet

    private suspend fun waitThenEnable() {
        while (true) {
            var idle = monotonic.elapsedMillis() - lastActivity.get()
            if (idle < 0) {
                // The reading went back: the TV was restarted, which someone did.
                touch()
                idle = 0
            }
            val left = timeout.inWholeMilliseconds - idle
            if (left <= 0) {
                lock.enable()
                return
            }
            delay(left)
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 2.hours
    }
}
