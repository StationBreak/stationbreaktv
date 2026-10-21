// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SeekCheckTest {
    @Test
    fun withinTwoSecondsLands() {
        assertEquals(SeekVerdict.Landed, SeekCheck.check(10.minutes, 10.minutes + 1999.milliseconds))
    }

    @Test
    fun exactlyTwoSecondsLands() {
        assertEquals(SeekVerdict.Landed, SeekCheck.check(10.minutes, 10.minutes + 2000.milliseconds))
    }

    @Test
    fun overTwoSecondsMisses() {
        assertEquals(SeekVerdict.Missed(2001.milliseconds), SeekCheck.check(10.minutes, 10.minutes + 2001.milliseconds))
    }

    @Test
    fun theToleranceCanBeSet() {
        assertEquals(SeekVerdict.Landed, SeekCheck.check(10.minutes, 10.minutes + 5.seconds, 5.seconds))
        assertEquals(
            SeekVerdict.Missed(5001.milliseconds),
            SeekCheck.check(10.minutes, 10.minutes + 5001.milliseconds, 5.seconds),
        )
    }

    @Test
    fun landingEarlyCountsToo() {
        assertEquals(SeekVerdict.Landed, SeekCheck.check(10.minutes, 10.minutes - 1.seconds))
    }

    @Test
    fun landingAtZeroFromTwentyMinutesMisses() {
        assertEquals(SeekVerdict.Missed(20.minutes), SeekCheck.check(20.minutes, 0.seconds))
    }
}
