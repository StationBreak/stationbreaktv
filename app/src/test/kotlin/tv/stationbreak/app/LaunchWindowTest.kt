// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The window the system shows before Compose draws: ink, never white, and on the launcher activity. */
class LaunchWindowTest {
    private val inkHex = "#1C1B19" // BrandColors.Ink (internal to core:ui, so written out)

    @Test
    fun theLaunchWindowBackgroundIsInk() {
        val themes = File("src/main/res/values/themes.xml").readText()
        val colour = Regex("""name="launch_window_background">(#[0-9A-Fa-f]{6})<""").find(themes)?.groupValues?.get(1)
        assertEquals(inkHex, colour?.uppercase())
        assertTrue(themes.contains("""<item name="android:windowBackground">@color/launch_window_background</item>"""))
    }

    @Test
    fun theLauncherActivityUsesTheLaunchTheme() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue(activity.contains("android:theme=\"@style/Theme.StationBreak.Launch\""))
    }
}
