// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramInfoTags
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.NextProgram
import java.util.concurrent.CopyOnWriteArrayList

/** The banner's progress and "Next" line, the actions banner's buttons and the info overlay on screen (spec 17). */
@RunWith(AndroidJUnit4::class)
class LiveBannerActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<LiveCommand>()
    private val id = CanonicalId.parse("1".padStart(32, '0'))
    private val movie = ProgramInfo(id, ScheduleItemKind.Movie, "Test Movie A", null, null, null)

    private fun banner(
        actions: Boolean = false,
        hasLast: Boolean = false,
        next: NextProgram? = NextProgram("Test Movie B", START + 30 * MINUTE),
    ) = Banner(
        4,
        "Test Channel",
        BannerLabel.Now,
        "Test Movie A",
        movie,
        START,
        START + 30 * MINUTE,
        null,
        next,
        actions,
        hasLast,
    )

    private fun show(
        banner: Banner,
        info: LiveInfo? = null,
    ) {
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner.takeIf { info == null }), info, { sent += it }) {}
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun progressAndNextAreShown() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        val next = compose.onNodeWithTag(LiveTags.BANNER_NEXT)
        next.assertExists()
        val text = next.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(text, text.startsWith("Next: Test Movie B at "))
    }

    @Test
    fun progressReadsTheClockAndSaysHowLongIsLeft() {
        compose.setContent {
            StationBreakTheme { CableBanner(banner(), clock = { START + 12 * MINUTE }) }
        }
        compose.waitForIdle()
        val config = compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).fetchSemanticsNode().config
        assertEquals(0.4f, config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertEquals(listOf("18 min left"), config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun noNextLineWhenNothingFollows() {
        show(banner(next = null))
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_NEXT).assertDoesNotExist()
    }

    @Test
    fun aTuneBannerHasNoButtons() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_ACTIONS).assertDoesNotExist()
    }

    @Test
    fun theActionsBannerOpensOnInfo() {
        show(banner(actions = true, hasLast = true))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertExists()
    }

    @Test
    fun lastIsLeftOutWhenThereIsNoChannelToGoBackTo() {
        show(banner(actions = true, hasLast = false))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertDoesNotExist()
    }

    @Test
    fun lastActionTunesPreviousChannel() {
        show(banner(actions = true, hasLast = true))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).performClick()
        assertEquals(listOf<LiveCommand>(LiveCommand.Previous), sent.toList())
    }

    @Test
    fun infoActionOpensTheOverlay() {
        show(banner(actions = true, hasLast = true))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).performClick()
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent.toList())
    }

    @Test
    fun theOverlayReplacesTheBannerAndShowsItsPlaceholdersWhileLoading() {
        show(banner(), LiveInfo(banner(), null, loading = true))
        compose.onNodeWithTag(LiveTags.INFO).assertExists()
        compose.onNodeWithTag(ProgramInfoTags.LOADING, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
    }
}
