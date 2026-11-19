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
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import java.io.File

/**
 * The Channels panel with a FULL lineup (#785) as PNG files for the PR: 60 channels on, and some on the shelf with
 * their reasons, under the heads-up (Pro only, #799). Made-up channel names only (no library, no device data, so the
 * frames are safe to publish). The same path as `ReadyScreenshotTest`: UiAutomation's screenshot, written to the folder
 * the emulator job already pulls and uploads. The names start with `channels-`. The Channels list has no light theme.
 */
@RunWith(AndroidJUnit4::class)
class ChannelsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val adjectives =
        listOf("Neon", "Velvet", "Paper", "Copper", "Hollow", "Silver", "Rusty", "Quiet", "Orbit", "Marble")
    private val nouns = listOf("Rain", "Matinee", "Harbour", "Signal", "Garden", "Circus")

    private fun on(n: Int) =
        ChannelRow(
            id = ChannelId("on$n"),
            number = FIRST_NUMBER + n,
            name = "${adjectives[(n - 1) % adjectives.size]} ${nouns[(n - 1) / adjectives.size]}",
            group = ChannelGroup.On,
            kind = ChannelKind.Starter,
            kid = false,
            optIn = false,
            reason = null,
            hasOverride = false,
        )

    private fun shelved(
        n: Int,
        name: String,
        reason: ShelfReason,
    ) = ChannelRow(
        id = ChannelId("shelf$n"),
        number = SHELF_NUMBER + n,
        name = name,
        group = ChannelGroup.Shelf,
        kind = ChannelKind.Starter,
        kid = false,
        optIn = reason == ShelfReason.OptIn,
        reason = null,
        shelfReason = reason,
        hasOverride = false,
    )

    private fun fullLineup(
        focus: ChannelsFocus,
        isPro: Boolean = true,
    ) = ChannelsUi.Empty.copy(
        rows =
            (1..ON_COUNT).map { on(it) } +
                listOf(
                    shelved(1, "Fog Lantern", ShelfReason.BelowCap),
                    shelved(2, "Midnight Ferry", ShelfReason.BelowCap),
                    shelved(3, "Tin Parade", ShelfReason.TooThin),
                    shelved(4, "Late Orchard", ShelfReason.Empty),
                    shelved(5, "Velvet Noir", ShelfReason.OptIn),
                ),
        focus = focus,
        access = LineupAccess.Editable,
        listed = true,
        reasonsRead = true,
        isPro = isPro,
    )

    private fun shoot(
        name: String,
        ui: ChannelsUi,
    ) {
        val hole = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(GuideColors.Dark.page)) {
                    ChannelsScreen(ui, hole)
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        save(name)
    }

    /** The top of a 60-channel lineup, the heads-up under the list (Pro). */
    @Test
    fun theFullLineupTop() = shoot("channels-full-top.png", fullLineup(ChannelsFocus.Household))

    /** The free viewer's panel: no heads-up under the list (#799). */
    @Test
    fun theFreeTop() = shoot("channels-free-top.png", fullLineup(ChannelsFocus.Household, isPro = false))

    /** The list scrolled to More channels: the shelf reasons, "Fits, but your lineup is full" too. */
    @Test
    fun theFullLineupShelf() = shoot("channels-full-shelf.png", fullLineup(ChannelsFocus.Row(ChannelId("shelf2"))))

    /** With a line under the title ("Choosing channels…") as well: the list gives up a line, nothing else moves. */
    @Test
    fun theFullLineupWithANote() =
        shoot(
            "channels-full-note.png",
            fullLineup(ChannelsFocus.Row(ChannelId("on3"))).copy(choosing = true),
        )

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
        const val LOG_TAG = "ChannelsScreenshot"

        /** The folder the emulator job pulls (`pull-guide-screenshots.sh`); the files here start with `channels-`. */
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val FRAME_WAIT_MS = 600L
        const val PNG_QUALITY = 100
        const val ON_COUNT = 60
        const val FIRST_NUMBER = 100
        const val SHELF_NUMBER = 400
    }
}
