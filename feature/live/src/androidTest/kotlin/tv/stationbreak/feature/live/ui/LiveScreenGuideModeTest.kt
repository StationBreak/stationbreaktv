// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState

/**
 * Live TV with the guide open (spec 17, Task 10): the picture goes to the guide's corner through the surface's
 * modifier, what stands in for a picture (snow, the cards) is drawn there and nowhere else, and the banners and the
 * info overlay make way for the guide.
 */
@RunWith(AndroidJUnit4::class)
class LiveScreenGuideModeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val corner = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    private val full = DpRect(0.dp, 0.dp, WIDTH_DP.dp, HEIGHT_DP.dp)
    private val hole = mutableStateOf<DpRect?>(null)
    private val shown = mutableStateOf<LiveState>(LiveState.Watching(null))

    private val upNext =
        Banner(4, "Test Channel", BannerLabel.UpNext, "Test Movie A", null, START, START + 30 * MINUTE, null)

    private fun show(
        state: LiveState,
        info: LiveInfo? = null,
        guideOpen: Boolean = true,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        shown.value = state
        compose.setContent {
            StationBreakTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).testTag(HOST)) {
                        LiveScreen(
                            shown.value,
                            info,
                            guide =
                                if (guideOpen) {
                                    { pictureHole ->
                                        hole.value = pictureHole
                                        Box(Modifier.fillMaxSize().testTag(GUIDE))
                                    }
                                } else {
                                    null
                                },
                        ) { Box(Modifier.fillMaxSize().testTag(SURFACE)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // In the screen's own coordinates: the host is a 960 x 540 dp screen wherever the test device places it (a
    // right-to-left layout puts it at the device's right edge).
    private fun SemanticsNodeInteraction.bounds(): DpRect {
        val host = compose.onNodeWithTag(HOST).getBoundsInRoot()
        val node = getBoundsInRoot()
        return DpRect(node.left - host.left, node.top - host.top, node.right - host.left, node.bottom - host.top)
    }

    private fun assertRect(
        expected: DpRect,
        actual: DpRect,
    ) {
        val message = "expected $expected, was $actual"
        assertEquals(message, expected.left.value, actual.left.value, EPS)
        assertEquals(message, expected.top.value, actual.top.value, EPS)
        assertEquals(message, expected.right.value, actual.right.value, EPS)
        assertEquals(message, expected.bottom.value, actual.bottom.value, EPS)
    }

    @Test
    fun thePictureIsInTheCornerAndTheGuideIsToldWhere() {
        show(LiveState.Watching(null))
        assertRect(corner, compose.onNodeWithTag(SURFACE).bounds())
        assertRect(corner, checkNotNull(hole.value))
        compose.onNodeWithTag(GUIDE).assertExists()
    }

    @Test
    fun theCornerIsTopRightInARightToLeftLayoutToo() {
        // The guide's hole is in screen coordinates; the picture must not be mirrored away from it.
        show(LiveState.Watching(null), direction = LayoutDirection.Rtl)
        assertRect(corner, compose.onNodeWithTag(SURFACE).bounds())
    }

    @Test
    fun thePictureIsFullScreenWithoutTheGuide() {
        show(LiveState.Watching(null), guideOpen = false)
        assertRect(full, compose.onNodeWithTag(SURFACE).bounds())
    }

    @Test
    fun snowIsInTheCorner() {
        show(LiveState.Tuning(upNext.copy(label = BannerLabel.TuningIn)))
        assertRect(corner, compose.onNodeWithTag(LiveTags.SNOW).bounds())
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    @Test
    fun identAndDifficultiesAreClippedToTheCorner() {
        show(LiveState.Ident(upNext, ChannelTag(4, "Test Channel")))
        assertRect(corner, compose.onNodeWithTag(LiveTags.TEST_CARD).bounds())
        compose.onNodeWithTag(LiveTags.IDENT, useUnmergedTree = true).assertExists()
        // The card's "Up next" banner is a banner: the guide says what is next.
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
        compose.onNodeWithText("Up next").assertDoesNotExist()

        compose.runOnIdle { shown.value = LiveState.TechnicalDifficulties(upNext, ChannelTag(4, "Test Channel")) }
        compose.waitForIdle()
        assertRect(corner, compose.onNodeWithTag(LiveTags.TEST_CARD).bounds())
        compose.onNodeWithTag(LiveTags.DIFFICULTIES, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    @Test
    fun bannerHiddenInGuideMode() {
        val banner = upNext.copy(label = BannerLabel.Now, actions = true)
        show(LiveState.Watching(banner), LiveInfo(banner, null, loading = false))
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.BANNER_ACTIONS).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.INFO).assertDoesNotExist()
        compose.onNodeWithTag(GUIDE).assertExists()
    }

    @Test
    fun noChannelsKeepsItsCardInTheCornerUnderTheGuide() {
        // With no channels the guide is still drawn (it shows its own empty state, GuideScreenTest), and live's "no
        // channels" card stays in the picture's corner, not full screen over the guide.
        show(LiveState.NoChannels)
        compose.onNodeWithTag(GUIDE).assertExists()
        assertRect(corner, compose.onNodeWithTag(LiveTags.TEST_CARD).bounds())
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val EPS = 0.5f
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
        const val GUIDE = "test:guide"
        const val SURFACE = "test:surface"
        const val HOST = "test:host"
    }
}
