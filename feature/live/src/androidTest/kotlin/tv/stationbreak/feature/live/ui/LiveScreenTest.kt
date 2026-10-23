// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.LiveState

/** The live screen's states on the API 25 emulator: what each one puts on screen (spec 14). */
@RunWith(AndroidJUnit4::class)
class LiveScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val id = CanonicalId.parse("1".padStart(32, '0'))
    private val movie = ProgramInfo(id, ScheduleItemKind.Movie, "Test Movie A", null, null, null)
    private val episode = ProgramInfo(id, ScheduleItemKind.Episode, "Test Episode One", "Test Series B", 2, 5)

    private fun banner(
        label: BannerLabel,
        info: ProgramInfo? = movie,
        title: String? = info?.seriesName ?: info?.title,
        joinedMs: Long? = 12 * MINUTE,
    ) = Banner(4, "Test Channel", label, title, info, START, START + 30 * MINUTE, joinedMs)

    private fun show(state: LiveState) {
        compose.setContent { StationBreakTheme { LiveScreen(state) {} } }
        compose.waitForIdle()
    }

    @Test
    fun tuningShowsSnowAndBanner() {
        show(LiveState.Tuning(banner(BannerLabel.TuningIn)))
        compose.onNodeWithTag(LiveTags.SNOW).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_TITLE).assertTextEquals("Test Movie A")
        compose.onNodeWithTag(LiveTags.BANNER_JOINED).assertTextEquals("joining 12 min in")
        compose.onNodeWithText("Tuning in").assertExists()
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertDoesNotExist()
    }

    @Test
    fun aJustStartedShowSaysJustStarted() {
        show(LiveState.Tuning(banner(BannerLabel.TuningIn, joinedMs = 20_000)))
        compose.onNodeWithTag(LiveTags.BANNER_JOINED).assertTextEquals("Just started")
    }

    @Test
    fun anEpisodeShowsItsSeriesAndEpisodeLine() {
        show(LiveState.Tuning(banner(BannerLabel.TuningIn, info = episode)))
        compose.onNodeWithTag(LiveTags.BANNER_TITLE).assertTextEquals("Test Series B")
        compose.onNodeWithTag(LiveTags.BANNER_SUBTITLE).assertTextEquals("S2 E5 · Test Episode One")
    }

    @Test
    fun aMovieHasNoEpisodeLine() {
        show(LiveState.Tuning(banner(BannerLabel.TuningIn)))
        compose.onNodeWithTag(LiveTags.BANNER_SUBTITLE).assertDoesNotExist()
    }

    @Test
    fun aCutInShowsNowAndNoJoinedLine() {
        show(LiveState.Watching(banner(BannerLabel.Now, joinedMs = null)))
        compose.onNodeWithText("Now").assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_JOINED).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.BANNER_SLOT).assertExists()
    }

    @Test
    fun aLingeringBannerOverThePictureSaysNowNotTuningIn() {
        show(LiveState.Watching(banner(BannerLabel.Now)))
        compose.onNodeWithText("Now").assertExists()
        compose.onNodeWithText("Tuning in").assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.BANNER_JOINED).assertTextEquals("joining 12 min in")
    }

    @Test
    fun watchingWithBannerShowsBannerOnly() {
        show(LiveState.Watching(banner(BannerLabel.TuningIn)))
        compose.onNodeWithTag(LiveTags.BANNER).assertExists()
        compose.onNodeWithTag(LiveTags.SNOW).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertDoesNotExist()
    }

    @Test
    fun watchingWithoutBannerShowsNeither() {
        show(LiveState.Watching(null))
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.SNOW).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertDoesNotExist()
    }

    @Test
    fun technicalDifficultiesShowsTheTestCardSlateWithUpNext() {
        show(LiveState.TechnicalDifficulties(banner(BannerLabel.UpNext, joinedMs = null)))
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertExists()
        compose.onNodeWithTag(LiveTags.DIFFICULTIES).assertExists()
        compose.onNodeWithText("TECHNICAL DIFFICULTIES").assertExists()
        compose.onNodeWithText("PLEASE STAND BY").assertExists()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithTag(LiveTags.SNOW).assertDoesNotExist()
    }

    @Test
    fun technicalDifficultiesWithoutAnUpNextShowsNoBanner() {
        show(LiveState.TechnicalDifficulties(null))
        compose.onNodeWithText("TECHNICAL DIFFICULTIES").assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    @Test
    fun identShowsTheTestCardWithUpNext() {
        show(LiveState.Ident(banner(BannerLabel.UpNext, joinedMs = null)))
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertExists()
        compose.onNodeWithTag(LiveTags.IDENT).assertExists()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithTag(LiveTags.DIFFICULTIES).assertDoesNotExist()
    }

    @Test
    fun noChannelsShowsTheTestCardLine() {
        show(LiveState.NoChannels)
        compose.onNodeWithTag(LiveTags.TEST_CARD).assertExists()
        compose.onNodeWithTag(LiveTags.NO_CHANNELS).assertTextEquals("No channels on the air yet")
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    @Test
    fun fillingInBannerSaysFillingIn() {
        show(LiveState.Watching(banner(BannerLabel.FillingIn)))
        compose.onNodeWithText("Filling in").assertExists()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
    }
}
