// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import java.text.NumberFormat

/**
 * The first-sync progress screen on the API 25 emulator, driven with real key events (spec 06 task 3.9): nothing
 * takes focus while it syncs, Select and the arrow keys do nothing, and in the failure state Retry has default focus.
 * Generated placeholder numbers only.
 */
@RunWith(AndroidJUnit4::class)
class FirstSyncScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val phase = mutableStateOf<FirstSyncPhase>(FirstSyncPhase.Starting)
    private var retried = 0

    private fun show(initial: FirstSyncPhase) {
        phase.value = initial
        compose.setContent { StationBreakTheme { FirstSyncScreen(phase.value, onRetry = { retried++ }) } }
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun number(value: Int) = NumberFormat.getInstance().format(value)

    // Syncing

    @Test
    fun theHeadlineAndTheOnlyOnceLineShowWhileItSyncs() {
        show(FirstSyncPhase.Syncing(copied = 12_500, total = 15_000))

        compose.onNodeWithTag(FirstSyncTags.HEADLINE).assert(hasText("Getting your library ready"))
        compose
            .onNodeWithTag(FirstSyncTags.SECOND_LINE)
            .assert(hasText("This only happens once and can take a few minutes."))
    }

    @Test
    fun theCountReadsCopiedOfTotalAndTheBarShowsTheFraction() {
        show(FirstSyncPhase.Syncing(copied = 12_500, total = 15_000))

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(12_500)} of ${number(15_000)}"))
        val range = compose.onNodeWithTag(FirstSyncTags.BAR).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(12_500f / 15_000f, range.current, 0.001f)
    }

    @Test
    fun beforeTheTotalIsKnownTheCountIsAloneAndTheBarIsEmpty() {
        show(FirstSyncPhase.Syncing(copied = 200, total = null))

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(200)} so far"))
        val range = compose.onNodeWithTag(FirstSyncTags.BAR).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, range.current, 0f)
    }

    @Test
    fun beforeTheFirstPageThereIsAGettingStartedLine() {
        show(FirstSyncPhase.Starting)

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("Getting started…"))
    }

    @Test
    fun theCountFollowsTheState() {
        show(FirstSyncPhase.Syncing(copied = 100, total = 1_000))

        phase.value = FirstSyncPhase.Syncing(copied = 600, total = 1_000)
        compose.waitForIdle()

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(600)} of ${number(1_000)}"))
    }

    @Test
    fun nothingTakesFocusWhileItSyncsAndNoKeyDoesAnything() {
        show(FirstSyncPhase.Syncing(copied = 5, total = 10))

        compose.onAllNodes(isFocusable()).assertCountEquals(0)
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach(::press)

        compose.onAllNodes(isFocusable()).assertCountEquals(0)
        assertEquals(0, retried)
        compose.onNodeWithTag(FirstSyncTags.HEADLINE).assert(hasText("Getting your library ready"))
    }

    // Failure

    private val failed: FirstSyncPhase =
        MediaServerError.NetworkUnreachable().let { FirstSyncPhase.Failed(it, ErrorCopyTable.copyFor(it), false) }

    @Test
    fun theFailureShowsThePlainErrorFromTheTable() {
        show(failed)

        compose.onNodeWithTag(FirstSyncTags.ERROR_PANEL).assertExists()
        val message = "This TV can’t reach the network. Check its Wi-Fi or Ethernet connection."
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("Network unreachable").assertExists()
    }

    @Test
    fun retryHasDefaultFocusInTheFailureStateAndRunsTheSyncAgain() {
        show(failed)

        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            runCatching { compose.onNodeWithTag(FirstSyncTags.RETRY).assertIsFocused() }.isSuccess
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(1, retried)
    }

    @Test
    fun theSyncingScreenLeavesNoRetryBehind() {
        show(failed)
        compose.waitForIdle()

        phase.value = FirstSyncPhase.Syncing(copied = 1, total = 10)
        compose.waitForIdle()

        compose.onNodeWithTag(FirstSyncTags.RETRY).assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(0)
    }

    @Test
    fun aRefusedSignInSaysSoAndOffersSignInAgainNotRetry() {
        val error = MediaServerError.SessionExpired()
        show(FirstSyncPhase.Failed(error, ErrorCopyTable.copyFor(error), sessionLost = true))

        compose.onNodeWithText("The server no longer accepts this sign-in.").assertExists()
        compose.onNodeWithTag(FirstSyncTags.RETRY).assert(hasText("Sign in again"))
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            runCatching { compose.onNodeWithTag(FirstSyncTags.RETRY).assertIsFocused() }.isSuccess
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, retried)
    }

    @Test
    fun waitingForTheNetworkShowsInTheCountLine() {
        show(FirstSyncPhase.WaitingForNetwork)

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("Waiting for your network…"))
        compose.onAllNodes(isFocusable()).assertCountEquals(0)
    }

    @Test
    fun theCountIsHiddenFromTalkBackBecauseTheBarAndTheAnnouncementSayIt() {
        show(FirstSyncPhase.Syncing(copied = 7_500, total = 15_000))

        val count = compose.onNodeWithTag(FirstSyncTags.COUNT).fetchSemanticsNode().config
        assertTrue(count.contains(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun progressIsAnnouncedPolitelyOnlyWhenAQuarterIsPassed() {
        show(FirstSyncPhase.Syncing(copied = 5_000, total = 20_000))

        fun announced() =
            compose.onNodeWithTag(FirstSyncTags.ANNOUNCE).fetchSemanticsNode().config.let {
                it[SemanticsProperties.ContentDescription].single() to it[SemanticsProperties.LiveRegion]
            }

        val (first, region) = announced()
        assertEquals(LiveRegionMode.Polite, region)
        assertEquals("25 percent", first)

        phase.value = FirstSyncPhase.Syncing(copied = 6_000, total = 20_000)
        compose.waitForIdle()
        assertEquals(first, announced().first)

        phase.value = FirstSyncPhase.Syncing(copied = 10_000, total = 20_000)
        compose.waitForIdle()
        assertEquals("50 percent", announced().first)
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
    }
}
