// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.lineup.ScheduleSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A debug-build probe for the on-device check of spec 12: after the first completed lineup refresh in the process it
 * times one `ScheduleSource.airings` call and logs the milliseconds and the number of airings, as numbers only. Nothing
 * in the app calls `airings` yet, so this is the only way to measure it on the device.
 */
internal class LineupTimingProbe(
    private val scope: CoroutineScope,
    private val source: () -> ScheduleSource,
    private val now: () -> Instant,
    private val log: (String) -> Unit,
) {
    private val started = AtomicBoolean(false)

    /** Called after each completed refresh; only the first one in the process starts the probe. */
    fun onRefreshed() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val began = TimeSource.Monotonic.markNow()
            val airings = source().airings(now())
            log("AiringsTookMs=${began.elapsedNow().inWholeMilliseconds} channels=${airings.size}")
        }
    }
}

/** The probe for a debug build; null for a release build, which therefore never has one to run. */
internal fun lineupTimingProbeFor(
    debug: Boolean,
    scope: CoroutineScope,
    source: () -> ScheduleSource,
    log: (String) -> Unit,
): LineupTimingProbe? = if (debug) LineupTimingProbe(scope, source, { Clock.System.now() }, log) else null
