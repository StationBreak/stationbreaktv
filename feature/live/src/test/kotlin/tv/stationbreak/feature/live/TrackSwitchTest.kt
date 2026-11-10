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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.tracks.SubtitlePreference
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleChoice
import tv.stationbreak.core.playback.SubtitleSwitch
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.TrackOverride
import tv.stationbreak.core.playback.TrackSwitch
import tv.stationbreak.feature.live.SwitchFixtures.ENGLISH_SUBS
import tv.stationbreak.feature.live.SwitchFixtures.FRENCH
import tv.stationbreak.feature.live.SwitchFixtures.SOURCE
import tv.stationbreak.feature.live.SwitchFixtures.SPANISH
import tv.stationbreak.feature.live.SwitchFixtures.facts
import kotlin.time.Duration.Companion.minutes

/**
 * A viewer's choice of audio or subtitles on the live channel (design doc B2.7, B3): in place when the player can,
 * else a retune under the snow, "Switching ...", with a fallback to the previous choice and "Couldn't switch".
 */
class TrackSwitchTest {
    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        /** The transport handle of the latest play of item 1: a retune makes a new one. */
        val control get() = checkNotNull(r.tune.controlOf(itemOf(1)))

        /** Lets the snow's minimum run out (virtual time), well inside the banner's linger. */
        fun settle() = r.at(r.scope.currentTime + SETTLE_MS)

        val banner: Banner? get() = (r.state as? LiveState.Watching)?.banner

        val tunesOfOne get() = r.tunes.filter { it.item == itemOf(1) }
    }

    /** A play that answers with [facts], as the adapter does. */
    private fun withFacts(facts: StreamFacts = facts()): suspend () -> TuneOutcome =
        { (FakeTune.plays() as TuneOutcome.Playing).copy(facts = facts) }

    private suspend fun TestScope.keys(vararg scripts: suspend () -> TuneOutcome): Keys {
        val setup: Rig.() -> Unit = { tune.script(itemOf(1), *scripts) }
        return keysFor(setup)
    }

    /** CH7 with item 1 from minute 0 to 60 and item 2 to 120, joined at minute 10; [setup] scripts the port. */
    private suspend fun TestScope.keysFor(setup: Rig.() -> Unit): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        r.setup()
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }

    private fun audio(index: Int) = LiveCommand.Choose(TrackSelection.Audio(index))

    private fun subtitles(choice: SubtitleChoice) = LiveCommand.Choose(TrackSelection.Subtitles(choice))

    @Test
    fun anInstantAudioSwitchTunesNothingAndTheBannerSaysTheNewTrack() =
        runTest {
            val k = keys(withFacts())
            k.r.atMin(1)
            k.press(audio(SPANISH.index))
            assertEquals(listOf(TrackSwitch(audio = SPANISH.pick)), k.control.switches)
            assertEquals("no new tune", 1, k.tunesOfOne.size)
            k.press(LiveCommand.ShowBanner)
            assertEquals(SPANISH.index, k.banner?.stream?.audioIndex)
            assertEquals(listOf<Remembered>(Remembered.AudioLanguage("es")), k.r.tune.remembered)
        }

    @Test
    fun anInstantSubtitleSwitchPicksTheTextTrackAndOffTurnsItOff() =
        runTest {
            val k = keys(withFacts())
            k.r.atMin(1)
            k.press(subtitles(SubtitleChoice.Track(ENGLISH_SUBS.index)))
            k.press(subtitles(SubtitleChoice.Off))
            assertEquals(
                listOf(
                    TrackSwitch(subtitles = SubtitleSwitch.To(ENGLISH_SUBS.pick!!)),
                    TrackSwitch(subtitles = SubtitleSwitch.Off),
                ),
                k.control.switches,
            )
            assertEquals(1, k.tunesOfOne.size)
            assertEquals(
                listOf<Remembered>(
                    Remembered.Subtitles(SubtitlePreference.On("en")),
                    Remembered.Subtitles(SubtitlePreference.Off),
                ),
                k.r.tune.remembered,
            )
        }

    @Test
    fun aTrackThePlayerCannotSwitchToRetunesAtThePlaceWithTheChoice() =
        runTest {
            val k = keys(withFacts(), withFacts(facts(audioIndex = FRENCH.index)))
            k.r.atMin(1)
            k.press(audio(FRENCH.index))
            k.settle()
            val second = k.tunesOfOne.last()
            assertEquals(2, k.tunesOfOne.size)
            assertEquals(TrackChoice(SOURCE, TrackOverride(FRENCH.index, SubtitleChoice.Default)), second.choice)
            assertEquals("at the place, not the start", 11 * MIN, second.start.inWholeMilliseconds)
            assertTrue(k.control.switches.isEmpty())
            val labels =
                k.r.seen
                    .filterIsInstance<LiveState.Tuning>()
                    .map { it.banner.label }
            assertTrue("under the snow, switching", BannerLabel.SwitchingAudio in labels)
            assertEquals("then it plays, as Now", BannerLabel.Now, k.banner?.label)
            assertFalse(checkNotNull(k.banner).switchFailed)
            assertEquals(FRENCH.index, k.banner?.stream?.audioIndex)
            assertEquals(listOf<Remembered>(Remembered.AudioLanguage("fr")), k.r.tune.remembered)
        }

    @Test
    fun aSwitchTheControlRefusesFallsBackToARetune() =
        runTest {
            val k = keys(withFacts(), withFacts(facts(audioIndex = SPANISH.index)))
            k.r.atMin(1)
            val first = k.control
            first.switchTaken = false
            k.press(audio(SPANISH.index))
            assertEquals(1, first.switches.size)
            assertEquals(2, k.tunesOfOne.size)
            assertEquals(
                SPANISH.index,
                k.tunesOfOne
                    .last()
                    .choice
                    ?.tracks
                    ?.audioIndex,
            )
        }

    @Test
    fun aRetuneThatDiesGoesBackToThePreviousChoiceAndSaysSo() =
        runTest {
            val k = keys(withFacts(), FakeTune.dead(), withFacts())
            k.r.atMin(1)
            k.press(audio(FRENCH.index))
            k.settle()
            assertEquals(3, k.tunesOfOne.size)
            assertEquals(
                FRENCH.index,
                k.tunesOfOne[1]
                    .choice
                    ?.tracks
                    ?.audioIndex,
            )
            assertNull("the previous choice: none was made", k.tunesOfOne[2].choice)
            assertTrue("one banner says it", checkNotNull(k.banner).switchFailed)
            assertTrue(
                "a failed choice leaves nothing behind",
                k.r.tune.remembered
                    .isEmpty(),
            )
        }

    @Test
    fun aFallbackKeepsAnEarlierChoiceOfThePreviousSwitch() =
        runTest {
            val k = keys(withFacts(), withFacts(facts(audioIndex = SPANISH.index)), FakeTune.dead(), withFacts())
            k.r.atMin(1)
            k.press(subtitles(SubtitleChoice.Default))
            k.settle()
            val chose = k.tunesOfOne[1].choice
            k.press(audio(FRENCH.index))
            k.settle()
            // The failed choice is undone to what the viewer had before it, not to nothing.
            assertEquals(chose, k.tunesOfOne.last().choice)
        }

    @Test
    fun whenNeitherChoicePlaysNothingIsKept() =
        runTest {
            val k = keys(withFacts(), FakeTune.dead(), FakeTune.dead())
            k.r.atMin(1)
            k.press(audio(FRENCH.index))
            k.settle()
            assertTrue(
                k.r.tune.remembered
                    .isEmpty(),
            )
            assertTrue(k.tunesOfOne.size >= 3)
        }

    @Test
    fun theWayBackToServerDecidesRetunesAndForgetsTheSubtitleChoice() =
        runTest {
            val k = keys(withFacts(), withFacts())
            k.r.atMin(1)
            k.press(subtitles(SubtitleChoice.Default))
            k.settle()
            assertEquals(
                SubtitleChoice.Default,
                k.tunesOfOne
                    .last()
                    .choice
                    ?.tracks
                    ?.subtitle,
            )
            assertEquals(listOf<Remembered>(Remembered.Subtitles(null)), k.r.tune.remembered)
            assertTrue(
                "a switch of subtitles says so under the snow",
                k.r.seen.filterIsInstance<LiveState.Tuning>().any {
                    it.banner.label ==
                        BannerLabel.SwitchingSubtitles
                },
            )
        }

    @Test
    fun aFailedPlayAfterAnInstantSwitchIsTunedAgainWithTheViewersChoice() =
        runTest {
            val k =
                keysFor {
                    val failing = tune.playsThenEnds(5.minutes, PlaybackEnd.Failed("Boom", positionMs = 15 * MIN))
                    val first: suspend () -> TuneOutcome = { (failing() as TuneOutcome.Playing).copy(facts = facts()) }
                    tune.script(itemOf(1), first, withFacts(facts(audioIndex = SPANISH.index)))
                }
            k.r.atMin(1)
            k.press(audio(SPANISH.index))
            k.r.atMin(8)
            val retry = k.tunesOfOne.last()
            assertEquals(2, k.tunesOfOne.size)
            assertEquals(SPANISH.index, retry.choice?.tracks?.audioIndex)
        }

    @Test
    fun aChoiceForOneProgrammeIsNotCarriedIntoTheNextByTheSitting() =
        runTest {
            val k = keys(withFacts())
            k.r.atMin(1)
            k.press(audio(SPANISH.index))
            // Item 2 starts at minute 60: its tune carries no choice of the sitting (the store's language does).
            k.r.atMin(61)
            val of2 =
                k.r.tune.calls.filter {
                    (it as? Call.Tune)?.item == itemOf(2) || (it as? Call.TuneHandle)?.item == itemOf(2)
                }
            assertTrue("item 2 was tuned", of2.isNotEmpty())
            assertTrue("with no choice of the sitting's", of2.all { (it as? Call.Tune)?.choice == null })
        }
}
