// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.ChannelChange
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
        change: ChannelChange = ChannelChange.Stan,
    ) = stanSpecOf(state, change, time)

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
        assertEquals(Phrase(R.string.live_stan_spoken_trouble), spec.spoken)
        assertEquals(LiveTags.DIFFICULTIES, spec.tag)
    }

    @Test
    fun anIdentWithTheNextProgrammeSaysWhenTheChannelReturns() {
        val spec = checkNotNull(spec(LiveState.Ident(banner(), ChannelTag(7, "Test Channel"))))
        assertEquals(StanScene.OffAir, spec.scene)
        assertEquals(Phrase(R.string.live_stan_offair_headline), spec.headline)
        assertEquals(Phrase(R.string.live_stan_offair_status), spec.status)
        assertEquals(Phrase(R.string.live_stan_offair_returns, listOf("Test Channel", "t1000")), spec.detail)
        assertEquals(Phrase(R.string.live_stan_spoken_offair_returns, listOf("Test Channel", "t1000")), spec.spoken)
        assertEquals(LiveTags.IDENT, spec.tag)
        assertEquals(LiveTags.STAN_DETAIL, spec.detailTag)
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
        assertEquals(LiveTags.IDENT_NOTE, spec.detailTag)
    }

    @Test
    fun aChannelOffTheAirSaysWhereLiveMovedTo() {
        val spec = checkNotNull(spec(LiveState.Ident(banner(), null, CardNote.OffTheAir("Test Channel", 12))))
        assertEquals(Phrase(R.string.live_stan_offair_now_on, listOf(12)), spec.detail)
        assertEquals(Phrase(R.string.live_stan_spoken_offair_now_on, listOf("Test Channel", 12)), spec.spoken)
        assertEquals(LiveTags.IDENT_NOTE, spec.detailTag)
    }

    @Test
    fun theInstructionCardsThePictureAndSignOutHaveNoStanScreen() {
        assertNull(spec(LiveState.NoChannels))
        assertNull(spec(LiveState.NoLibraries))
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
            assertNotNull(spec(state, ChannelChange.Stan))
            assertNull(spec(state, ChannelChange.Snow))
        }
        assertFalse(isChangingChannel(LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = true)))
        assertFalse(isChangingChannel(LiveState.Watching(null)))
    }

    @Test
    fun theChoiceOnlyConcernsTheChangeOfChannel() {
        // Technical difficulties and the off-air screen are Stan's whichever the viewer chose for the change.
        assertNotNull(spec(LiveState.TechnicalDifficulties(null), ChannelChange.Snow))
        assertNotNull(spec(LiveState.Ident(null), ChannelChange.Snow))
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
