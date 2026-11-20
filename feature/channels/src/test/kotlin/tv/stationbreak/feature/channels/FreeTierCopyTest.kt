// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A free viewer never reads the word Pro or "coming soon": the lines they can reach say neither. */
class FreeTierCopyTest {
    private val resources = File("src/main/res/values")

    private fun stringsOf(vararg files: String): Map<String, String> =
        files
            .flatMap { name ->
                Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(File(resources, name).readText()).toList()
            }.associate { it.groupValues[1] to it.groupValues[2] }

    private val strings = stringsOf("strings.xml", "strings_pro.xml")

    private val freeReachable =
        listOf(
            "pro_custom_limit_note",
            "channels_shelf_below_cap_free",
            "channels_shelf_opt_in_free",
            "channels_shelf_empty",
            "channels_shelf_thin",
            "channels_shelf_no_number",
            "channels_refused_number_taken",
            "builder_number_taken_pick",
            "channels_refused_kids_band",
            "channels_refused_last_on",
            "channels_refused_no_number",
        )

    @Test
    fun theFreeReachableLinesNameNoProAndNoComingSoon() {
        freeReachable.forEach { name ->
            val text = checkNotNull(strings[name]) { "$name is not a string" }
            assertFalse("$name: $text", Regex("""\bPro\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("$name: $text", text.contains("coming soon", ignoreCase = true))
        }
    }

    @Test
    fun theFreeShelfLinesDoNotOfferToSwitchOn() {
        assertEquals("Fits, but your lineup is full", strings["channels_shelf_below_cap_free"])
        assertEquals("Adult content, off in this app", strings["channels_shelf_opt_in_free"])
        assertTrue(strings.getValue("channels_shelf_below_cap").contains("Switch it on"))
    }

    @Test
    fun theCustomLimitNoteIsTheFreeSentenceOnly() {
        assertEquals("Free includes 5 custom channels. Delete one to make another.", strings["pro_custom_limit_note"])
    }
}
