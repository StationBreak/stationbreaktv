// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R

/**
 * The heads-up under the list (#785): more channels on make the guide slower to get ready. Only a viewer who can switch
 * channels on sees it (#799: Pro, the same `isPro` as the free-tier gates). It is plain text, there whatever the number
 * of channels on, and never a focus stop: the screen draws its focus from the state,
 * so the line is not a focusable or clickable node, and not a live region (it is not announced as it changes).
 */
@RunWith(AndroidJUnit4::class)
class ChannelsHeadsUpTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun rows(on: Int) =
        (1..on).map {
            ChannelRow(
                id = ChannelId("c$it"),
                number = FIRST_NUMBER + it,
                name = "Channel $it",
                group = ChannelGroup.On,
                kind = ChannelKind.Starter,
                kid = false,
                optIn = false,
                reason = null,
                hasOverride = false,
            )
        }

    private fun show(
        on: Int,
        isPro: Boolean = true,
    ) = showUi(uiOf(on, isPro))

    private fun uiOf(
        on: Int,
        isPro: Boolean = true,
    ) = ChannelsUi.Empty.copy(
        rows = rows(on),
        focus = if (on > 0) ChannelsFocus.Row(ChannelId("c1")) else ChannelsFocus.New,
        access = LineupAccess.Editable,
        listed = true,
        reasonsRead = true,
        isPro = isPro,
    )

    private fun showUi(ui: ChannelsUi) {
        val hole = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(GuideColors.Dark.page)) {
                    ChannelsScreen(ui, hole)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun headsUp() = compose.onNodeWithTag(ChannelsTags.HEADS_UP, useUnmergedTree = true)

    private fun assertHeadsUp() {
        val copy = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.channels_heads_up)
        headsUp().assertIsDisplayed().assertTextEquals(copy)
        val config = headsUp().fetchSemanticsNode().config
        assertFalse("not a focus stop", config.contains(SemanticsProperties.Focused))
        assertFalse("not clickable", config.contains(SemanticsActions.OnClick))
        assertFalse("not a live region", config.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun theHeadsUpShowsWithNoChannelsOn() {
        show(0)
        assertHeadsUp()
    }

    @Test
    fun theHeadsUpShowsWithFiftyChannelsOn() {
        show(50)
        assertHeadsUp()
    }

    @Test
    fun theHeadsUpShowsWithAHundredChannelsOn() {
        show(100)
        assertHeadsUp()
    }

    @Test
    fun aFreeViewerDoesNotSeeTheHeadsUp() {
        show(50, isPro = false)
        headsUp().assertDoesNotExist()
    }

    @Test
    fun theHeadsUpFollowsTheEntitlementLive() {
        val ui = mutableStateOf(uiOf(50, isPro = false))
        val hole = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(GuideColors.Dark.page)) {
                    ChannelsScreen(ui.value, hole)
                }
            }
        }
        headsUp().assertDoesNotExist()
        ui.value = ui.value.copy(isPro = true)
        compose.waitForIdle()
        assertHeadsUp()
        ui.value = ui.value.copy(isPro = false)
        compose.waitForIdle()
        headsUp().assertDoesNotExist()
    }

    @Test
    fun theHeadsUpStaysBelowTheScrollingListSoItIsNotOnEveryRow() {
        show(100)
        val list = compose.onNodeWithTag(ChannelsTags.LIST, useUnmergedTree = true).getBoundsInRoot()
        val note = headsUp().getBoundsInRoot()
        assertTrue("below the list, not over it", note.top >= list.bottom)
    }

    @Test
    fun theFocusStaysOnTheRowWhateverTheHeadsUp() {
        show(3)
        compose.onNodeWithTag(ChannelsTags.row(ChannelId("c1"))).assertIsSelected()
        headsUp().assertIsDisplayed()
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val FIRST_NUMBER = 100
    }
}
