// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the opening hands over: the name-in time from the scene, the cap, early and late apps, keys, reduced motion. */
class StanOpeningTimingTest {
    @Test
    fun nameInTimeIsWhenTheSceneHasEveryTitleAlphaAtOne() {
        val at = openingDwellSeconds(reducedMotion = false)
        val settled = requireNotNull(StanScene.Opening.frame(at).title)
        assertEquals(1.0, settled.nameStationAlpha, 0.0)
        assertEquals(1.0, settled.nameBreakAlpha, 0.0)
        assertEquals(1.0, settled.taglineAlpha, 0.0)
        assertEquals(0.0, settled.offsetY, 0.0)
        // A moment earlier the tagline is still coming in, so this is the first moment the name is all in.
        val before = requireNotNull(StanScene.Opening.frame(at - 0.05).title)
        assertTrue(before.taglineAlpha < 1.0)
        assertEquals(1.0, StanScene.Opening.frame(at).fade, 0.0)
    }

    @Test
    fun theDwellNeverExceedsTheScenesRun() {
        assertTrue(openingDwellSeconds(false) <= requireNotNull(StanScene.Opening.runSeconds))
        assertEquals(6.2, requireNotNull(StanScene.Opening.runSeconds), 0.0)
    }

    @Test
    fun anAppReadyEarlyWaitsOnlyUntilTheNameIsIn() {
        val dwell = openingDwellSeconds(false)
        assertEquals(dwell, openingRemainingSeconds(0.0, true, false, false)!!, 1e-9)
        assertEquals(dwell - 1.0, openingRemainingSeconds(1.0, true, false, false)!!, 1e-9)
        assertEquals(0.0, openingRemainingSeconds(dwell, true, false, false)!!, 0.0)
    }

    @Test
    fun anAppReadyLateLeavesAtOnceWithNoWaitAdded() {
        assertEquals(0.0, openingRemainingSeconds(4.0, true, false, false)!!, 0.0)
        assertEquals(0.0, openingRemainingSeconds(30.0, true, false, false)!!, 0.0)
    }

    @Test
    fun anAppNotReadyKeepsPlayingEvenPastTheRunsEnd() {
        assertNull(openingRemainingSeconds(0.0, false, false, false))
        assertNull(openingRemainingSeconds(6.2, false, false, false))
        assertNull(openingRemainingSeconds(60.0, false, false, false))
    }

    @Test
    fun aKeySkipsAtOnceWhenReadyAndWaitsForReadyOtherwise() {
        assertEquals(0.0, openingRemainingSeconds(0.1, true, false, true)!!, 0.0)
        assertNull(openingRemainingSeconds(0.1, false, false, true))
    }

    @Test
    fun reducedMotionDwellsAboutOneSecondOnceReady() {
        assertEquals(1.0, openingDwellSeconds(true), 0.0)
        assertEquals(1.0, openingRemainingSeconds(0.0, true, true, false)!!, 1e-9)
        assertEquals(0.0, openingRemainingSeconds(2.0, true, true, false)!!, 0.0)
        assertNull(openingRemainingSeconds(0.0, false, true, false))
        assertEquals(0.0, openingRemainingSeconds(0.2, true, true, true)!!, 0.0)
    }

    @Test
    fun theStillFrameForReducedMotionShowsTheWholeTitle() {
        val title = requireNotNull(StanScene.Opening.frame(stanTimeSeconds(null, true, 0L)).title)
        assertEquals(1.0, title.nameStationAlpha, 0.0)
        assertEquals(1.0, title.nameBreakAlpha, 0.0)
        assertEquals(1.0, title.taglineAlpha, 0.0)
        assertNotNull(StanScene.Opening.frame(STILL_FRAME_SECONDS))
        assertEquals(1.0, StanScene.Opening.frame(STILL_FRAME_SECONDS).fade, 0.0)
    }

    @Test
    fun theHandoverDissolvesForAThirdOfASecondAndNotWithReducedMotion() {
        assertEquals(300, openingDissolveMillis(reducedMotion = false))
        assertEquals(0, openingDissolveMillis(reducedMotion = true))
        // It adds nothing to the wait for the name: the dissolve starts as the destination is handed the app.
        assertEquals(0.0, openingRemainingSeconds(openingDwellSeconds(false), true, false, false)!!, 0.0)
    }

    @Test
    fun everyKeyButVolumeIsConsumedAsASkipAndBackIsItsOwn() {
        listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter)
            .plus(listOf(Key.Enter, Key.NumPadEnter, Key.Menu, Key.Guide, Key.ChannelUp, Key.ChannelDown))
            .plus(listOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaFastForward, Key.Info))
            .plus(listOf(Key.Captions, Key.A))
            .forEach { assertEquals(StanOpeningKey.Skip, stanOpeningKey(it)) }
        assertEquals(StanOpeningKey.Back, stanOpeningKey(Key.Back))
        listOf(Key.VolumeUp, Key.VolumeDown, Key.VolumeMute)
            .forEach { assertEquals(StanOpeningKey.PassThrough, stanOpeningKey(it)) }
    }
}
