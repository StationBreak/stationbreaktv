// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmStandbyTest {
    private var now = 1_000L
    private val logs = mutableListOf<String>()

    private fun standby(
        config: WarmConfig = WarmConfig(warmNextDecoder = true),
        deviceCannot: () -> String? = { null },
    ) = WarmStandby(config, WarmSwitches.None, deviceCannot, { now }, { logs += it })

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

    private fun failOn(
        s: WarmStandby,
        key: String,
    ) {
        s.request(key)
        s.fail(WarmReason.Error)
    }

    @Test
    fun breakerOpensAtThreeFailuresInARow() {
        val s = standby()
        failOn(s, "w1")
        failOn(s, "w2")
        s.swapped()
        failOn(s, "w3")
        failOn(s, "w4")
        assertEquals(Request.Start, s.request("w5"))
        s.fail(WarmReason.Timeout)
        assertEquals("disabled", skipReason(s.request("w6")))
        assertTrue(logs.any { it == "disabled reason=timeout failures=3" })
    }

    @Test
    fun failTwiceCountsOnce() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 2))
        s.request("w1")
        s.fail(WarmReason.Error)
        s.fail(WarmReason.Error)
        assertEquals(Request.Start, s.request("w2"))
        s.fail(WarmReason.Error)
        assertEquals("disabled", skipReason(s.request("w3")))
    }

    @Test
    fun decoderMissingTwiceCountsOnce() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 5))
        s.request("w1")
        s.decoderMissing()
        s.decoderMissing()
        assertEquals(1, logs.count { it.startsWith("disabled reason=decoderMissing") })
        assertTrue(logs.contains("disabled reason=decoderMissing failures=1"))
    }

    @Test
    fun failWithNothingHeldIsIgnored() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        s.fail(WarmReason.Error)
        assertEquals(Request.Start, s.request("w1"))
    }

    @Test
    fun readyThenFailIsCold() {
        val s = standby()
        s.request("p1")
        s.onStateReady("p1")
        s.onDecoderUp("p1", 50)
        s.fail(WarmReason.Error)
        val d = s.decide("p1")
        assertFalse(d.swap)
        assertEquals(WarmReason.Error, d.reason)
    }

    @Test
    fun readyWithBreakerOpenIsCold() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        s.request("p1")
        s.onStateReady("p1")
        s.onDecoderUp("p1", 50)
        s.fail(WarmReason.Error)
        s.request("p2")
        val d = s.decide("p1")
        assertFalse(d.swap)
        assertEquals(WarmReason.Disabled, d.reason)
    }

    @Test
    fun masterOffDecideIsDisabled() {
        val s = standby(WarmConfig(warmNextDecoder = false))
        val d = s.decide("p1")
        assertFalse(d.swap)
        assertEquals(WarmReason.Disabled, d.reason)
    }

    @Test
    fun sameKeyRebuildAfterFailureDoesNotLogReplaced() {
        val s = standby()
        s.request("p1")
        s.fail(WarmReason.Error)
        assertEquals(Request.Start, s.request("p1"))
        assertFalse(logs.any { it.startsWith("dropped reason=replaced") })
        assertEquals(WarmReason.NotReady, s.decide("p1").reason)
    }

    @Test
    fun staleEventsFromAnOlderStandbyAreIgnored() {
        val s = standby()
        s.request("p1")
        val old = s.epoch
        s.onError("p1", audio = true)
        assertTrue(s.epoch != old)
        s.onStateReady("p1", old)
        s.onDecoderUp("p1", 10, old)
        assertEquals(ErrorAction.Ignored, s.onError("p1", audio = false, epoch = old))
        assertEquals(Verdict.Pending, s.evaluate())
        s.onStateReady("p1", s.epoch)
        s.onDecoderUp("p1", 10, s.epoch)
        assertEquals(Verdict.Ready, s.evaluate())
        s.request("p2")
        assertEquals(ErrorAction.Ignored, s.onError("p1", audio = false))
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
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 2))
        s.request("p1")
        assertTrue(s.audioOn)
        assertEquals(ErrorAction.RetryVideoOnly, s.onError("p1", audio = true))
        assertFalse(s.audioOn)
        assertEquals("p1", s.held)
        // counted once: one of two, so the breaker is still closed
        assertEquals(Request.Start, s.request("p2"))
        assertEquals(ErrorAction.Disabled, s.onError("p2", audio = false))
    }

    @Test
    fun videoErrorAfterAudioRetryIsDropped() {
        val s = standby()
        s.request("p1")
        s.onError("p1", audio = true)
        assertEquals(ErrorAction.Dropped, s.onError("p1", audio = false))
        assertEquals(Request.Start, s.request("p2"))
        s.fail(WarmReason.Error)
        assertEquals("disabled", skipReason(s.request("p3")))
    }

    @Test
    fun audioErrorCountsOnce() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 2))
        s.request("p1")
        s.onError("p1", audio = true)
        assertEquals("alreadyWarm", skipReason(s.request("p1")))
    }

    @Test
    fun videoErrorsOpenBreaker() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        s.request("p1")
        assertEquals(ErrorAction.Disabled, s.onError("p1", audio = false))
    }

    @Test
    fun audioOffByConfig() {
        assertFalse(standby(WarmConfig(warmNextDecoder = true, warmNextAudio = false)).audioOn)
    }

    @Test
    fun deviceListOffsAtFirstRequest() {
        var calls = 0
        val s =
            standby(deviceCannot = {
                calls++
                "deviceList"
            })
        assertEquals("disabled", skipReason(s.request("w1")))
        assertEquals("disabled", skipReason(s.request("w1")))
        assertEquals(1, calls)
        assertEquals(1, logs.count { it == "disabled reason=deviceList failures=0" })
        assertEquals(WarmReason.Disabled, s.decide("w1").reason)
    }

    @Test
    fun trimPauseSkipsRest() {
        val s = standby()
        s.request("w1")
        s.trim(RUNNING_LOW)
        s.released("trim")
        assertNull(s.held)
        assertEquals("disabled", skipReason(s.request("w2")))
    }

    @Test
    fun runningModerateIsLoggedAndNothingElse() {
        val s = standby()
        s.request("w1")
        assertEquals(TrimAction.Ignored, s.trim(RUNNING_MODERATE))
        assertEquals("w1", s.held)
        assertFalse(s.disabled)
        assertEquals(1, logs.count { it == "trim level=5 action=ignored" })
        assertTrue(logs.none { it.startsWith("disabled") })
        assertEquals("alreadyWarm", skipReason(s.request("w1")))
    }

    @Test
    fun runningLowAndCriticalPauseWarmingForTheVisit() {
        for (level in listOf(RUNNING_LOW, RUNNING_CRITICAL)) {
            logs.clear()
            val s = standby()
            s.request("w1")
            assertEquals(TrimAction.Paused, s.trim(level))
            assertTrue(s.disabled)
            assertEquals("disabled", skipReason(s.request("w2")))
            assertEquals(1, logs.count { it == "trim level=$level action=paused" })
            assertEquals(1, logs.count { it == "disabled reason=trim level=$level" })
        }
    }

    @Test
    fun aSecondPausingTrimLogsTheTrimButDisablesOnlyOnce() {
        val s = standby()
        s.trim(RUNNING_LOW)
        s.trim(RUNNING_CRITICAL)
        assertEquals(1, logs.count { it.startsWith("disabled reason=trim") })
        assertEquals(1, logs.count { it == "trim level=15 action=paused" })
    }

    @Test
    fun hiddenAndBackgroundLevelsReleaseButNeverPause() {
        for (level in listOf(UI_HIDDEN, 40, 60, 80)) {
            logs.clear()
            val s = standby()
            s.request("w1")
            assertEquals(TrimAction.Released, s.trim(level))
            assertFalse(s.disabled)
            assertEquals(1, logs.count { it == "trim level=$level action=released" })
            assertTrue(logs.none { it.startsWith("disabled") })
            // A later warm is built again.
            assertEquals(Request.Start, s.request("w2"))
        }
    }

    @Test
    fun theTrimThresholdIsATunable() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmTrimLevel = RUNNING_CRITICAL))
        assertEquals(TrimAction.Ignored, s.trim(RUNNING_LOW))
        assertFalse(s.disabled)
        assertEquals(TrimAction.Paused, s.trim(RUNNING_CRITICAL))
        val lowest = standby(WarmConfig(warmNextDecoder = true, warmTrimLevel = RUNNING_MODERATE))
        assertEquals(TrimAction.Paused, lowest.trim(RUNNING_MODERATE))
    }

    @Test
    fun aLevelBelowTheRunningLevelsIsIgnored() {
        val s = standby()
        assertEquals(TrimAction.Ignored, s.trim(0))
        assertFalse(s.disabled)
    }

    @Test
    fun tunablesAreRead() {
        val c =
            WarmConfig(
                warmNextDecoder = true,
                warmReadyTimeoutMs = 3_000,
                warmDecoderGraceMs = 200,
                warmMaxFailures = 2,
            )
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
        assertEquals(Request.Start, t.request("w2"))
        t.fail(WarmReason.Timeout)
        assertEquals("disabled", skipReason(t.request("w3")))
    }

    @Test
    fun droppedClearsTheHeldStandbyWithoutAFailure() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        s.request("w1")
        s.dropped("fallback")
        assertNull(s.held)
        assertTrue(logs.any { it == "dropped reason=fallback key=w1" })
        // Not counted: with one failure allowed, warming is still on.
        assertEquals(Request.Start, s.request("w2"))
    }

    @Test
    fun droppedWithNothingHeldSaysNothing() {
        val s = standby()
        s.dropped("dropped")
        assertTrue(logs.isEmpty())
    }

    @Test
    fun disabledFollowsTheBreakerTheSwitchAndTheTrim() {
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        assertFalse(s.disabled)
        s.request("w1")
        s.fail(WarmReason.Timeout)
        assertTrue(s.disabled)
        val off = standby(WarmConfig(warmNextDecoder = false))
        assertTrue(off.disabled)
        val trimmed = standby()
        trimmed.trim(RUNNING_LOW)
        assertTrue(trimmed.disabled)
    }

    @Test
    fun disabledOnTheDeviceListOnceAsked() {
        val s = standby(deviceCannot = { "deviceList" })
        s.request("w1")
        assertTrue(s.disabled)
    }

    @Test
    fun readyAndFallbackLinesCarryTheExtraFields() {
        val s =
            WarmStandby(
                WarmConfig(warmNextDecoder = true),
                WarmSwitches.None,
                { null },
                { now },
                { logs += it },
                StandbyFields(ready = { "framesDecoded=3" }, fallback = { "javaKb=1" }),
            )
        s.request("w1")
        s.onStateReady("w1")
        s.onDecoderUp("w1", 40)
        assertTrue(logs.any { it.startsWith("ready ") && it.endsWith(" framesDecoded=3") })
        s.decide("w2")
        assertTrue(logs.any { it.startsWith("fallback ") && it.endsWith(" javaKb=1") })
    }

    @Test
    fun mismatchWithNothingHeldLogsNoFallback() {
        val s = standby()
        val decision = s.decide("w1")
        assertFalse(decision.swap)
        assertEquals(WarmReason.Mismatch, decision.reason)
        assertTrue("logged $logs", logs.none { it.startsWith("fallback") })
    }

    @Test
    fun aReadyStandbyThePlayerCannotUseCountsTowardTheBreaker() {
        // The player's own check at the swap (an error not yet delivered, or not READY): fail, then drop.
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 3))
        repeat(3) { n ->
            val key = "w$n"
            assertEquals(Request.Start, s.request(key))
            s.onStateReady(key, s.epoch)
            s.onDecoderUp(key, 10, s.epoch)
            assertTrue(s.decide(key).swap)
            s.fail(WarmReason.NotReady)
            s.dropped(WarmReason.NotReady.label)
        }
        assertTrue(s.disabled)
        assertEquals("disabled", skipReason(s.request("w9")))
    }

    @Test
    fun aSourceThatChangedIsAMismatch() {
        // The handle is the standby's, but the source it was built from is not this play's (a route fell back, an
        // item was replaced): cold, logged as a fallback, and not the standby's failure.
        val s = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        s.request("w1")
        s.onStateReady("w1")
        s.onDecoderUp("w1", 10)
        val decision = s.decide("w1", sameSource = false)
        assertFalse(decision.swap)
        assertEquals(WarmReason.Mismatch, decision.reason)
        assertTrue("logged $logs", logs.any { it.startsWith("fallback reason=mismatch") })
        s.dropped(decision.reason.label)
        assertFalse("a changed source opened the breaker", s.disabled)
        assertTrue(s.decide("w1").reason == WarmReason.Mismatch)
    }

    @Test
    fun aDeviceThatCannotSaysWhy() {
        // The reason is the device check's: the denylist, a missing decoder, Media3's list, or a check that failed.
        val s = standby(deviceCannot = { "deviceCheck" })
        assertEquals("disabled", skipReason(s.request("w1")))
        assertEquals(listOf("disabled reason=deviceCheck failures=0", "skipped key=w1 reason=disabled"), logs)
        assertTrue(s.disabled)
    }

    @Test
    fun theOnlyCheckBeforeReadyIsTheReadyTimeout() {
        val s = standby()
        assertNull("nothing held, nothing to check", s.nextCheckInMs())
        s.request("w1")
        assertEquals(15_000L, s.nextCheckInMs())
        now += 4_000
        assertEquals(11_000L, s.nextCheckInMs())
    }

    @Test
    fun onceReadyTheDecoderGraceIsChecked() {
        val s = standby()
        s.request("w1")
        now += 2_000
        s.onStateReady("w1")
        assertEquals(1_500L, s.nextCheckInMs())
        now += 1_500
        assertEquals(0L, s.nextCheckInMs())
        assertEquals(Verdict.DecoderMissing, s.evaluate())
    }

    @Test
    fun theTimeoutComesFirstWhenItIsSooner() {
        val s = standby()
        s.request("w1")
        now += 14_000
        s.onStateReady("w1")
        assertEquals(1_000L, s.nextCheckInMs())
        now += 1_000
        assertEquals(Verdict.TimedOut, s.evaluate())
    }

    @Test
    fun aReadyOrFailedStandbyNeedsNoCheck() {
        val s = standby()
        s.request("w1")
        s.onStateReady("w1")
        s.onDecoderUp("w1", 10)
        assertNull(s.nextCheckInMs())
        s.request("w2")
        s.fail(WarmReason.Timeout)
        assertNull(s.nextCheckInMs())
    }

    @Test
    fun anAudioRetryStartsTheTimeoutAgain() {
        val s = standby()
        s.request("w1")
        now += 5_000
        assertEquals(ErrorAction.RetryVideoOnly, s.onError("w1", audio = true))
        assertEquals(15_000L, s.nextCheckInMs())
    }

    @Test
    fun aDisabledSkipIsSaidOncePerPlayer() {
        // The warm calls go on while warming is off; the line would repeat on every flip with nothing new to say.
        val s = standby(WarmConfig(warmNextDecoder = false))
        repeat(3) { n -> assertEquals("disabled", skipReason(s.request("w$n"))) }
        assertEquals(listOf("skipped key=w0 reason=disabled"), logs.filter { it.startsWith("skipped") })
    }

    @Test
    fun anAlreadyWarmSkipIsSaidEachTime() {
        val s = standby()
        s.request("w1")
        s.request("w1")
        s.request("w1")
        assertEquals(2, logs.count { it == "skipped key=w1 reason=alreadyWarm" })
    }

    @Test
    fun theSameSourceAgainReusesTheHeldHandle() {
        // A second warm of the source already held keeps the handle and does not prepare again.
        val s = standby()
        s.request("w1")
        assertEquals("w1", s.reuseFor(sameSource = true))
        assertEquals("w1", s.held)
        assertTrue(logs.contains("skipped key=w1 reason=alreadyWarm"))
        assertNull("another source is a new standby", s.reuseFor(sameSource = false))
    }

    @Test
    fun aFailedOrDisabledStandbyIsNotReused() {
        val s = standby()
        assertNull("nothing held", s.reuseFor(sameSource = true))
        s.request("w1")
        s.fail(WarmReason.Timeout)
        assertNull("failed", s.reuseFor(sameSource = true))
        val off = standby(WarmConfig(warmNextDecoder = true, warmMaxFailures = 1))
        off.request("w1")
        off.trim(RUNNING_LOW)
        assertNull("disabled", off.reuseFor(sameSource = true))
    }

    @Test
    fun twoPlayersShareTheDecoderMissingLatch() {
        // One DeviceWarmth for the process: the probe failing on one player turns warming off for the next one.
        val device = DeviceWarmth({ "AFTKRT" }) { false }
        val denylist = WarmConfig(warmNextDecoder = true).warmDeviceDenylist
        val first = standby(deviceCannot = { device.cannot(denylist) })
        val second = standby(deviceCannot = { device.cannot(denylist) })
        assertEquals(Request.Start, first.request("w1"))
        first.onStateReady("w1")
        now += 1_500
        assertEquals(Verdict.DecoderMissing, first.evaluate())
        first.decoderMissing()
        device.markDecoderMissing()
        assertEquals("disabled", skipReason(second.request("w1")))
        assertTrue(logs.contains("disabled reason=decoderMissing failures=0"))
    }

    private companion object {
        // ComponentCallbacks2 levels (the platform's numbers).
        const val RUNNING_MODERATE = 5
        const val RUNNING_LOW = 10
        const val RUNNING_CRITICAL = 15
        const val UI_HIDDEN = 20
    }
}
