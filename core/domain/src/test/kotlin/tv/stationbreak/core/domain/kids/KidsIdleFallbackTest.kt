// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class KidsIdleFallbackTest {
    private val monotonic = FakeMonotonicClock()
    private val lock = FakeKidsLock(pin = "1234").also { it.current.value = it.current.value.copy(pinSet = true) }

    /** Time passing: the test scheduler's and the monotonic clock's. */
    private fun TestScope.pass(by: Duration) {
        monotonic.ms += by.inWholeMilliseconds
        advanceTimeBy(by)
        runCurrent()
    }

    private fun TestScope.running(fallback: KidsIdleFallback): Job = launch { fallback.run() }.also { runCurrent() }

    @Test
    fun aTvLeftInAdultGoesBackToKidsModeAfterTheTimeout() =
        runTest {
            val job = running(KidsIdleFallback(lock, monotonic))
            pass(1.hours + 59.minutes)
            assertFalse(lock.current.value.enabled)
            pass(2.minutes)
            assertTrue(lock.current.value.enabled)
            job.cancel()
        }

    @Test
    fun aKeyPressStartsTheWaitOver() =
        runTest {
            val fallback = KidsIdleFallback(lock, monotonic)
            val job = running(fallback)
            pass(1.hours + 30.minutes)
            fallback.touch()
            pass(1.hours + 30.minutes)
            assertFalse(lock.current.value.enabled)
            pass(31.minutes)
            assertTrue(lock.current.value.enabled)
            job.cancel()
        }

    @Test
    fun turningKidsModeOffStartsTheWaitFromThen() =
        runTest {
            lock.lock()
            val job = running(KidsIdleFallback(lock, monotonic))
            pass(5.hours)
            lock.unlock()
            runCurrent()
            pass(1.hours + 59.minutes)
            assertFalse(lock.current.value.enabled)
            pass(2.minutes)
            assertTrue(lock.current.value.enabled)
            job.cancel()
        }

    @Test
    fun withKidsModeOnOrNoPinItDoesNothing() =
        runTest {
            val noPin = FakeKidsLock()
            val job = CoroutineScope(coroutineContext).launch { KidsIdleFallback(noPin, monotonic).run() }
            runCurrent()
            pass(5.hours)
            assertFalse(noPin.current.value.enabled)
            job.cancel()
        }

    @Test
    fun aRecheckAfterSleepNoticesTimePassedThatTheTimerDidNotCount() =
        runTest {
            val fallback = KidsIdleFallback(lock, monotonic)
            val job = running(fallback)
            // The TV slept for 3 hours: the monotonic clock moved, the coroutine timer did not.
            monotonic.ms += 3.hours.inWholeMilliseconds
            val before = currentTime
            fallback.recheck()
            runCurrent()
            assertTrue(lock.current.value.enabled)
            assertTrue(currentTime == before)
            job.cancel()
        }

    @Test
    fun aRebootCountsAsActivity() =
        runTest {
            val fallback = KidsIdleFallback(lock, monotonic)
            val job = running(fallback)
            pass(1.hours)
            monotonic.reboot(at = 1_000L)
            fallback.recheck()
            runCurrent()
            assertFalse(lock.current.value.enabled)
            job.cancel()
        }
}
