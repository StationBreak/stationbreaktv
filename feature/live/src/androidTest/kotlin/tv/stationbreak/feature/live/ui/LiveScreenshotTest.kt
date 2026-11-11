// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SubtitleForm
import tv.stationbreak.core.playback.SwitchCost
import tv.stationbreak.core.playback.VideoFacts
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.core.ui.mark.MarkCaches
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.NextProgram
import tv.stationbreak.feature.live.PickerKind
import tv.stationbreak.feature.live.PictureLabel
import tv.stationbreak.feature.live.PictureLead
import tv.stationbreak.feature.live.TransportView
import java.io.File

/**
 * The live chrome on the API 25 emulator, as PNG files for the before and after of the banner restyle (#686): the tune
 * banner, the Select banner, the channel flip and the transport strip, each over the same warm backdrop at 960 by 540
 * dp. The same path as `GuideScreenshotTest`: UiAutomation's screenshot (Compose's `captureToImage` needs API 26),
 * written to the folder the emulator job already pulls and uploads. The names start with `live-`.
 */
@RunWith(AndroidJUnit4::class)
class LiveScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The real clock, which the banner's progress line and "min left" read: the slot is 20 minutes in, 100 to go. */
    private val now = System.currentTimeMillis()

    private val episode =
        ProgramInfo(
            CanonicalId.parse("1".padStart(32, '0')),
            ScheduleItemKind.Movie,
            "Pride & Prejudice",
            null,
            null,
            null,
            2005,
        )

    /** A direct play with three audio tracks and three subtitle tracks, one of each that restarts the picture. */
    private val facts =
        StreamFacts(
            route = Route.DIRECT_PLAY,
            sourceId = SOURCE,
            audioIndex = 1,
            subtitleIndex = null,
            sources =
                listOf(
                    SourceFacts(
                        SOURCE,
                        "Director's Cut",
                        VideoFacts(1920, 1080, "hevc"),
                        audio =
                            listOf(
                                AudioFacts(1, "eng", "ac3", 6, null, true, TrackFlags(), SwitchCost.Instant),
                                AudioFacts(2, "spa", "ac3", 6, null, false, TrackFlags(), SwitchCost.Instant),
                                AudioFacts(3, "eng", "ac3", 2, "Commentary", false, TrackFlags(commentary = true)),
                            ),
                        subtitles =
                            listOf(
                                SubtitleFacts(
                                    4,
                                    "eng",
                                    "subrip",
                                    SubtitleForm.Text,
                                    null,
                                    false,
                                    false,
                                    TrackFlags(),
                                    SwitchCost.Instant,
                                ),
                                SubtitleFacts(
                                    5,
                                    "eng",
                                    "subrip",
                                    SubtitleForm.Text,
                                    "SDH",
                                    false,
                                    false,
                                    TrackFlags(sdh = true),
                                    SwitchCost.Instant,
                                ),
                                SubtitleFacts(
                                    6,
                                    "spa",
                                    "pgssub",
                                    SubtitleForm.Picture,
                                    null,
                                    false,
                                    false,
                                    TrackFlags(),
                                ),
                            ),
                    ),
                ),
            offCost = SwitchCost.Instant,
        )

    /** The same play where the server offers a second version: the Version chip is a button, and has a picker. */
    private val twoVersions =
        facts.copy(
            sources =
                facts.sources +
                    SourceFacts(
                        MediaSourceId("frame-theatrical"),
                        "Theatrical Cut",
                        VideoFacts(1280, 720, "h264"),
                        audio = emptyList(),
                        subtitles = emptyList(),
                    ),
        )

    @Suppress("LongParameterList") // One frame's choices: which banner, what plays, what is open, what it says.
    private fun banner(
        actions: Boolean,
        recipeKey: String? = MARK_KEY,
        joinedMs: Long = 29 * MINUTE,
        stream: StreamFacts? = null,
        picker: PickerKind? = null,
        label: BannerLabel = BannerLabel.Now,
        chip: PickerKind? = null,
        switchFailed: Boolean = false,
    ) = Banner(
        number = 162,
        channelName = "Date Night",
        label = label,
        title = "Pride & Prejudice",
        info = episode,
        slotStartMs = now - 20 * MINUTE,
        slotEndMs = now + 100 * MINUTE,
        joinedMs = joinedMs,
        next = NextProgram("Roman Holiday", now + 100 * MINUTE),
        actions = actions,
        hasLast = actions,
        recipeKey = recipeKey,
        stream = stream,
        picker = picker,
        chip = chip,
        switchFailed = switchFailed,
    )

    /** The logo is decoded off the main thread: wait for it, or a frame can show the initials in its place. */
    @Before
    fun warmTheMark() {
        val cache = MarkCaches.get(InstrumentationRegistry.getInstrumentation().targetContext)
        cache.request(listOf(MARK_KEY))
        val deadline = SystemClock.uptimeMillis() + MARK_WAIT_MS
        while (cache.peek(MARK_KEY) == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertNotNull("the $MARK_KEY logo did not load", cache.peek(MARK_KEY))
    }

    private fun shoot(
        name: String,
        state: LiveState,
        transport: TransportView? = null,
    ) {
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    LiveScreen(state = state, transport = transport, nowMs = { now }) { Backdrop() }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        save(name)
    }

    /**
     * The guide open over live TV: a stand-in for the guide's page (its real [GuideColors] ground, with a hole where
     * the picture goes) over a bright picture, which is the hardest ground for the label's scrim. [title] is what the
     * label says is playing; null draws no label, as before #722. (Live cannot depend on the guide module, so the
     * page is a stand-in; the real guide's frames are `GuideScreenshotTest`'s.)
     */
    private fun shootGuide(
        name: String,
        colors: GuideColors,
        title: String?,
    ) {
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    LiveScreen(
                        state = LiveState.Watching(null),
                        nowMs = { now },
                        guide = { hole -> GuideStandIn(hole, colors.page) },
                        pictureLabel =
                            title?.let { PictureLabel(162, "Date Night", MARK_KEY, PictureLead.NowPlaying, it) },
                    ) { BrightPicture() }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        save(name)
    }

    @Test
    fun theGuideWithTheLabel() = shootGuide("live-guide-label.png", GuideColors.Dark, "Pride & Prejudice")

    @Test
    fun theGuideWithTheLabelLight() = shootGuide("live-guide-label-light.png", GuideColors.Light, "Pride & Prejudice")

    @Test
    fun theGuideWithALongTitleOnTheLabel() =
        shootGuide(
            "live-guide-label-long.png",
            GuideColors.Dark,
            "The Late Late Picture Show With a Title Long Enough to Reach the Picture's Edge",
        )

    @Test
    fun theGuideWithoutALabel() = shootGuide("live-guide-nolabel.png", GuideColors.Dark, null)

    @Test
    fun theGuideWithoutALabelLight() = shootGuide("live-guide-nolabel-light.png", GuideColors.Light, null)

    @Composable
    private fun GuideStandIn(
        hole: DpRect,
        page: Color,
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val cut =
                    Path().apply {
                        addRect(Rect(hole.left.toPx(), hole.top.toPx(), hole.right.toPx(), hole.bottom.toPx()))
                    }
                clipPath(cut, ClipOp.Difference) { drawRect(page) }
            },
        )
    }

    /** A pale sky over a darker ground: light at the top edge, where the label sits, so its scrim is what is tested. */
    @Composable
    private fun BrightPicture() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE3EDF6), Color(0xFF8FB0CF), Color(0xFF47627A)))),
        )
    }

    @Test
    fun theTuneBanner() = shoot("live-tune.png", LiveState.Watching(banner(actions = false)))

    /** Joined under a minute in: "Just started" where "N min in" would be. */
    @Test
    fun theTuneBannerJustStarted() {
        val justStarted = banner(actions = false, joinedMs = 20_000)
        shoot("live-tune-just-started.png", LiveState.Watching(justStarted))
    }

    /** A custom channel: no logo, so its initials and then its name after the number. */
    @Test
    fun theTuneBannerWithNoMark() {
        val noMark = banner(actions = false, recipeKey = null)
        shoot("live-tune-nomark.png", LiveState.Watching(noMark))
    }

    @Test
    fun theSelectBanner() = shoot("live-select.png", LiveState.Watching(banner(actions = true)))

    @Test
    fun theSelectBannerWithItsChips() {
        val state = LiveState.Watching(banner(actions = true, stream = facts))
        shoot("live-select-chips.png", state)
    }

    @Test
    fun theAudioPicker() =
        shoot(
            "live-picker-audio.png",
            LiveState.Watching(banner(actions = true, stream = facts, picker = PickerKind.Audio)),
        )

    @Test
    fun theSubtitlesPicker() =
        shoot(
            "live-picker-subtitles.png",
            LiveState.Watching(banner(actions = true, stream = facts, picker = PickerKind.Subtitles)),
        )

    @Test
    fun theVersionPicker() =
        shoot(
            "live-picker-version.png",
            LiveState.Watching(banner(actions = true, stream = twoVersions, picker = PickerKind.Version)),
        )

    @Test
    fun switchingVersionUnderTheSnow() =
        shoot(
            "live-switching-version.png",
            LiveState.Tuning(banner(actions = false, stream = twoVersions, label = BannerLabel.SwitchingVersion)),
        )

    @Test
    fun switchingAudioUnderTheSnow() =
        shoot(
            "live-switching.png",
            LiveState.Tuning(banner(actions = false, label = BannerLabel.SwitchingAudio)),
        )

    /** The Select banner reopened after a re-tune that took: the chip used has the focus and shows the new value. */
    @Test
    fun theBannerAfterASwitchThatTook() =
        shoot(
            "live-switch-took.png",
            LiveState.Watching(banner(actions = true, stream = facts, chip = PickerKind.Audio)),
        )

    /** The same banner after a re-tune that fell back: the chip reads "Couldn't switch" in its normal text colour. */
    @Test
    fun theBannerAfterASwitchThatDidNotTake() =
        shoot(
            "live-switch-failed.png",
            LiveState.Watching(
                banner(actions = true, stream = facts, chip = PickerKind.Audio, switchFailed = true),
            ),
        )

    /** The widest row: Live (behind), Info, Last, a subtitle in use, a transcode, and "Couldn't switch" on the chip. */
    @Test
    fun theBannerAfterAVersionThatDidNotTake() =
        shoot(
            "live-switch-failed-version.png",
            LiveState.Watching(
                banner(actions = true, stream = twoVersions, chip = PickerKind.Version, switchFailed = true),
            ),
        )

    @Test
    fun theBannerAfterAFailedSwitchAtItsWidest() {
        val transcoding = facts.copy(route = Route.TRANSCODE, subtitleIndex = 4)
        shoot(
            "live-switch-failed-widest.png",
            LiveState.Watching(
                banner(actions = true, stream = transcoding, chip = PickerKind.Audio, switchFailed = true),
            ),
            behindView(),
        )
    }

    @Test
    fun theChannelFlip() {
        val target = ChannelTag(162, "Date Night", MARK_KEY)
        shoot("live-flip.png", LiveState.Choosing(target, overPicture = true))
    }

    @Test
    fun theTransportStrip() =
        shoot(
            "live-transport.png",
            LiveState.Watching(null),
            behindView(),
        )

    private fun behindView() =
        TransportView(
            paused = true,
            seeking = false,
            seekable = true,
            visible = true,
            mediaMs = 10 * MINUTE,
            targetMs = null,
            asOfMs = now,
            blockStartMs = now - 15 * MINUTE,
            inPointMs = 0,
            cutMs = 60 * MINUTE,
            driftMs = 0,
        )

    /** A warm vignette, so the banner's surface and text are judged over a picture and not over black. */
    @Composable
    private fun Backdrop() {
        Box(
            Modifier
                .requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFB08A60), Color(0xFF3A2A1E), Color(0xFF0B0908)),
                        center = Offset(480f * DENSITY_GUESS, 200f * DENSITY_GUESS),
                        radius = 700f * DENSITY_GUESS,
                    ),
                ),
        )
    }

    private fun save(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("no screenshot", bitmap)
        val context = InstrumentationRegistry.getInstrumentation().context
        // The package's own external files, and a copy on shared storage, which the shell can pull after the test
        // package is uninstalled (as the guide's frames are).
        val external = File(checkNotNull(context.getExternalFilesDir(null)), SCREENSHOT_DIR)
        val shared = File(Environment.getExternalStorageDirectory(), SCREENSHOT_DIR)
        grantPublicStorage(context.packageName)
        for (directory in listOf(external, shared)) {
            assertTrue("could not create $directory", directory.mkdirs() || directory.isDirectory)
            val file = File(directory, name)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
            assertTrue("$name is empty", file.length() > 0)
            Log.i(LOG_TAG, "wrote ${file.absolutePath} (${file.length()} bytes)")
        }
    }

    private fun grantPublicStorage(packageName: String) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val grantedAlready =
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (grantedAlready) return
        val granted =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant $packageName ${Manifest.permission.WRITE_EXTERNAL_STORAGE}",
            )
        ParcelFileDescriptor.AutoCloseInputStream(granted).use { it.readBytes() }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        )
    }

    private companion object {
        const val MARK_KEY = "date-night"
        val SOURCE = MediaSourceId("frame-source")
        const val MARK_WAIT_MS = 10_000L
        const val POLL_MS = 50L
        const val LOG_TAG = "LiveScreenshot"

        /** The folder the emulator job pulls (`pull-guide-screenshots.sh`); this test's files start with `live-`. */
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540
        const val DENSITY_GUESS = 2f
        const val FRAME_WAIT_MS = 600L
        const val PNG_QUALITY = 100
        const val MINUTE = 60_000L
    }
}
