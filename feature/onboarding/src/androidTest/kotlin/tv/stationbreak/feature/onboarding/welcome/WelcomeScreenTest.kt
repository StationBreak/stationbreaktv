// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.welcome

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.SetupScaffoldTags
import tv.stationbreak.feature.onboarding.awaitWindowFocus

/** The welcome screen's content and default focus. Where Get started goes is covered in `:app`'s navigation tests. */
@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var started = 0

    private fun launch() {
        compose.setContent { StationBreakTheme { WelcomeDestination(onGetStarted = { started++ }) } }
        compose.waitForIdle()
    }

    @Test
    fun stanWaitsUnderTheOpeningAndGreetsWhenItHandsOver() {
        val covered = mutableStateOf(true)
        compose.setContent {
            StationBreakTheme { WelcomeDestination(onGetStarted = {}, covered = covered.value) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertDoesNotExist()
        compose.onNodeWithTag(WelcomeTags.GET_STARTED).assertExists()
        covered.value = false
        compose.waitForIdle()
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertExists()
    }

    @Test
    fun showsTheTitleTaglineAndBody() {
        launch()
        compose.onNodeWithText("Welcome to StationBreak").assert(keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Your library, on the air.").assertExists()
        compose
            .onNodeWithText(
                "You’ll need your Jellyfin server’s address, and a username and password for it. " +
                    "Not sure? Ask whoever runs your server.",
            ).assertExists()
    }

    @Test
    fun getStartedIsTheOnlyButtonAndHasDefaultFocus() {
        launch()
        compose.onNodeWithTag(WelcomeTags.GET_STARTED).assertIsFocused()
        compose.onNodeWithText("Get started").assertExists()
    }

    @Test
    fun selectOnGetStartedIsReportedOnce() {
        launch()
        compose.onNodeWithTag(WelcomeTags.GET_STARTED).assertIsFocused()
        compose.awaitWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(1, started)
    }
}
