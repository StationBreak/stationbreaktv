// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** What the label on the guide's picture says in each state (#722). */
class PictureLabelTest {
    private fun banner(
        label: BannerLabel = BannerLabel.Now,
        title: String? = "Shin Godzilla",
        number: Int = 12,
        recipeKey: String? = "rubber-suit",
    ) = Banner(number, "Rubber Suit", label, title, null, 0L, 1L, null, recipeKey = recipeKey)

    private fun label(
        lead: PictureLead?,
        title: String?,
    ) = PictureLabel(12, "Rubber Suit", "rubber-suit", lead, title)

    private val alone = label(null, null)

    @Test
    fun aPlayingPictureIsNowPlayingWithItsTitle() {
        val state = LiveState.Watching(banner())
        assertEquals(label(PictureLead.NowPlaying, "Shin Godzilla"), pictureLabelOf(state, null))
    }

    @Test
    fun theLabelStaysOnceTheBannerHasGone() {
        // Watching(null) carries no banner: the playing show's own banner stands in.
        assertEquals(
            label(PictureLead.NowPlaying, "Shin Godzilla"),
            pictureLabelOf(LiveState.Watching(null), banner()),
        )
    }

    @Test
    fun nothingPlayingAndNoBannerIsNoLabel() {
        assertNull(pictureLabelOf(LiveState.Watching(null), null))
    }

    @Test
    fun aPlayingPictureIsNowPlayingWhateverItsBannersLabel() {
        // A stand-in or a switch's banner still sits over a picture that is playing.
        for (word in BannerLabel.entries) {
            val state = LiveState.Watching(banner(label = word))
            assertEquals(PictureLead.NowPlaying, pictureLabelOf(state, null)?.lead)
        }
    }

    @Test
    fun aTuneFollowsTheBannersLabel() {
        val tuning = LiveState.Tuning(banner(label = BannerLabel.TuningIn))
        assertEquals(label(PictureLead.TuningIn, "Shin Godzilla"), pictureLabelOf(tuning, null))
        val switching = LiveState.Tuning(banner(label = BannerLabel.SwitchingAudio))
        assertEquals(PictureLead.SwitchingAudio, pictureLabelOf(switching, null)?.lead)
        val subtitles = LiveState.Tuning(banner(label = BannerLabel.SwitchingSubtitles))
        assertEquals(PictureLead.SwitchingSubtitles, pictureLabelOf(subtitles, null)?.lead)
        val version = LiveState.Tuning(banner(label = BannerLabel.SwitchingVersion))
        assertEquals(PictureLead.SwitchingVersion, pictureLabelOf(version, null)?.lead)
        val filling = LiveState.Tuning(banner(label = BannerLabel.FillingIn))
        assertEquals(PictureLead.FillingIn, pictureLabelOf(filling, null)?.lead)
        val cutIn = LiveState.Tuning(banner(label = BannerLabel.Now))
        assertEquals(PictureLead.NowPlaying, pictureLabelOf(cutIn, null)?.lead)
    }

    @Test
    fun aSlateOrIdentNamesWhatIsUpNext() {
        val upNext = banner(label = BannerLabel.UpNext, title = "Matilda")
        val tag = ChannelTag(12, "Rubber Suit", "rubber-suit")
        assertEquals(label(PictureLead.UpNext, "Matilda"), pictureLabelOf(LiveState.Ident(upNext, tag), null))
        assertEquals(
            label(PictureLead.UpNext, "Matilda"),
            pictureLabelOf(LiveState.TechnicalDifficulties(upNext, tag), null),
        )
    }

    @Test
    fun aCardThatNamesNoShowSaysTheChannelAlone() {
        val tag = ChannelTag(12, "Rubber Suit", "rubber-suit")
        assertEquals(alone, pictureLabelOf(LiveState.Ident(null, tag), null))
        assertEquals(alone, pictureLabelOf(LiveState.TechnicalDifficulties(null, tag), null))
        assertEquals(alone, pictureLabelOf(LiveState.Choosing(tag, overPicture = true), null))
    }

    @Test
    fun aBannerWithNoTitleSaysTheChannelAlone() {
        assertEquals(alone, pictureLabelOf(LiveState.Tuning(banner(label = BannerLabel.TuningIn, title = null)), null))
        assertEquals(alone, pictureLabelOf(LiveState.Watching(banner(title = "  ")), null))
    }

    @Test
    fun noChannelNamedMeansNoLabel() {
        assertNull(pictureLabelOf(LiveState.Ident(null), null))
        assertNull(pictureLabelOf(LiveState.TechnicalDifficulties(null), null))
        assertNull(pictureLabelOf(LiveState.NoChannels, banner()))
        assertNull(pictureLabelOf(LiveState.NoLibraries, banner()))
        assertNull(pictureLabelOf(LiveState.SignedOut, banner()))
    }

    @Test
    fun aLabelHasALeadExactlyWhenItHasATitle() {
        assertThrows(IllegalArgumentException::class.java) { label(PictureLead.NowPlaying, null) }
        assertThrows(IllegalArgumentException::class.java) { label(null, "Shin Godzilla") }
    }

    @Test
    fun theMachineKeepsTheLabelAfterTheTuneBannerHasLingeredAway() =
        runTest {
            val f = flipping()
            val shown = checkNotNull((f.r.state as LiveState.Watching).banner)
            val onTune = checkNotNull(f.r.live.pictureLabel.value)
            assertEquals(PictureLead.NowPlaying, onTune.lead)
            assertEquals(shown.title, onTune.title)
            assertEquals(shown.number, onTune.number)
            assertEquals(shown.channelName, onTune.channelName)

            advanceTimeBy(5.seconds)
            assertNull((f.r.state as LiveState.Watching).banner)
            assertEquals(onTune, f.r.live.pictureLabel.value)
        }
}
