// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme

/** The placeholder after setup: its wording, and a heading for a screen reader to land on. */
@RunWith(AndroidJUnit4::class)
class SetupCompleteScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theHeadlineIsAHeadingAndTheBodyIsTheOwnersWording() {
        compose.setContent { StationBreakTheme { SetupCompleteScreen() } }
        compose.onNodeWithTag(SetupCompleteTags.HEADLINE).assert(hasText("Setup complete"))
        compose
            .onNodeWithTag(SetupCompleteTags.HEADLINE)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose
            .onNodeWithTag(SetupCompleteTags.BODY)
            .assert(
                hasText(
                    "Your server and libraries are saved. " +
                        "There’s nothing to watch yet, because StationBreak is still being built.",
                ),
            )
    }
}
