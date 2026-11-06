// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmStandbyTest {
    private var now = 1_000L

    private fun standby(
        config: WarmConfig = WarmConfig(),
        deviceCannot: () -> Boolean = { false },
    ) = WarmStandby(config, deviceCannot) { now }

    private fun skipReason(request: Request): String = (request as Request.Skip).reason

    @Test
    fun startsWhenOnAndNotHeld() {
        val s = standby()
        assertEquals(Request.Start, s.request("w1"))
        assertEquals("w1", s.held)
    }

    @Test
    fun skipsWhenMasterSwitchOff() {
        val s = standby(WarmConfig(warmNextDecoder = false))
        assertEquals("disabled", skipReason(s.request("w1")))
        assertNull(s.held)
    }

    @Test
    fun skipsAlreadyWarmForSameKey() {
        val s = standby()
        s.request("w1")
        assertEquals("alreadyWarm", skipReason(s.request("w1")))
    }

    @Test
    fun replacesDifferentKey() {
        val s = standby()
        s.request("w1")
        assertEquals(Request.Start, s.request("w2"))
        assertEquals("w2", s.held)
    }

    @Test
    fun readyNeedsStateAndDecoder() {
        val s = standby()
        s.request("w1")
        s.onStateReady("w1")
        assertEquals(Verdict.Pending, s.evaluate())
        s.onDecoderUp("w1", 120)
        assertEquals(Verdict.Ready, s.evaluate())
    }

    @Test
    fun decoderMissingAfterGraceDisablesForPlayer() {
        val s = standby()
        s.request("w1")
        s.onStateReady("w1")
        now += 1_499
        assertEquals(Verdict.Pending, s.evaluate())
        now += 1
        assertEquals(Verdict.DecoderMissing, s.evaluate())
        s.decoderMissing()
        assertEquals("disabled", skipReason(s.request("w2")))
    }

    @Test
    fun timesOutAtReadyTimeout() {
        val s = standby()
        s.request("w1")
        now += 14_999
        assertEquals(Verdict.Pending, s.evaluate())
        now += 1
        assertEquals(Verdict.TimedOut, s.evaluate())
    }

    @Test
    fun breakerOpensAtThreeFailuresInARow() {
        val s = standby()
        s.request("w1")
        s.fail(WarmReason.Error)
        s.fail(WarmReason.Error)
        s.swapped()
        s.request("w2")
        s.fail(WarmReason.Error)
        s.fail(WarmReason.Error)
        s.released("test")
        assertEquals(Request.Start, s.request("w3"))
        s.fail(WarmReason.Timeout)
        assertEquals("disabled", skipReason(s.request("w4")))
    }

    @Test
    fun keyMismatchIsCold() {
        val s = standby()
        s.request("p1")
        val d = s.decide("p2")
        assertFalse(d.swap)
        assertEquals(WarmReason.Mismatch, d.reason)
    }

    @Test
    fun standbyGoneIsMismatch() {
        val d = standby().decide("p1")
        assertFalse(d.swap)
        assertEquals(WarmReason.Mismatch, d.reason)
    }

    @Test
    fun nullKeyIsNoHandle() {
        val s = standby()
        s.request("p1")
        assertEquals(WarmReason.NoHandle, s.decide(null).reason)
    }

    @Test
    fun notReadyIsCold() {
        val s = standby()
        s.request("p1")
        s.onStateReady("p1")
        val d = s.decide("p1")
        assertFalse(d.swap)
        assertEquals(WarmReason.NotReady, d.reason)
    }

    @Test
    fun swapNeedsReady() {
        val s = standby()
        s.request("p1")
        s.onStateReady("p1")
        s.onDecoderUp("p1", 90)
        now += 4_000
        val d = s.decide("p1")
        assertTrue(d.swap)
        assertEquals(WarmReason.None, d.reason)
        assertEquals(4_000L, d.ageMs)
        s.swapped()
        assertNull(s.held)
    }

    @Test
    fun failedAndTimedOutStandbyReportThose() {
        val s = standby()
        s.request("p1")
        now += 15_000
        assertEquals(WarmReason.Timeout, s.decide("p1").reason)
        s.fail(WarmReason.Error)
        assertEquals(WarmReason.Error, s.decide("p1").reason)
    }

    @Test
    fun audioErrorRetriesVideoOnly() {
        val s = standby()
        s.request("p1")
        assertTrue(s.audioOn)
        assertEquals(ErrorAction.RetryVideoOnly, s.onError("p1", audio = true))
        assertFalse(s.audioOn)
        assertEquals("p1", s.held)
        assertEquals(ErrorAction.Dropped, s.onError("p1", audio = false))
        // one audio failure plus one video failure: two counted, breaker still closed at three
        assertEquals(Request.Start, s.request("p2"))
    }

    @Test
    fun audioErrorCountsOnce() {
        val s = standby(WarmConfig(warmMaxFailures = 2))
        s.request("p1")
        s.onError("p1", audio = true)
        assertEquals("alreadyWarm", skipReason(s.request("p1")))
    }

    @Test
    fun videoErrorsOpenBreaker() {
        val s = standby(WarmConfig(warmMaxFailures = 1))
        s.request("p1")
        assertEquals(ErrorAction.Disabled, s.onError("p1", audio = false))
    }

    @Test
    fun audioOffByConfig() {
        assertFalse(standby(WarmConfig(warmNextAudio = false)).audioOn)
    }

    @Test
    fun deviceListOffsAtFirstRequest() {
        var calls = 0
        val s = standby(deviceCannot = { calls++ >= 0 })
        assertEquals("disabled", skipReason(s.request("w1")))
        assertEquals("disabled", skipReason(s.request("w1")))
        assertEquals(1, calls)
        assertEquals(WarmReason.Disabled, s.decide("w1").reason)
    }

    @Test
    fun trimPauseSkipsRest() {
        val s = standby()
        s.request("w1")
        s.pauseForVisit()
        s.released("trim")
        assertNull(s.held)
        assertEquals("disabled", skipReason(s.request("w2")))
    }

    @Test
    fun tunablesAreRead() {
        val c = WarmConfig(warmReadyTimeoutMs = 3_000, warmDecoderGraceMs = 200, warmMaxFailures = 1)
        val s = standby(c)
        s.request("w1")
        s.onStateReady("w1")
        now += 200
        assertEquals(Verdict.DecoderMissing, s.evaluate())
        now += 2_800
        assertEquals(Verdict.DecoderMissing, s.evaluate())
        val t = standby(c)
        t.request("w1")
        now += 3_000
        assertEquals(Verdict.TimedOut, t.evaluate())
        t.fail(WarmReason.Timeout)
        assertEquals("disabled", skipReason(t.request("w2")))
    }
}
