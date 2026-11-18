// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ServerButton

/**
 * Where [SetupScaffold] puts Stan, and that he never takes a key, crowds the form or moves it (Review Focus 1 and 2).
 * Stan has no semantics, so his mood is not visible here: the doze is [DozeTimerTest]'s, on virtual time.
 */
@RunWith(AndroidJUnit4::class)
class SetupScaffoldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cue = StanCue(StanMood.Idle, R.string.stan_server_idle)

    private fun show(
        width: Dp,
        height: Dp,
        cue: MutableState<StanCue?> = mutableStateOf(this.cue),
        covered: MutableState<Boolean> = mutableStateOf(false),
        fontScale: Float? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                StationBreakTheme {
                    Box(Modifier.requiredSize(width, height)) {
                        SetupScaffold(cue.value, covered = covered.value) { padding -> Form(padding) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aKeyStillReachesTheScreen() {
        show(960.dp, 540.dp)
        compose.onNodeWithTag(FIRST).assertIsFocused()
        compose.awaitFocusedForKeys(FIRST, KEY_TIMEOUT_MS)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        compose.onNodeWithTag(SECOND).assertIsFocused()
    }

    @Test
    fun stanSitsInTheCornerAtFullHeight() {
        show(960.dp, 540.dp)
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertExists()
    }

    @Test
    fun stanHiddenWhenShort() {
        show(960.dp, 380.dp)
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertDoesNotExist()
        compose.onNodeWithTag(FIRST).assertExists()
    }

    @Test
    fun theFormAndAFullWidthFocusedButtonClearStanAtLargeFont() {
        show(960.dp, 540.dp, fontScale = 1.3f)
        compose.onNodeWithTag(FIRST).assertIsFocused()
        val host = compose.onNodeWithTag(SetupScaffoldTags.HOST).getUnclippedBoundsInRoot()
        val form = compose.onNodeWithTag(FORM).getUnclippedBoundsInRoot()
        // FIRST spans the form's width, the widest a control there can be. A focused button is drawn at the real
        // focus scale about its centre, which would take it past its layout bounds if the scale ever grows.
        val focused = compose.onNodeWithTag(FIRST).getUnclippedBoundsInRoot().scaled(FocusStyle.BUTTON_SCALE)
        assertFalse("form $form and Stan $host overlap", form.overlaps(host))
        assertFalse("focused button $focused and Stan $host overlap", focused.overlaps(host))
    }

    @Test
    fun nullCueShowsNoStan() {
        show(960.dp, 540.dp, cue = mutableStateOf(null))
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertDoesNotExist()
    }

    @Test
    fun coveredShowsNoStan() {
        show(960.dp, 540.dp, covered = mutableStateOf(true))
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertDoesNotExist()
    }

    @Test
    fun theFormStaysPutWhetherStanIsThereOrNot() {
        val cue = mutableStateOf<StanCue?>(null)
        val covered = mutableStateOf(false)
        show(960.dp, 540.dp, cue = cue, covered = covered)
        val empty = compose.onNodeWithTag(FORM).getUnclippedBoundsInRoot()
        cue.value = this.cue
        compose.waitForIdle()
        compose.onNodeWithTag(SetupScaffoldTags.HOST).assertExists()
        assertEquals(empty, compose.onNodeWithTag(FORM).getUnclippedBoundsInRoot())
        covered.value = true
        compose.waitForIdle()
        assertEquals(empty, compose.onNodeWithTag(FORM).getUnclippedBoundsInRoot())
    }

    /** A setup screen's shape: the scaffold's padding, a wide column of text and two buttons. */
    @Composable
    private fun Form(padding: PaddingValues) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(FORM),
                verticalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Text(LONG_TEXT)
                ServerButton(
                    label = R.string.server_test,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().focusRequester(first).testTag(FIRST),
                )
                ServerButton(label = R.string.server_cancel, onClick = {}, modifier = Modifier.testTag(SECOND))
            }
        }
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private fun DpRect.scaled(factor: Float): DpRect {
        val dx = (right - left) * (factor - 1) / 2
        val dy = (bottom - top) * (factor - 1) / 2
        return DpRect(left - dx, top - dy, right + dx, bottom + dy)
    }

    private companion object {
        const val FORM = "setupScaffoldTest.form"
        const val FIRST = "setupScaffoldTest.first"
        const val SECOND = "setupScaffoldTest.second"
        const val KEY_TIMEOUT_MS = 10_000L
        const val LONG_TEXT =
            "Type the address you use to open your server in a browser, then test it. A long line, so a large " +
                "font fills the form's width."
    }
}
