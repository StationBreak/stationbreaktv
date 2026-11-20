// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ChannelReadyNotice
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import java.io.File

/**
 * The ready notice and the "Getting ready" row (#776) as PNG files for the PR, drawn from made-up channels (no
 * library, no device data, so the frames are safe to publish). The same path as `GuideScreenshotTest`: UiAutomation's
 * screenshot, written to the folder the emulator job already pulls and uploads. The names start with `ready-`. The
 * Channels list has no light theme (it is drawn on its own dark ground); the notice takes the guide's colours, so it
 * is shot in both.
 */
@RunWith(AndroidJUnit4::class)
class ReadyScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun row(
        id: String,
        number: Int,
        name: String,
        gettingReady: Boolean = false,
    ) = ChannelRow(
        id = ChannelId(id),
        number = number,
        name = name,
        group = ChannelGroup.On,
        kind = ChannelKind.Starter,
        kid = false,
        optIn = false,
        reason = null,
        hasOverride = false,
        gettingReady = gettingReady,
    )

    private fun list(vararg waiting: String) =
        ChannelsUi.Empty.copy(
            rows =
                listOf(
                    row("neon", 352, "Neon Rain", "neon" in waiting),
                    row("noir", 424, "Noir", "noir" in waiting),
                    row("sun", 431, "Sunday Matinee", "sun" in waiting),
                    row("late", 507, "Late Late Show", "late" in waiting),
                ),
            focus = ChannelsFocus.Row(ChannelId("neon")),
            access = LineupAccess.Editable,
            listed = true,
            reasonsRead = true,
        )

    private fun shootList(
        name: String,
        ui: ChannelsUi,
        notice: List<Pair<Int, String>> = emptyList(),
    ) {
        val hole = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(GuideColors.Dark.page)) {
                    ChannelsScreen(ui, hole)
                    ChannelReadyNotice(notice)
                }
            }
        }
        settle(name)
    }

    private fun shootNotice(
        name: String,
        colors: GuideColors,
        channels: List<Pair<Int, String>>,
    ) {
        compose.setContent {
            StationBreakTheme {
                ProvideGuideColors(colors) {
                    Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(colors.page)) {
                        ChannelReadyNotice(channels)
                    }
                }
            }
        }
        settle(name)
    }

    private fun settle(name: String) {
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        save(name)
    }

    /** Right after a switch-on: the row has no reason yet, and says it is getting ready. */
    @Test
    fun theRowGettingReady() = shootList("ready-row-getting-ready.png", list("neon"))

    /** The channel is ready: the row has no note, and the notice names it over the list. */
    @Test
    fun theNoticeOverTheList() = shootList("ready-list-notice.png", list(), listOf(352 to "Neon Rain"))

    @Test
    fun theNoticeForOneChannel() = shootNotice("ready-notice-one.png", GuideColors.Dark, ONE)

    @Test
    fun theNoticeForOneChannelLight() = shootNotice("ready-notice-one-light.png", GuideColors.Light, ONE)

    @Test
    fun theNoticeForSeveralChannels() = shootNotice("ready-notice-many.png", GuideColors.Dark, TWO)

    @Test
    fun theNoticeForSeveralChannelsLight() = shootNotice("ready-notice-many-light.png", GuideColors.Light, TWO)

    /** A second batch joined a showing notice: the count grew from 2 to 3. */
    @Test
    fun theNoticeAfterItGrew() = shootNotice("ready-notice-grown.png", GuideColors.Dark, THREE)

    @Test
    fun theNoticeAfterItGrewLight() = shootNotice("ready-notice-grown-light.png", GuideColors.Light, THREE)

    private fun save(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("no screenshot", bitmap)
        val context = InstrumentationRegistry.getInstrumentation().context
        // The package's own external files, and a copy on shared storage, which the shell can pull after the test
        // package is uninstalled (as the guide's frames are).
        val external = File(checkNotNull(context.getExternalFilesDir(null)), SCREENSHOT_DIR)
        val shared = File(Environment.getExternalStorageDirectory(), SCREENSHOT_DIR)
        // A run given the instrumentation argument sharedCopy=false keeps only the package's own copy.
        val sharedCopy = InstrumentationRegistry.getArguments().getString("sharedCopy") != "false"
        if (sharedCopy) grantPublicStorage(context.packageName)
        for (directory in if (sharedCopy) listOf(external, shared) else listOf(external)) {
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
        const val LOG_TAG = "ReadyScreenshot"

        /** The folder the emulator job pulls (`pull-guide-screenshots.sh`); this test's files start with `ready-`. */
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val FRAME_WAIT_MS = 600L
        const val PNG_QUALITY = 100
        val ONE = listOf(352 to "Neon Rain")
        val TWO = listOf(352 to "Neon Rain", 424 to "Noir")
        val THREE = TWO + (507 to "Late Late Show")
    }
}
