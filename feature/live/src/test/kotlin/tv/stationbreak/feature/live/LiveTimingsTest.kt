// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The held-key fallback must outlast the device's own key-repeat timeout (#430), which the app passes in. */
class LiveTimingsTest {
    @Test
    fun theDefaultKeyRepeatTimeoutIsAndroids() {
        assertEquals(500.milliseconds, LiveTimings().keyRepeatTimeout)
    }

    @Test
    fun aFallbackNoLongerThanTheKeyRepeatTimeoutIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveTimings(keyRepeatTimeout = 1.seconds, flipReleaseFallback = 1.seconds)
        }
    }

    @Test
    fun theDevicesTimeoutKeepsTheDefaultFallbackWhenItIsShort() {
        val timings = LiveTimings.forKeyRepeatTimeout(400.milliseconds)
        assertEquals(400.milliseconds, timings.keyRepeatTimeout)
        assertEquals(LiveTimings().flipReleaseFallback, timings.flipReleaseFallback)
    }

    @Test
    fun aLongDeviceTimeoutStretchesTheFallbackPastIt() {
        // A device set to a slow key repeat (an accessibility setting) must not make the fallback fire mid-hold, nor
        // fail the check at start.
        val timings = LiveTimings.forKeyRepeatTimeout(1_500.milliseconds)
        assertEquals(1_500.milliseconds, timings.keyRepeatTimeout)
        assertTrue(timings.flipReleaseFallback > 1_500.milliseconds)
    }

    @Test
    fun pauseReportMaxDefaultsToFiveMinutes() {
        assertEquals(5.minutes, LiveTimings().pauseReportMax)
        assertEquals(5.minutes, LiveTimings.DEFAULT_PAUSE_REPORT_MAX)
    }

    @Test
    fun theBehindPlayBoundsDefaultToTheirMeasuredValues() {
        assertEquals(20.seconds, LiveTimings().goLiveWait)
        assertEquals(2.seconds, LiveTimings().seekLandingSlack)
        assertEquals(250.milliseconds, LiveTimings().holdRearmMin)
    }

    @Test
    fun behindPlayBoundsThatAreNotPositiveAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(goLiveWait = 0.seconds) }
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(seekLandingSlack = (-1).seconds) }
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(holdRearmMin = 0.seconds) }
    }

    @Test
    fun aNegativePauseReportMaxIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(pauseReportMax = (-1).seconds) }
    }

    @Test
    fun theTransportDefaultsAreTenBackThirtyAheadAndAnHourHeld() {
        assertEquals(10.seconds, LiveTimings().stepBack)
        assertEquals(30.seconds, LiveTimings().stepAhead)
        assertEquals(60.minutes, LiveTimings().pauseHoldMax)
    }

    @Test
    fun aTransportTimeThatIsNotPositiveIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(stepBack = 0.seconds) }
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(stepAhead = 0.seconds) }
        assertThrows(IllegalArgumentException::class.java) { LiveTimings(pauseHoldMax = 0.seconds) }
    }
}
