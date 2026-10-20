// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ScheduleSource
import kotlin.time.Instant

/** The debug-only device timing probe (spec 12): numbers only in its log line, once per process, never in release. */
class LineupTimingProbeTest {
    @Suppress("InjectDispatcher") // A test: Unconfined runs the probe where it is fired.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val lines = mutableListOf<String>()
    private var calls = 0

    private val source =
        object : ScheduleSource {
            override suspend fun airings(at: Instant): List<ChannelAiring> {
                calls++
                return emptyList()
            }
        }

    @After
    fun stop() = scope.cancel()

    @Test
    fun logsTheTimeAndTheCountAsNumbersOnly() {
        val probe = LineupTimingProbe(scope, { source }, { Instant.fromEpochMilliseconds(0) }) { lines += it }

        probe.onRefreshed()

        assertEquals(1, lines.size)
        assertTrue(lines.single(), Regex("""AiringsTookMs=\d+ channels=0""").matches(lines.single()))
    }

    @Test
    fun runsOnlyForTheFirstRefreshInTheProcess() {
        val probe = LineupTimingProbe(scope, { source }, { Instant.fromEpochMilliseconds(0) }) { lines += it }

        probe.onRefreshed()
        probe.onRefreshed()

        assertEquals(1, calls)
        assertEquals(1, lines.size)
    }

    @Test
    fun releaseBuildsGetNoProbe() {
        assertNull(lineupTimingProbeFor(debug = false, scope, { source }) { lines += it })
        assertNotNull(lineupTimingProbeFor(debug = true, scope, { source }) { lines += it })
        assertEquals(0, calls)
    }
}
