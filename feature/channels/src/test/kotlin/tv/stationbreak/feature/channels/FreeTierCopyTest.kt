// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Coming soon" is never said, and Pro is named only where a free viewer reaches for something that is part of it
 * (#99): the one note and the one button of the Pro prompt. Nothing else in the module names Pro.
 */
class FreeTierCopyTest {
    private val strings: Map<String, String> =
        checkNotNull(File("src/main/res/values").listFiles { f -> f.extension == "xml" })
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(file.readText()).toList()
            }.associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    // The strings a free viewer reads on reaching for a Pro action: the notes, and the button to the purchase screen.
    // They are the only exemption: reachable only with the store available, so the free release never shows them, and
    // resources cannot be split per availability. Exact keys, all in strings_pro.xml, so any other Pro mention fails.
    private val proPrompt =
        setOf(
            "channels_pro_needs_reshape",
            "channels_pro_needs_reset",
            "channels_pro_needs_custom",
            "channels_action_see_pro",
        )

    @Test
    fun noStringButThePromptNamesProOrComingSoon() {
        assertTrue("the strings were not found", strings.size > 50)
        (strings - proPrompt).forEach { (name, text) ->
            assertFalse("$name: $text", Regex("""\bPro\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("$name: $text", text.contains("coming soon", ignoreCase = true))
        }
    }

    @Test
    fun theExemptKeysAreExactlyTheProNamingStringsOfStringsProXml() {
        val file = File("src/main/res/values/strings_pro.xml").readText()
        val named =
            Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
                .findAll(file)
                .filter { Regex("""\bPro\b""").containsMatchIn(it.groupValues[2]) }
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(proPrompt, named)
    }

    @Test
    fun theProPromptNamesProAndOffersTheWay() {
        proPrompt.forEach { assertTrue(it, strings.getValue(it).contains("Pro")) }
        assertFalse(strings.values.any { it.contains("coming soon", ignoreCase = true) })
    }

    @Test
    fun theFreeShelfLinesDoNotOfferToSwitchOn() {
        assertEquals("Fits, but your lineup is full", strings["channels_shelf_below_cap_free"])
        assertEquals("Adult content. Can't be switched on", strings["channels_shelf_opt_in_free"])
        assertTrue(strings.getValue("channels_shelf_below_cap").contains("Switch it on"))
    }

    @Test
    fun theCustomLimitNoteIsTheFreeSentenceOnly() {
        val note = "Free includes 5 custom channels. Delete some of yours to make room."
        assertEquals(note, strings["pro_custom_limit_note"])
    }
}
