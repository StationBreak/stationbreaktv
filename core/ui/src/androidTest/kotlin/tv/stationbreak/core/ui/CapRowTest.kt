// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Cap row (#1152, #1151): every option stays visible, the filled one is spoken, and an off row says it is off. */
@RunWith(AndroidJUnit4::class)
class CapRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = listOf("Tots", "Kids", "Teens")

    private fun show(
        selected: Int?,
        enabled: Boolean,
        focused: Boolean = false,
    ) = compose.setContent {
        StationBreakTheme { CapRow("Cap", options, selected, focused, enabled, tag = "cap") }
    }

    @Test
    fun allThreeLabelsAreShownWhateverIsSelected() {
        show(selected = 1, enabled = true)

        options.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun theSelectedOptionIsSpokenAsTheState() {
        show(selected = 2, enabled = true)

        val state = compose.onNodeWithTag("cap").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("Teens", state)
    }

    @Test
    fun anOffRowIsDimmedForAScreenReaderAndShowsNoSelection() {
        show(selected = null, enabled = false, focused = true)

        compose.onNodeWithTag("cap").assertIsNotEnabled()
        options.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        // A disabled row is never selected, whatever the screen says of focus.
        val selected = compose.onNodeWithTag("cap").fetchSemanticsNode().config[SemanticsProperties.Selected]
        assertEquals(false, selected)
    }
}
