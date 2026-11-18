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
}
