// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.feature.live.SwitchFixtures.SPANISH
import tv.stationbreak.feature.live.SwitchFixtures.facts

/** The picker over the Select banner's chips: opening, the suspended clock, Back, and choosing (design doc B2.4). */
class PickerOverlayTest {
    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        fun after(ms: Long) = r.at(r.scope.currentTime + ms)

        val banner: Banner? get() = (r.state as? LiveState.Watching)?.banner
    }

    private fun withFacts(facts: StreamFacts = facts()): suspend () -> TuneOutcome =
        { (FakeTune.plays() as TuneOutcome.Playing).copy(facts = facts) }

    private suspend fun TestScope.keys(first: suspend () -> TuneOutcome = withFacts()): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        r.tune.script(itemOf(1), first)
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private val linger = LiveTimings().actionsLinger.inWholeMilliseconds

    @Test
    fun aPickerOpensOverTheActionsBanner() =
        runTest {
            val k = keys()
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            assertEquals(PickerKind.Audio, k.banner?.picker)
            assertTrue(checkNotNull(k.banner).actions)
        }

    @Test
    fun theBannersClockIsSuspendedWhileAPickerIsOpenAndStartsAgainOnClose() =
        runTest {
            val k = keys()
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Subtitles))
            k.after(linger * 3)
            assertEquals("still open long after the linger", PickerKind.Subtitles, k.banner?.picker)
            k.press(LiveCommand.DismissOverlay)
            assertNull(k.banner?.picker)
            assertTrue("the banner is still up", checkNotNull(k.banner).actions)
            k.after(linger + 500)
            assertNull("the clock ran from the close", k.banner)
        }

    @Test
    fun backClosesThePickerThenTheBanner() =
        runTest {
            val k = keys()
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            k.press(LiveCommand.DismissOverlay)
            assertNull(k.banner?.picker)
            assertTrue(checkNotNull(k.banner).actions)
            k.press(LiveCommand.DismissOverlay)
            assertNull(k.banner)
        }

    @Test
    fun aKeyFromThePictureBringsTheBannerUpWithThePickerOpen() =
        runTest {
            val k = keys()
            k.after(10_000)
            assertNull("no banner over the picture", k.banner)
            k.press(LiveCommand.OpenPicker(PickerKind.Subtitles))
            assertTrue(checkNotNull(k.banner).actions)
            assertEquals(PickerKind.Subtitles, k.banner?.picker)
        }

    @Test
    fun aChipWithNothingToChooseFromDoesNotOpen() =
        runTest {
            val k = keys(withFacts(facts(audio = listOf(SPANISH), subtitles = emptyList())))
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            k.press(LiveCommand.OpenPicker(PickerKind.Subtitles))
            assertNull(k.banner?.picker)
        }

    @Test
    fun aPlayWithNoFactsHasNoPicker() =
        runTest {
            val k = keys { FakeTune.plays() }
            k.after(5_000)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            assertFalse(k.banner?.picker != null)
        }

    @Test
    fun choosingARowClosesThePickerAndTheBannerClockRunsAgain() =
        runTest {
            val k = keys()
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            k.press(LiveCommand.Choose(TrackSelection.Audio(SPANISH.index)))
            assertNull(k.banner?.picker)
            k.after(linger + 500)
            assertNull("the banner went away", k.banner)
        }

    @Test
    fun theChosenTrackShowsOnTheBannerThatComesBack() =
        runTest {
            val k = keys()
            k.after(5_000)
            k.press(LiveCommand.ShowBanner)
            k.press(LiveCommand.OpenPicker(PickerKind.Audio))
            k.press(LiveCommand.Choose(TrackSelection.Audio(SPANISH.index)))
            // An in-place switch: no snow, the same banner with the new facts.
            assertEquals(SPANISH.index, k.banner?.stream?.audioIndex)
        }
}
