// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.stan.StanScene
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.CardNote
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.R
import java.io.File

/** Which of Stan's screens each live state shows, with which copy; and the viewer's choice of Stan or snow. */
class StanSpecsTest {
    private val time: (Long) -> String = { "t$it" }

    private fun banner(
        label: BannerLabel = BannerLabel.UpNext,
        title: String? = "Test Movie",
    ) = Banner(7, "Test Channel", label, title, null, 1_000L, 2_000L, null)

    private fun spec(
        state: LiveState,
        change: ChannelChangeStyle = ChannelChangeStyle.Stan,
        withBanner: Boolean = true,
    ) = stanSpecOf(state, change, withBanner, time)

    @Test
    fun tuningIsTheStandbyScreenWithTheChannelAndTheProgramme() {
        val spec = checkNotNull(spec(LiveState.Tuning(banner(BannerLabel.TuningIn))))
        assertEquals(StanScene.Standby, spec.scene)
        assertEquals(Phrase(R.string.live_stan_standby_headline), spec.headline)
        assertEquals(Phrase(R.string.live_stan_tuning_status, listOf(7)), spec.status)
        assertEquals(Phrase(R.string.live_stan_programme, listOf("Test Movie")), spec.detail)
        assertEquals(LiveTags.STAN_STANDBY, spec.tag)
    }

    @Test
    fun tuningWithNoProgrammeNameHasNoDetail() {
        assertNull(checkNotNull(spec(LiveState.Tuning(banner(BannerLabel.TuningIn, title = null)))).detail)
        assertNull(checkNotNull(spec(LiveState.Tuning(banner(BannerLabel.TuningIn, title = "  ")))).detail)
    }

    @Test
    fun theBannerAnnouncesATuneSoTheScreenDoesNotRepeatIt() {
        assertNull(checkNotNull(spec(LiveState.Tuning(banner(BannerLabel.TuningIn)))).spoken)
    }

    @Test
    fun flippingOverNoPictureIsTheStandbyScreenForTheTargetChannel() {
        val spec = checkNotNull(spec(LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = false)))
        assertEquals(StanScene.Standby, spec.scene)
        assertEquals(Phrase(R.string.live_stan_tuning_status, listOf(12)), spec.status)
        assertNull(spec.detail)
    }

    @Test
    fun flippingOverThePictureHasNoScreenOfItsOwn() {
        assertNull(spec(LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = true)))
    }

    @Test
    fun technicalDifficultiesIsTheTroubleScreenAndSaysItPlainly() {
        val spec = checkNotNull(spec(LiveState.TechnicalDifficulties(banner())))
        assertEquals(StanScene.Trouble, spec.scene)
        assertEquals(Phrase(R.string.live_stan_trouble_headline), spec.headline)
        assertEquals(Phrase(R.string.live_stan_trouble_status), spec.status)
        assertEquals(Phrase(R.string.live_stan_trouble_detail), spec.detail)
        // With the up-next banner over it, Stan says only the plain status.
        assertEquals(Phrase(R.string.live_stan_spoken_trouble_status), spec.spoken)
        assertEquals(LiveTags.DIFFICULTIES, spec.tag)
    }

    @Test
    fun anIdentWithTheNextProgrammeSaysWhenTheChannelReturns() {
        val spec = checkNotNull(spec(LiveState.Ident(banner(), ChannelTag(7, "Test Channel"))))
        assertEquals(StanScene.OffAir, spec.scene)
        assertEquals(Phrase(R.string.live_stan_offair_headline), spec.headline)
        assertEquals(Phrase(R.string.live_stan_offair_status), spec.status)
        assertEquals(Phrase(R.string.live_stan_offair_returns, listOf("Test Channel", "t1000")), spec.detail)
        // The banner over the screen says the rest, so Stan says only the plain status.
        assertEquals(Phrase(R.string.live_stan_spoken_offair), spec.spoken)
        assertEquals(LiveTags.STAN_OFF_AIR, spec.tag)
    }

    @Test
    fun withNoBannerStanSpeaksTheWholeLine() {
        // In the guide's corner no banner is drawn, so the plain status is followed by what the banner would say.
        val trouble = checkNotNull(spec(LiveState.TechnicalDifficulties(banner()), withBanner = false))
        assertEquals(Phrase(R.string.live_stan_spoken_trouble), trouble.spoken)
        val returns = checkNotNull(spec(LiveState.Ident(banner(), ChannelTag(7, "Test Channel")), withBanner = false))
        assertEquals(Phrase(R.string.live_stan_spoken_offair_returns, listOf("Test Channel", "t1000")), returns.spoken)
        // And a card with no banner at all speaks the whole line even on the full screen.
        val bare = checkNotNull(spec(LiveState.TechnicalDifficulties(null)))
        assertEquals(Phrase(R.string.live_stan_spoken_trouble), bare.spoken)
    }

    @Test
    fun anIdentWithNothingToSayHasTheStatusAlone() {
        val spec = checkNotNull(spec(LiveState.Ident(null)))
        assertNull(spec.detail)
        assertEquals(Phrase(R.string.live_stan_spoken_offair), spec.spoken)
    }

    @Test
    fun aProgrammeThatLeftTheLibraryKeepsItsMeaning() {
        val spec = checkNotNull(spec(LiveState.Ident(null, null, CardNote.NotInLibrary)))
        assertEquals(Phrase(R.string.live_stan_offair_next), spec.detail)
        assertEquals(Phrase(R.string.live_stan_spoken_offair_next), spec.spoken)
    }

    @Test
    fun aChannelOffTheAirSaysWhereLiveMovedTo() {
        val spec = checkNotNull(spec(LiveState.Ident(banner(), null, CardNote.OffTheAir("Test Channel", 12))))
        assertEquals(Phrase(R.string.live_stan_offair_now_on, listOf(12)), spec.detail)
        assertEquals(Phrase(R.string.live_stan_spoken_offair_now_on, listOf("Test Channel", 12)), spec.spoken)
    }

    @Test
    fun whileTheChannelsAreBuiltStanStandsByAndSaysSo() {
        // Whichever the viewer chose for a channel change: no channel is changing.
        for (change in ChannelChangeStyle.entries) {
            val spec = checkNotNull(spec(LiveState.BuildingChannels, change))
            assertEquals(StanScene.Standby, spec.scene)
            assertEquals(Phrase(R.string.live_stan_standby_headline), spec.headline)
            assertEquals(Phrase(R.string.live_stan_building_status), spec.status)
            assertNull(spec.detail)
            assertEquals(Phrase(R.string.live_stan_spoken_building), spec.spoken)
            assertEquals(LiveTags.STAN_STANDBY, spec.tag)
        }
        assertFalse(isChangingChannel(LiveState.BuildingChannels))
    }

    @Test
    fun whileLiveStartsStanStandsByNotOffTheAir() {
        // Nothing is chosen yet: no channel to name, and the station is not closed. Whichever the viewer chose for a
        // channel change, on the whole screen and in the guide's corner.
        for (change in ChannelChangeStyle.entries) {
            for (withBanner in listOf(true, false)) {
                val spec = checkNotNull(spec(LiveState.Starting, change, withBanner))
                assertEquals(StanScene.Standby, spec.scene)
                assertNotEquals(StanScene.OffAir, spec.scene)
                assertEquals(Phrase(R.string.live_stan_standby_headline), spec.headline)
                assertEquals(Phrase(R.string.live_stan_starting_status), spec.status)
                assertNull(spec.detail)
                assertEquals(Phrase(R.string.live_stan_spoken_starting), spec.spoken)
                assertEquals(LiveTags.STAN_STARTING, spec.tag)
            }
        }
        assertFalse(isChangingChannel(LiveState.Starting))
    }

    @Test
    fun withNoChannelsStanShrugsAndSaysWhereChannelsAreSwitchedOn() {
        // Whichever the viewer chose for a channel change, and in the guide's corner too: no channel is changing.
        for (change in ChannelChangeStyle.entries) {
            for (withBanner in listOf(true, false)) {
                val spec = checkNotNull(spec(LiveState.NoChannels, change, withBanner))
                assertEquals(StanScene.NoSignal, spec.scene)
                assertEquals(Phrase(R.string.live_stan_no_channels_headline), spec.headline)
                assertEquals(Phrase(R.string.live_stan_no_channels_status), spec.status)
                assertEquals(Phrase(R.string.live_stan_no_channels_detail), spec.detail)
                assertEquals(Phrase(R.string.live_stan_spoken_no_channels), spec.spoken)
                assertEquals(LiveTags.NO_CHANNELS, spec.tag)
            }
        }
        assertFalse(isChangingChannel(LiveState.NoChannels))
    }

    @Test
    fun withNoLibrariesStanShrugsAndSaysWhereToChooseThem() {
        for (change in ChannelChangeStyle.entries) {
            for (withBanner in listOf(true, false)) {
                val spec = checkNotNull(spec(LiveState.NoLibraries, change, withBanner))
                assertEquals(StanScene.NoSignal, spec.scene)
                assertEquals(Phrase(R.string.live_stan_no_libraries_headline), spec.headline)
                assertEquals(Phrase(R.string.live_stan_no_libraries_status), spec.status)
                assertEquals(Phrase(R.string.live_stan_no_libraries_detail), spec.detail)
                assertEquals(Phrase(R.string.live_stan_spoken_no_libraries), spec.spoken)
                assertEquals(LiveTags.NO_LIBRARIES, spec.tag)
            }
        }
        assertFalse(isChangingChannel(LiveState.NoLibraries))
    }

    @Test
    fun theEmptyScreensSayTheirLinesWordForWord() {
        // Module tests run from the module's directory. The spoken lines are the plain status, never Stan's joke.
        val line = Regex("""<string name="([a-z_]+)"[^>]*>(.*)</string>""")
        val strings =
            File("src/main/res/values/strings.xml")
                .readLines()
                .mapNotNull { line.find(it)?.destructured }
                .associate { (name, text) -> name to text }
        assertEquals("NOTHING ON\\nTHE AIR", strings["live_stan_no_channels_headline"])
        assertEquals("NO CHANNELS YET", strings["live_stan_no_channels_status"])
        assertEquals("GUIDE · CHANNELS", strings["live_stan_no_channels_detail"])
        assertEquals("No channels on the air yet. Guide, Channels.", strings["live_stan_spoken_no_channels"])
        assertEquals("NOTHING ON\\nTHE SHELF", strings["live_stan_no_libraries_headline"])
        assertEquals("NO LIBRARIES", strings["live_stan_no_libraries_status"])
        assertEquals("GUIDE · SETTINGS · LIBRARIES", strings["live_stan_no_libraries_detail"])
        assertEquals("No libraries available. Guide, Settings, Libraries.", strings["live_stan_spoken_no_libraries"])
    }

    @Test
    fun thePictureAndSignOutHaveNoStanScreen() {
        assertNull(spec(LiveState.Watching(banner(BannerLabel.Now))))
        assertNull(spec(LiveState.Watching(null)))
        assertNull(spec(LiveState.SignedOut))
    }

    @Test
    fun theViewersChoiceDecidesBetweenSnowAndTheStandbyScreen() {
        val tuning = LiveState.Tuning(banner(BannerLabel.TuningIn))
        val flipping = LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = false)
        for (state in listOf(tuning, flipping)) {
            assertTrue(isChangingChannel(state))
            assertNotNull(spec(state, ChannelChangeStyle.Stan))
            assertNull(spec(state, ChannelChangeStyle.Snow))
        }
        assertFalse(isChangingChannel(LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = true)))
        assertFalse(isChangingChannel(LiveState.Watching(null)))
    }

    @Test
    fun theChoiceOnlyConcernsTheChangeOfChannel() {
        // Technical difficulties and the off-air screen are Stan's whichever the viewer chose for the change.
        assertNotNull(spec(LiveState.TechnicalDifficulties(null), ChannelChangeStyle.Snow))
        assertNotNull(spec(LiveState.Ident(null), ChannelChangeStyle.Snow))
        assertNotNull(spec(LiveState.Starting, ChannelChangeStyle.Snow))
    }

    @Test
    fun stansCopyKeepsTheVoice() {
        // Module tests run from the module's directory. No exclamation marks, and never "I": we.
        val stan = File("src/main/res/values/strings.xml").readLines().filter { "name=\"live_stan_" in it }
        assertTrue(stan.isNotEmpty())
        stan.forEach { line ->
            assertFalse("no exclamation marks: $line", '!' in line)
            assertFalse("we, never I: $line", Regex("""\bI\b|\bI'(m|ll|ve)\b""").containsMatchIn(line))
        }
    }
}
