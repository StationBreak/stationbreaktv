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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.core.ui.mark.MarkCaches
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.NextProgram
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

    private fun banner(
        actions: Boolean,
        recipeKey: String? = MARK_KEY,
        joinedMs: Long = 29 * MINUTE,
    ) = Banner(
        number = 162,
        channelName = "Date Night",
        label = BannerLabel.Now,
        title = "Pride & Prejudice",
        info = episode,
        slotStartMs = now - 20 * MINUTE,
        slotEndMs = now + 100 * MINUTE,
        joinedMs = joinedMs,
        next = NextProgram("Roman Holiday", now + 100 * MINUTE),
        actions = actions,
        hasLast = actions,
        recipeKey = recipeKey,
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
    fun theChannelFlip() {
        val target = ChannelTag(162, "Date Night", MARK_KEY)
        shoot("live-flip.png", LiveState.Choosing(target, overPicture = true))
    }

    @Test
    fun theTransportStrip() =
        shoot(
            "live-transport.png",
            LiveState.Watching(null),
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
            ),
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
