// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app never says Pro or "coming soon" to a viewer: no string of the module names either. */
class FreeTierCopyTest {
    private val strings: Map<String, String> =
        checkNotNull(File("src/main/res/values").listFiles { f -> f.extension == "xml" })
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(file.readText()).toList()
            }.associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    // Strings that may name Pro because only a Pro viewer reads them. None do: Pro's choices are simply not shown.
    private val proOnly = emptySet<String>()

    @Test
    fun noStringNamesProOrComingSoon() {
        assertTrue("the strings were not found", strings.size > 50)
        (strings - proOnly).forEach { (name, text) ->
            assertFalse("$name: $text", Regex("""\bPro\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("$name: $text", text.contains("coming soon", ignoreCase = true))
        }
    }

    @Test
    fun theFreeShelfLinesDoNotOfferToSwitchOn() {
        assertEquals("Fits, but your lineup is full", strings["channels_shelf_below_cap_free"])
        assertEquals("Adult content. Can't be switched on", strings["channels_shelf_opt_in_free"])
        assertTrue(strings.getValue("channels_shelf_below_cap").contains("Switch it on"))
    }

    @Test
    fun theCustomLimitNoteIsTheFreeSentenceOnly() {
        assertEquals("Free includes 5 custom channels. Delete one to make another.", strings["pro_custom_limit_note"])
    }
}
