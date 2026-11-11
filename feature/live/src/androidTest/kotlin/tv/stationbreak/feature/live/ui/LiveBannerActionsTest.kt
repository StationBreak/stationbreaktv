// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.TrackFlags
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.playback.AudioFacts
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SwitchCost
import tv.stationbreak.core.playback.VideoFacts
import tv.stationbreak.core.ui.ProgramInfoTags
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.NextProgram
import tv.stationbreak.feature.live.PickerKind
import tv.stationbreak.feature.live.TransportView
import java.util.concurrent.CopyOnWriteArrayList

/** The banner's progress and "Next" line, the actions banner's buttons and the info overlay on screen (spec 17). */
@RunWith(AndroidJUnit4::class)
class LiveBannerActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<LiveCommand>()
    private val id = CanonicalId.parse("1".padStart(32, '0'))
    private val movie = ProgramInfo(id, ScheduleItemKind.Movie, "Test Movie A", null, null, null)

    private fun banner(
        actions: Boolean = false,
        hasLast: Boolean = false,
        next: NextProgram? = NextProgram("Test Movie B", START + 30 * MINUTE),
    ) = Banner(
        4,
        "Test Channel",
        BannerLabel.Now,
        "Test Movie A",
        movie,
        START,
        START + 30 * MINUTE,
        null,
        next,
        actions,
        hasLast,
    )

    private fun show(
        banner: Banner,
        info: LiveInfo? = null,
    ) {
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner.takeIf { info == null }), info, { sent += it }) {}
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun progressAndNextAreShown() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        val next = compose.onNodeWithTag(LiveTags.BANNER_NEXT)
        next.assertExists()
        val text = next.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(text, text.startsWith("Next: Test Movie B at "))
    }

    @Test
    fun progressReadsTheClockAndSaysHowLongIsLeft() {
        compose.setContent {
            StationBreakTheme { CableBanner(banner(), clock = { START + 12 * MINUTE }) }
        }
        compose.waitForIdle()
        val config = compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).fetchSemanticsNode().config
        assertEquals(0.4f, config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertEquals(listOf("18 min left"), config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun noNextLineWhenNothingFollows() {
        show(banner(next = null))
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_NEXT).assertDoesNotExist()
    }

    @Test
    fun aTuneBannerHasNoButtons() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_ACTIONS).assertDoesNotExist()
    }

    @Test
    fun theActionsBannerOpensOnInfo() {
        show(banner(actions = true, hasLast = true))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertExists()
    }

    @Test
    fun lastIsLeftOutWhenThereIsNoChannelToGoBackTo() {
        show(banner(actions = true, hasLast = false))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertDoesNotExist()
    }

    /**
     * The app's own key path: [LiveKeysRoot] takes the keys first, as on the TV. Select asks for the actions banner,
     * which this stand-in machine then shows, so the buttons appear the way they do after a real press.
     */
    private fun showKeysRoot(transport: TransportView? = null) {
        compose.setContent {
            var state by remember { mutableStateOf<LiveState>(LiveState.Watching(banner())) }
            val machine = { command: LiveCommand ->
                sent += command
                if (command == LiveCommand.ShowBanner) {
                    state = LiveState.Watching(banner(actions = true, hasLast = true))
                }
            }
            StationBreakTheme { LiveKeysRoot(state, null, machine, transport = transport) {} }
        }
        compose.waitForIdle()
    }

    /** One remote press: down, a frame or more, then up, as a viewer's thumb does. */
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { keyDown(key) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { keyUp(key) }
        compose.waitForIdle()
    }

    @Test
    fun selectWithNoBannerOpensTheActionsBannerOnInfoAndNothingMore() {
        showKeysRoot()
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        // The release of that same press must not reach the Info button it moved the focus to.
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent.toList())
    }

    @Test
    fun aHeldSelectOpensTheActionsBannerAndNeverClicksInfo() {
        showKeysRoot()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downAt = SystemClock.uptimeMillis()
        // A held key: the first down, auto-repeats with a rising count, then one release, through the real input path.
        for (repeat in 0..2) {
            val down = KeyEvent(downAt, downAt + repeat * REPEAT_MS, ACTION_DOWN, KEYCODE_DPAD_CENTER, repeat)
            instrumentation.sendKeySync(down)
            compose.waitForIdle()
        }
        val release = KeyEvent(downAt, downAt + 3 * REPEAT_MS, ACTION_UP, KEYCODE_DPAD_CENTER, 0)
        instrumentation.sendKeySync(release)
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        // Neither a repeat nor the release reached the Info button the first down moved the focus to.
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent.toList())
    }

    @Test
    fun lastActionTunesPreviousChannel() {
        showKeysRoot()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertIsFocused()
        sent.clear()
        press(Key.DirectionCenter)
        assertEquals(listOf<LiveCommand>(LiveCommand.Previous), sent.toList())
    }

    private fun view(paused: Boolean) =
        TransportView(
            paused = paused,
            seeking = false,
            seekable = true,
            visible = true,
            mediaMs = 10 * MINUTE,
            targetMs = null,
            asOfMs = START,
            blockStartMs = START - 15 * MINUTE,
            inPointMs = 0,
            cutMs = 60 * MINUTE,
            driftMs = 0,
        )

    @Test
    fun liveShowsOnlyWhileBehindAndInfoKeepsTheFocus() {
        var transport by mutableStateOf<TransportView?>(null)
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner(actions = true, hasLast = true)), null, {}, null, transport) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertDoesNotExist()
        compose.runOnIdle { transport = view(paused = false) }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        // Live, Info, Last: left to right.
        val live =
            compose
                .onNodeWithTag(LiveTags.BANNER_ACTION_LIVE)
                .fetchSemanticsNode()
                .boundsInRoot.left
        val info =
            compose
                .onNodeWithTag(LiveTags.BANNER_ACTION_INFO)
                .fetchSemanticsNode()
                .boundsInRoot.left
        val last =
            compose
                .onNodeWithTag(LiveTags.BANNER_ACTION_LAST)
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue("$live $info $last", live < info && info < last)
        compose.runOnIdle { transport = null }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertDoesNotExist()
    }

    @Test
    fun theBannerUpWhileLiveGainsLiveWhenTheViewerPauses() {
        var transport by mutableStateOf<TransportView?>(null)
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner(actions = true, hasLast = true)), null, {}, null, transport) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertDoesNotExist()
        compose.runOnIdle { transport = view(paused = true) }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertExists()
    }

    @Test
    fun theFocusMovesToInfoWhenLiveGoesWhileFocused() {
        var transport by mutableStateOf<TransportView?>(view(paused = true))
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner(actions = true, hasLast = true)), null, {}, null, transport) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertIsFocused()
        compose.runOnIdle { transport = null }
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
    }

    @Test
    fun liveSendsGoLive() {
        showKeysRoot(transport = view(paused = false))
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LIVE).assertIsFocused()
        sent.clear()
        press(Key.DirectionCenter)
        assertEquals(listOf<LiveCommand>(LiveCommand.GoLive), sent.toList())
    }

    @Test
    fun infoActionOpensTheOverlay() {
        showKeysRoot()
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        sent.clear()
        press(Key.DirectionCenter)
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent.toList())
    }

    @Test
    fun enterWorksLikeSelectOnAButton() {
        showKeysRoot()
        press(Key.Enter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        sent.clear()
        press(Key.Enter)
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent.toList())
    }

    @Test
    fun theOverlayReplacesTheBannerAndShowsItsPlaceholdersWhileLoading() {
        show(banner(), LiveInfo(banner(), null, loading = true))
        compose.onNodeWithTag(LiveTags.INFO).assertExists()
        compose.onNodeWithTag(ProgramInfoTags.LOADING, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    private fun facts(
        route: Route,
        secondAudio: SwitchCost = SwitchCost.Retune,
    ) = StreamFacts(
        route,
        SOURCE,
        1,
        null,
        listOf(
            SourceFacts(
                SOURCE,
                null,
                VideoFacts(1920, 1080, "hevc"),
                listOf(
                    AudioFacts(1, "eng", "ac3", 6, null, true, TrackFlags()),
                    AudioFacts(2, "spa", "ac3", 6, null, false, TrackFlags(), secondAudio),
                ),
                emptyList(),
            ),
        ),
    )

    private fun selectBanner(
        route: Route,
        picker: PickerKind? = null,
        secondAudio: SwitchCost = SwitchCost.Retune,
    ) = banner(actions = true).copy(stream = facts(route, secondAudio), picker = picker)

    private fun chipText(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun chipDescription(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString("")

    @Test
    fun aSwitchThatReopenedTheBannerHasTheFocusOnTheChipUsed() {
        show(selectBanner(Route.DIRECT_PLAY).copy(chip = PickerKind.Audio))
        compose.onNodeWithTag(LiveTags.BANNER_CHIP_AUDIO).assertIsFocused()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsNotFocused()
        assertTrue(chipText(LiveTags.BANNER_CHIP_AUDIO), "English 5.1" in chipText(LiveTags.BANNER_CHIP_AUDIO))
    }

    @Test
    fun afterAFailedSwitchTheChipReadsCouldntSwitchAndNamesWhatStillPlays() {
        show(selectBanner(Route.DIRECT_PLAY).copy(chip = PickerKind.Audio, switchFailed = true))
        compose.onNodeWithTag(LiveTags.BANNER_CHIP_AUDIO).assertIsFocused()
        val text = chipText(LiveTags.BANNER_CHIP_AUDIO)
        assertTrue(text, "Couldn't switch" in text)
        assertTrue("the value is replaced, not joined: $text", "English" !in text)
        assertEquals("Audio, couldn't switch, still English 5.1", chipDescription(LiveTags.BANNER_CHIP_AUDIO))
    }

    @Test
    fun theChipsNeverFailWithoutASwitchAndOnlyTheUsedOneFails() {
        show(selectBanner(Route.DIRECT_PLAY).copy(chip = PickerKind.Audio, switchFailed = true))
        assertTrue("Couldn't switch" !in chipText(LiveTags.BANNER_CHIP_SUBTITLES))
        assertEquals("Subtitles: None", chipDescription(LiveTags.BANNER_CHIP_SUBTITLES))
    }

    @Test
    fun anOrdinaryActionsBannerKeepsTheFocusOnInfoAndSaysNothingFailed() {
        show(selectBanner(Route.DIRECT_PLAY))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        assertEquals("Audio: English 5.1", chipDescription(LiveTags.BANNER_CHIP_AUDIO))
    }

    @Test
    fun transcodingIsSaidAtTheEndOfTheChipsRowOnlyForATranscode() {
        show(selectBanner(Route.TRANSCODE))
        val label = compose.onNodeWithTag(LiveTags.BANNER_TRANSCODING)
        label.assertExists()
        assertEquals(
            listOf("Transcoding"),
            label.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
        )
        assertTrue("not focusable", SemanticsProperties.Focused !in label.fetchSemanticsNode().config)
        assertTrue("not clickable", SemanticsActions.OnClick !in label.fetchSemanticsNode().config)
        // At the right end of the row: past the last chip.
        val chip = compose.onNodeWithTag(LiveTags.BANNER_CHIP_SUBTITLES).fetchSemanticsNode().boundsInRoot
        assertTrue(label.fetchSemanticsNode().boundsInRoot.left >= chip.right)
    }

    private fun withSecondVersion(facts: StreamFacts): StreamFacts {
        val first = facts.sources.single()
        val other = first.copy(id = MediaSourceId("other"), name = "Theatrical")
        return facts.copy(sources = listOf(first, other))
    }

    private fun twoVersions(picker: PickerKind? = null): Banner {
        val banner = selectBanner(Route.DIRECT_PLAY, picker)
        return banner.copy(stream = withSecondVersion(checkNotNull(banner.stream)))
    }

    private fun versionChipConfig() = compose.onNodeWithTag(LiveTags.BANNER_CHIP_VERSION).fetchSemanticsNode().config

    @Test
    fun withOneVersionTheVersionChipIsShownButNotAButton() {
        show(selectBanner(Route.DIRECT_PLAY))
        assertTrue(SemanticsActions.OnClick !in versionChipConfig())
    }

    @Test
    fun withTwoVersionsTheVersionChipOpensItsPicker() {
        show(twoVersions())
        assertTrue(SemanticsActions.OnClick in versionChipConfig())
        compose.onNodeWithTag(LiveTags.BANNER_CHIP_VERSION).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf<LiveCommand>(LiveCommand.OpenPicker(PickerKind.Version)), sent.toList())
    }

    @Test
    fun theVersionPickerListsEveryVersionAndSaysTheBriefPauseOnce() {
        show(twoVersions(PickerKind.Version))
        compose.onNodeWithTag(LiveTags.PICKER_ROW + 0).assertExists()
        compose.onNodeWithTag(LiveTags.PICKER_ROW + 1).assertExists()
        compose.onNodeWithTag(LiveTags.PICKER_HEADING).assertExists()
    }

    @Test
    fun directPlaySaysNothingOfTheRoute() {
        show(selectBanner(Route.DIRECT_PLAY))
        compose.onNodeWithTag(LiveTags.BANNER_CHIP_AUDIO).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_TRANSCODING).assertDoesNotExist()
    }

    @Test
    fun directStreamSaysNothingOfTheRoute() {
        show(selectBanner(Route.DIRECT_STREAM))
        compose.onNodeWithTag(LiveTags.BANNER_CHIP_AUDIO).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_TRANSCODING).assertDoesNotExist()
    }

    @Test
    fun aTuneBannerNeverSaysTranscoding() {
        show(banner(actions = false).copy(stream = facts(Route.TRANSCODE)))
        compose.onNodeWithTag(LiveTags.BANNER_TRANSCODING).assertDoesNotExist()
    }

    @Test
    fun whenEveryRowPausesThePickerSaysSoOnceInAHeading() {
        show(selectBanner(Route.TRANSCODE, PickerKind.Audio))
        val heading = compose.onNodeWithTag(LiveTags.PICKER_HEADING)
        heading.assertExists()
        assertEquals(
            listOf("brief pause"),
            heading.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
        )
        // The rows no longer carry the label: their merged description still does.
        compose.onAllNodesWithText("brief pause", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun whenOnlySomeRowsPauseEachSaysSoAndThereIsNoHeading() {
        show(selectBanner(Route.DIRECT_PLAY, PickerKind.Audio, secondAudio = SwitchCost.Instant))
        compose.onNodeWithTag(LiveTags.PICKER).assertExists()
        compose.onNodeWithTag(LiveTags.PICKER_HEADING).assertDoesNotExist()
        compose.onAllNodesWithText("brief pause", useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        val SOURCE = MediaSourceId("src")
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
        const val REPEAT_MS = 50L
    }
}
