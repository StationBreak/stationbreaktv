// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.StationBreakTheme
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

    private val episode =
        ProgramInfo(CanonicalId.parse("1".padStart(32, '0')), ScheduleItemKind.Movie, "Pride & Prejudice", null, null, null)

    private fun banner(actions: Boolean) =
        Banner(
            number = 162,
            channelName = "Date Night",
            label = BannerLabel.Now,
            title = "Pride & Prejudice",
            info = episode,
            slotStartMs = NOW - 20 * MINUTE,
            slotEndMs = NOW + 100 * MINUTE,
            joinedMs = 29 * MINUTE,
            next = NextProgram("Roman Holiday", NOW + 100 * MINUTE),
            actions = actions,
            hasLast = actions,
        )

    private fun shoot(
        name: String,
        state: LiveState,
        transport: TransportView? = null,
    ) {
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    LiveScreen(state = state, transport = transport, nowMs = { NOW }) { Backdrop() }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        save(name)
    }

    @Test
    fun theTuneBanner() = shoot("live-tune.png", LiveState.Watching(banner(actions = false)))

    @Test
    fun theSelectBanner() = shoot("live-select.png", LiveState.Watching(banner(actions = true)))

    @Test
    fun theChannelFlip() = shoot("live-flip.png", LiveState.Choosing(ChannelTag(162, "Date Night"), overPicture = true))

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
                asOfMs = NOW,
                blockStartMs = NOW - 15 * MINUTE,
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
        const val LOG_TAG = "LiveScreenshot"

        /** The folder the emulator job pulls (`pull-guide-screenshots.sh`); this test's files start with `live-`. */
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540
        const val DENSITY_GUESS = 2f
        const val FRAME_WAIT_MS = 600L
        const val PNG_QUALITY = 100
        const val MINUTE = 60_000L
        const val NOW = 1_800_000_000_000L
    }
}
