// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveState

/**
 * Long text in the banners (spec 14, device finding): every single-line text is one line that ends in an ellipsis, and
 * nothing leaves the banner or the 5% safe area at either side. Checks the text layout itself, not just the bounds,
 * since a clipped line and an ellipsized one occupy the same box.
 */
@RunWith(AndroidJUnit4::class)
class LiveBannerOverflowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val id = CanonicalId.parse("1".padStart(32, '0'))
    private val longWords = "With A Very Long Synthetic Name ".repeat(12)
    private val longEpisode =
        ProgramInfo(id, ScheduleItemKind.Episode, "Test Episode $longWords", "Test Series B", 29, 3)
    private val longSeries =
        ProgramInfo(id, ScheduleItemKind.Episode, "Test Episode One", "Test Series $longWords", 29, 3)

    private fun banner(
        info: ProgramInfo,
        channelName: String = "Test Channel",
    ) = Banner(4, channelName, BannerLabel.Now, info.seriesName, info, START, START + 30 * MINUTE, 12 * MINUTE)

    private fun show(state: LiveState) {
        compose.setContent { StationBreakTheme { LiveScreen(state) {} } }
        compose.waitForIdle()
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the node has a text layout", action != null && action(results))
        return results.first()
    }

    private fun assertEllipsized(tag: String) {
        val result = layout(compose.onNodeWithTag(tag))
        assertEquals("$tag is one line", 1, result.lineCount)
        assertTrue("$tag has visual overflow", result.hasVisualOverflow)
        assertTrue("$tag line is ellipsized", result.isLineEllipsized(0))
        assertTrue(
            "$tag shows less than its text",
            result.getLineEnd(0, visibleEnd = true) < result.layoutInput.text.length,
        )
    }

    /** The node sits between the banner's sides, and the banner between the safe-area insets. */
    private fun assertInside(tag: String) {
        val root = compose.onRoot().getBoundsInRoot()
        val banner = compose.onNodeWithTag(LiveTags.BANNER).getBoundsInRoot()
        val node = compose.onNodeWithTag(tag).getBoundsInRoot()
        val inset = root.right * SAFE
        assertTrue("banner left is inside the safe area", banner.left >= inset - TOLERANCE)
        assertTrue("banner right is inside the safe area", banner.right <= root.right - inset + TOLERANCE)
        assertTrue("$tag left is inside the banner", node.left >= banner.left - TOLERANCE)
        assertTrue("$tag right is inside the banner", node.right <= banner.right + TOLERANCE)
    }

    @Test
    fun aLongEpisodeTitleEndsInAnEllipsisInsideTheBanner() {
        show(LiveState.Watching(banner(longEpisode)))
        assertEllipsized(LiveTags.BANNER_SUBTITLE)
        assertInside(LiveTags.BANNER_SUBTITLE)
        assertInside(LiveTags.BANNER_SLOT)
        assertInside(LiveTags.BANNER_JOINED)
    }

    @Test
    fun aLongSeriesTitleEndsInAnEllipsisInsideTheBanner() {
        show(LiveState.Watching(banner(longSeries)))
        assertEllipsized(LiveTags.BANNER_TITLE)
        assertInside(LiveTags.BANNER_TITLE)
    }

    @Test
    fun aLongChannelNameEndsInAnEllipsisAndLeavesRoomForTheText() {
        show(LiveState.Watching(banner(longEpisode, channelName = "Test Channel $longWords")))
        assertEllipsized(LiveTags.BANNER_CHANNEL)
        assertInside(LiveTags.BANNER_CHANNEL)
        assertEllipsized(LiveTags.BANNER_SUBTITLE)
        assertInside(LiveTags.BANNER_SUBTITLE)
    }

    @Test
    fun theChannelOnlyBannerKeepsALongNameInsideItsBounds() {
        show(LiveState.Ident(null, ChannelTag(7, "Test Channel $longWords")))
        assertEllipsized(LiveTags.BANNER_CHANNEL)
        assertInside(LiveTags.BANNER_CHANNEL)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
        const val SAFE = 0.05f
        val TOLERANCE = 1.dp
    }
}
