// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgramInfoOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        overview: String? = null,
        year: Int? = null,
        genres: List<String> = emptyList(),
        runtimeMinutes: Int? = null,
        loading: Boolean = false,
    ) = compose.setContent {
        StationBreakTheme {
            ProgramInfoOverlay(
                channel = "07 Test Channel",
                title = "Test Show",
                episodeLine = "S1 E2 · Episode One",
                slot = "8:00 – 8:30 PM",
                overview = overview,
                year = year,
                genres = genres,
                runtimeMinutes = runtimeMinutes,
                loading = loading,
            )
        }
    }

    @Test
    fun showsFallbackWithoutDetails() {
        show()
        compose.onNodeWithTag(ProgramInfoTags.TITLE).assertIsDisplayed()
        compose.onNodeWithText("S1 E2 · Episode One").assertIsDisplayed()
        compose.onNodeWithText("8:00 – 8:30 PM").assertIsDisplayed()
        compose.onNodeWithTag(ProgramInfoTags.OVERVIEW).assertDoesNotExist()
        compose.onNodeWithTag(ProgramInfoTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun showsLoading() {
        show(loading = true)
        compose.onNodeWithTag(ProgramInfoTags.LOADING).assertIsDisplayed()
        compose.onNodeWithTag(ProgramInfoTags.TITLE).assertIsDisplayed()
    }

    @Test
    fun showsAllDetails() {
        show(overview = "A test overview.", year = 1999, genres = listOf("Drama", "Comedy"), runtimeMinutes = 45)
        compose.onNodeWithTag(ProgramInfoTags.OVERVIEW).assertIsDisplayed()
        compose.onNodeWithText("A test overview.").assertIsDisplayed()
        compose.onNodeWithTag(ProgramInfoTags.DETAILS).assertIsDisplayed()
        compose.onNodeWithText("1999 · Drama, Comedy · 45 min").assertIsDisplayed()
    }
}
