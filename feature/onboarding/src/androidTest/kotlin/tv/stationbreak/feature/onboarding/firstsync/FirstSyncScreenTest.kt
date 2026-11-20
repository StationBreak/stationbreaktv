// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.awaitFocusedForKeys
import tv.stationbreak.feature.onboarding.awaitWindowFocus
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
    private var skipped = 0

    private fun show(initial: FirstSyncPhase) {
        phase.value = initial
        compose.setContent {
            StationBreakTheme { FirstSyncScreen(phase.value, onRetry = { retried++ }, onSkipCheer = { skipped++ }) }
        }
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        compose.awaitWindowFocus()
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

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(12_500)} of ${number(15_000)} programmes"))
        val range = compose.onNodeWithTag(FirstSyncTags.BAR).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(12_500f / 15_000f, range.current, 0.001f)
    }

    @Test
    fun beforeTheTotalIsKnownTheCountIsAloneAndTheBarIsEmpty() {
        show(FirstSyncPhase.Syncing(copied = 200, total = null))

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(200)} programmes so far"))
        val range = compose.onNodeWithTag(FirstSyncTags.BAR).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, range.current, 0f)
    }

    @Test
    fun theBarSaysTheSameCountAsTheScreen() {
        show(FirstSyncPhase.Syncing(copied = 12_500, total = 15_000))

        val said = "Getting your library ready: ${number(12_500)} of ${number(15_000)} programmes"
        compose.onNodeWithTag(FirstSyncTags.BAR).assert(hasContentDescription(said))
    }

    @Test
    fun oneProgrammeIsSingular() {
        show(FirstSyncPhase.Syncing(copied = 1, total = null))
        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(1)} programme so far"))

        phase.value = FirstSyncPhase.Syncing(copied = 0, total = 1)
        compose.waitForIdle()
        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(0)} of ${number(1)} programme"))
        val said = "Getting your library ready: ${number(0)} of ${number(1)} programme"
        compose.onNodeWithTag(FirstSyncTags.BAR).assert(hasContentDescription(said))
    }

    // Building the channels

    @Test
    fun whileTheChannelsAreBuiltThereIsTheHeadlineNoCountAndABarWithNoEnd() {
        show(FirstSyncPhase.Building)

        compose.onNodeWithTag(FirstSyncTags.BUILDING).assert(hasText("Building your channels…"))
        compose.onAllNodesWithTag(FirstSyncTags.COUNT).assertCountEquals(0)
        compose.onAllNodesWithTag(FirstSyncTags.ANNOUNCE).assertCountEquals(0)
        // TalkBack must not hear "complete": the bar reads as indeterminate.
        val range = compose.onNodeWithTag(FirstSyncTags.BAR).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo.Indeterminate, range)
    }

    @Test
    fun beforeTheFirstPageThereIsAGettingStartedLine() {
        show(FirstSyncPhase.Starting)

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("Asking your server for your library…"))
    }

    @Test
    fun theCountFollowsTheState() {
        show(FirstSyncPhase.Syncing(copied = 100, total = 1_000))

        phase.value = FirstSyncPhase.Syncing(copied = 600, total = 1_000)
        compose.waitForIdle()

        compose.onNodeWithTag(FirstSyncTags.COUNT).assert(hasText("${number(600)} of ${number(1_000)} programmes"))
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

    // Done: Stan cheers us on the air, and any key skips to live TV (spec 2026-11-18, Review Focus 5).

    @Test
    fun whenItIsDoneTheScreenSaysSoAndTakesTheKeys() {
        show(FirstSyncPhase.Done)

        compose.onNodeWithText("Your library is ready").assertExists()
        compose.awaitFocusedForKeys(FirstSyncTags.DONE, FOCUS_TIMEOUT_MS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(1, skipped)
    }

    @Test
    fun whenItIsDoneTalkBackHearsThatItIsReadyOnce() {
        show(FirstSyncPhase.Done)

        val ready = "Your library is ready"
        compose
            .onAllNodes(hasText(ready, substring = true) or hasContentDescription(ready, substring = true))
            .assertCountEquals(1)
        compose.onNodeWithTag(FirstSyncTags.BAR).assert(hasContentDescription("100 percent"))
    }

    @Test
    fun backDuringTheCheerSkipsItAndStaysInTheApp() {
        show(FirstSyncPhase.Done)
        compose.awaitFocusedForKeys(FirstSyncTags.DONE, FOCUS_TIMEOUT_MS)

        press(KeyEvent.KEYCODE_BACK)

        assertEquals(1, skipped)
        assertFalse("Back skips the cheer; it never leaves the app", compose.activity.isFinishing)
    }

    @Test
    fun theCheerHeldStillOverTheNextScreenTakesNoFocusNoKeysAndSaysNothing() {
        // As the app holds it over live TV: the screen beneath keeps the focus and the keys, and TalkBack is not told
        // the cheer a second time.
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.fillMaxSize()) {
                    val beneath = remember { FocusRequester() }
                    Box(Modifier.testTag(BENEATH).focusRequester(beneath).focusable())
                    LaunchedEffect(Unit) { beneath.requestFocus() }
                    FirstSyncCheerStill(rememberFirstSyncCheerFrame())
                }
            }
        }
        compose.awaitFocusedForKeys(BENEATH, FOCUS_TIMEOUT_MS)

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithTag(BENEATH).assertIsFocused()
        compose.onAllNodes(isFocusable()).assertCountEquals(1)
        // What a screen reader reaches is the merged tree. The unmerged tree still lists the nodes the still's
        // clearAndSetSemantics replaced, so it is no measure of what is said.
        val ready = compose.activity.getString(R.string.first_sync_done_headline)
        compose.onAllNodes(hasText(ready, substring = true)).assertCountEquals(0)
        compose.onNodeWithTag(FirstSyncTags.DONE).assertDoesNotExist()
        compose.onNodeWithTag(FirstSyncTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun keysWhileItSyncsSkipNothing() {
        show(FirstSyncPhase.Syncing(copied = 5, total = 10))

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(0, skipped)
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
        const val BENEATH = "beneath"
    }
}
