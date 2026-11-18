// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.stan.StanMood

/** Stan dozes after a minute with no key, and any key wakes him (spec, "Dozing"), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DozeTimerTest {
    private fun TestScope.started(
        timer: DozeTimer,
        enabled: Boolean = true,
    ) = backgroundScope.launch { timer.run(enabled) }.also { runCurrent() }

    @Test
    fun dozesAfterAMinuteWithNoKey() =
        runTest {
            val timer = DozeTimer()
            started(timer)
            advanceTimeBy(DOZE_AFTER_MS - 1)
            assertFalse(timer.dozing.value)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(timer.dozing.value)
        }

    @Test
    fun aKeyWakesHimAndStartsTheMinuteAgain() =
        runTest {
            val timer = DozeTimer()
            started(timer)
            advanceTimeBy(DOZE_AFTER_MS)
            runCurrent()
            assertTrue(timer.dozing.value)

            timer.poke()
            assertFalse("a key wakes him at once", timer.dozing.value)
            runCurrent()
            advanceTimeBy(DOZE_AFTER_MS - 1)
            assertFalse(timer.dozing.value)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(timer.dozing.value)
        }

    @Test
    fun aKeyBeforeTheMinutePutsItOff() =
        runTest {
            val timer = DozeTimer()
            started(timer)
            advanceTimeBy(DOZE_AFTER_MS - 1_000)
            timer.poke()
            runCurrent()
            advanceTimeBy(DOZE_AFTER_MS - 1)
            assertFalse(timer.dozing.value)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(timer.dozing.value)
        }

    @Test
    fun neverDozesWhileDisabled() =
        runTest {
            val timer = DozeTimer()
            started(timer, enabled = false)
            advanceTimeBy(DOZE_AFTER_MS * 10)
            runCurrent()
            assertFalse(timer.dozing.value)
        }

    @Test
    fun disablingWakesHim() =
        runTest {
            val timer = DozeTimer()
            val running = started(timer)
            advanceTimeBy(DOZE_AFTER_MS)
            runCurrent()
            assertTrue(timer.dozing.value)

            // A dialog opens: its keys go to its own window, so the scaffold stops counting and Stan is awake.
            running.cancel()
            started(timer, enabled = false)
            assertFalse(timer.dozing.value)
        }

    @Test
    fun aBusyStanNeverDozes() =
        runTest {
            // The first sync runs for minutes with no key while he works the aerial: dozing then would read wrong.
            val timer = DozeTimer()
            started(timer, enabled = dozes(StanCue(StanMood.Busy, R.string.stan_first_sync_busy), covered = false))
            advanceTimeBy(90_000)
            runCurrent()
            assertFalse(timer.dozing.value)
        }

    @Test
    fun onlyAnUncoveredStanWhoIsNotBusyDozes() {
        val idle = StanCue(StanMood.Idle, R.string.stan_server_idle)
        assertTrue(dozes(idle, covered = false))
        assertTrue(dozes(StanCue(StanMood.Shrug, null), covered = false))
        assertFalse("a dialog has the keys", dozes(idle, covered = true))
        assertFalse(dozes(StanCue(StanMood.Busy, R.string.stan_server_testing), covered = false))
    }
}
